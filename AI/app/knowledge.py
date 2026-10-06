"""Civic knowledge base, semantic search and the RAG assistant, all on pgvector in the shared Neon database."""

import hashlib
import logging
from dataclasses import dataclass
from itertools import batched
from pathlib import Path

from psycopg_pool import ConnectionPool
from pydantic import BaseModel, Field

from app import llm
from app.db import vector_literal
from app.prompts import ASSISTANT_PROMPT, ASSISTANT_SCHEMA, ASSISTANT_SYSTEM
from app.schemas import AskResponse, ComplaintHit, KnowledgeHit, Source, SyncResponse

log = logging.getLogger(__name__)

MAX_CHUNK_CHARS = 1500
DIRECTORY_SOURCE = "department-directory"
# ponytail: calibrated on the FMC knowledge base with gemini-embedding-2 (2026-09-30): on-topic questions
# scored 0.74-0.88, off-topic ones <= 0.62. Recalibrate if the embedding model or knowledge base changes.
ANSWER_MIN_SCORE = 0.68
SEARCH_MIN_SCORE = 0.60
NOT_COVERED = (
    "The FMC knowledge base doesn't cover that question. If it is about a civic problem, you can submit a "
    "complaint and it will be routed to the right department."
)


@dataclass(frozen=True)
class Chunk:
    source: str
    index: int
    title: str
    content: str

    @property
    def content_hash(self) -> str:
        return hashlib.sha256(f"{self.title}\n{self.content}".encode()).hexdigest()


class _Answer(BaseModel):
    answer: str = Field(min_length=1, max_length=2000)
    cited: list[int]


def markdown_chunks(source: str, text: str) -> list[Chunk]:
    """One chunk per '## ' section, titled 'Document: Section'; long sections are split on paragraphs."""
    doc_title, sections, heading, body = Path(source).stem.replace("-", " ").title(), [], None, []
    for line in text.splitlines():
        if line.startswith("# "):
            doc_title = line[2:].strip()
        elif line.startswith("## "):
            sections.append((heading, body))
            heading, body = line[3:].strip(), []
        else:
            body.append(line)
    sections.append((heading, body))
    chunks: list[Chunk] = []
    for section, lines in sections:
        title = doc_title if section is None else f"{doc_title}: {section}"
        for piece in _split("\n".join(lines).strip()):
            chunks.append(Chunk(source, len(chunks), title, piece))
    return chunks


def _split(text: str) -> list[str]:
    pieces, current = [], ""
    for paragraph in (p.strip() for p in text.split("\n\n")):
        if not paragraph:
            continue
        if current and len(current) + len(paragraph) + 2 > MAX_CHUNK_CHARS:
            pieces.append(current)
            current = paragraph
        else:
            current = f"{current}\n\n{paragraph}" if current else paragraph
    return [*pieces, current] if current else pieces


def document_chunks(knowledge_dir: str) -> list[Chunk]:
    return [
        chunk
        for path in sorted(Path(knowledge_dir).glob("*.md"))
        for chunk in markdown_chunks(path.name, path.read_text(encoding="utf-8"))
    ]


def directory_chunks(pool: ConnectionPool) -> list[Chunk]:
    """Live routing facts from the categories and departments tables, so answers follow admin changes."""
    with pool.connection() as conn:
        routes = conn.execute(
            """SELECT c.name, c.description, d.name, d.description
               FROM categories c LEFT JOIN departments d ON d.id = c.department_id ORDER BY c.id"""
        ).fetchall()
        departments = conn.execute("SELECT name, description FROM departments ORDER BY id").fetchall()
    chunks = [
        Chunk(
            DIRECTORY_SOURCE,
            index,
            f"Which department handles {category} complaints?",
            f"Complaints in the {category} category ({category_info or 'no description'}) are handled by "
            + (
                f"the {department} department: {department_info or 'no description'}"
                if department
                else "an administrator, who routes them to a suitable department."
            ),
        )
        for index, (category, category_info, department, department_info) in enumerate(routes)
    ]
    listing = "\n".join(f"- {name}: {info or 'no description'}" for name, info in departments)
    chunks.append(
        Chunk(DIRECTORY_SOURCE, len(chunks), "FMC departments", f"FMC departments and what they handle:\n{listing}")
    )
    return chunks


def sync(pool: ConnectionPool, knowledge_dir: str) -> SyncResponse:
    """Makes knowledge_chunks match the documents and live directory, embedding only new or changed chunks."""
    desired = document_chunks(knowledge_dir) + directory_chunks(pool)
    with pool.connection() as conn:
        existing = {
            (s, i): h for s, i, h in conn.execute("SELECT source, chunk_index, content_hash FROM knowledge_chunks")
        }
    changed = [chunk for chunk in desired if existing.get((chunk.source, chunk.index)) != chunk.content_hash]
    vectors = [v for batch in batched(changed, 50) for v in llm.embed_documents([(c.title, c.content) for c in batch])]
    stale = existing.keys() - {(chunk.source, chunk.index) for chunk in desired}
    with pool.connection() as conn, conn.transaction():
        for chunk, vector in zip(changed, vectors, strict=True):
            conn.execute(
                """INSERT INTO knowledge_chunks
                       (source, chunk_index, title, content, content_hash, embedding, updated_at)
                   VALUES (%s, %s, %s, %s, %s, %s::vector, now())
                   ON CONFLICT (source, chunk_index) DO UPDATE SET title = EXCLUDED.title, content = EXCLUDED.content,
                       content_hash = EXCLUDED.content_hash, embedding = EXCLUDED.embedding, updated_at = now()""",
                (chunk.source, chunk.index, chunk.title, chunk.content, chunk.content_hash, vector_literal(vector)),
            )
        for source, index in stale:
            conn.execute("DELETE FROM knowledge_chunks WHERE source = %s AND chunk_index = %s", (source, index))
    log.info("Knowledge base synced: %d embedded, %d deleted, %d total", len(changed), len(stale), len(desired))
    return SyncResponse(embedded=len(changed), deleted=len(stale), total=len(desired))


def search_knowledge(pool: ConnectionPool, query: str, limit: int) -> list[KnowledgeHit]:
    vector = vector_literal(llm.embed_query(query))
    with pool.connection() as conn:
        rows = conn.execute(
            """SELECT source, title, content, 1 - (embedding <=> %(v)s::vector) FROM knowledge_chunks
               ORDER BY embedding <=> %(v)s::vector LIMIT %(limit)s""",
            {"v": vector, "limit": limit},
        ).fetchall()
    return [KnowledgeHit(source=r[0], title=r[1], content=r[2], score=r[3]) for r in rows if r[3] >= SEARCH_MIN_SCORE]


def search_complaints(
    pool: ConnectionPool, query: str, citizen_id: int | None, officer_id: int | None, limit: int
) -> list[ComplaintHit]:
    """Meaning-based complaint search within the scope the backend grants (citizen's own, officer's assigned)."""
    vector = vector_literal(llm.embed_query(query))
    with pool.connection() as conn, conn.transaction():
        # The HNSW index returns its nearest rows before the scope filter applies; keep scanning until enough of
        # this user's complaints are found (pgvector 0.8+), or a citizen could see nothing at scale.
        conn.execute("SET LOCAL hnsw.iterative_scan = strict_order")
        rows = conn.execute(
            """SELECT id, 1 - (embedding <=> %(v)s::vector) FROM complaints
               WHERE embedding IS NOT NULL
                 AND (%(citizen)s::bigint IS NULL OR citizen_id = %(citizen)s)
                 AND (%(officer)s::bigint IS NULL OR assigned_officer_id = %(officer)s)
               ORDER BY embedding <=> %(v)s::vector LIMIT %(limit)s""",
            {"v": vector, "citizen": citizen_id, "officer": officer_id, "limit": limit},
        ).fetchall()
    return [ComplaintHit(complaint_id=r[0], score=r[1]) for r in rows if r[1] >= SEARCH_MIN_SCORE]


def ask(pool: ConnectionPool, question: str) -> AskResponse:
    """RAG: retrieve knowledge-base passages first; answer only from them, or say the topic is not covered."""
    hits = [hit for hit in search_knowledge(pool, question, 4) if hit.score >= ANSWER_MIN_SCORE]
    if not hits:
        return AskResponse(answer=NOT_COVERED, grounded=False, sources=[], model=None)
    passages = "\n\n".join(f"[{number}] {hit.title}\n{hit.content}" for number, hit in enumerate(hits, 1))
    result, model = llm.generate(
        ASSISTANT_SYSTEM,
        ASSISTANT_PROMPT.format(passages=passages, question=question),
        ASSISTANT_SCHEMA,
        _Answer.model_validate,
    )
    cited = [hits[number - 1] for number in dict.fromkeys(result.cited) if 1 <= number <= len(hits)] or hits
    sources = list({(hit.title, hit.source): Source(title=hit.title, source=hit.source) for hit in cited}.values())
    return AskResponse(answer=result.answer.strip(), grounded=True, sources=sources, model=model)
