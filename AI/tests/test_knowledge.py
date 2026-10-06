import psycopg
import pytest

from app import db, knowledge, llm
from app.config import get_settings
from app.db import vector_literal
from tests.conftest import fake_vector

DOC = """# Complaint statuses

## What "Resolved" means

Resolved means the officer marked the problem as fixed after adding notes and proof.

## What "Closed" means

Closed is final. A complaint closes when the citizen gives feedback.
"""


@pytest.fixture
def kb(tmp_path, client, database_url, monkeypatch):
    """A clean knowledge base built from one document plus the live department directory."""
    with psycopg.connect(database_url, autocommit=True) as conn:
        conn.execute("TRUNCATE knowledge_chunks")
    (tmp_path / "statuses.md").write_text(DOC, encoding="utf-8")
    monkeypatch.setenv("KNOWLEDGE_DIR", str(tmp_path))
    get_settings.cache_clear()
    # The production thresholds are calibrated for real embeddings, not this bag-of-words fake.
    monkeypatch.setattr(knowledge, "ANSWER_MIN_SCORE", 0.3)
    monkeypatch.setattr(knowledge, "SEARCH_MIN_SCORE", 0.3)
    return tmp_path


def test_markdown_is_chunked_per_section_with_document_titles():
    chunks = knowledge.markdown_chunks("statuses.md", DOC)

    assert [c.title for c in chunks] == [
        'Complaint statuses: What "Resolved" means',
        'Complaint statuses: What "Closed" means',
    ]
    assert [c.index for c in chunks] == [0, 1]
    assert "notes and proof" in chunks[0].content


def test_long_sections_are_split_on_paragraph_boundaries():
    paragraphs = "\n\n".join(f"Paragraph {i} " + "word " * 60 for i in range(10))
    chunks = knowledge.markdown_chunks("long.md", f"# Long\n\n## Section\n\n{paragraphs}")

    assert len(chunks) > 1
    assert all(len(c.content) <= knowledge.MAX_CHUNK_CHARS for c in chunks)


def test_sync_embeds_only_new_or_changed_chunks_and_removes_stale_ones(kb, client, embeddings):
    first = client.post("/knowledge/sync").json()
    assert first["embedded"] == first["total"] > 2  # document sections + live department directory

    embeddings.clear()
    assert client.post("/knowledge/sync").json()["embedded"] == 0 and embeddings == []

    (kb / "statuses.md").write_text(DOC.replace("Closed is final.", "Closed is the final status."), encoding="utf-8")
    assert client.post("/knowledge/sync").json()["embedded"] == 1

    (kb / "statuses.md").unlink()
    assert client.post("/knowledge/sync").json()["deleted"] == 2


def test_assistant_answers_from_retrieved_passages_with_sources(kb, client, monkeypatch):
    client.post("/knowledge/sync")
    seen = {}

    def fake_generate(system, prompt, schema, parse, image=None):
        seen["prompt"] = prompt
        return parse(
            {"answer": "The Street Lighting & Electrical department handles them.", "cited": [1, 7]}
        ), "fake:model"

    monkeypatch.setattr(llm, "generate", fake_generate)
    body = client.post("/assistant/ask", json={"question": "Which department handles streetlights complaints?"}).json()

    assert body["grounded"] is True
    assert "[1] Which department handles Streetlights complaints?" in seen["prompt"]
    assert body["sources"] == [
        {"title": "Which department handles Streetlights complaints?", "source": "department-directory"}
    ]


def test_assistant_says_so_when_nothing_relevant_is_retrieved(kb, client, monkeypatch):
    client.post("/knowledge/sync")

    def must_not_be_called(*args, **kwargs):
        raise AssertionError("the model must not be asked without retrieved context")

    monkeypatch.setattr(llm, "generate", must_not_be_called)
    body = client.post("/assistant/ask", json={"question": "Best pizza toppings?"}).json()

    assert body == {"answer": knowledge.NOT_COVERED, "grounded": False, "sources": [], "model": None}


def _user(conn, role, email):
    department = "(SELECT min(id) FROM departments)" if role == "OFFICER" else "NULL"
    return conn.execute(
        f"INSERT INTO users (name, email, password_hash, role, department_id) VALUES "
        f"('T', %s, 'x', %s, {department}) RETURNING id",
        (email, role),
    ).fetchone()[0]


def _complaint(conn, citizen, text, assigned=None):
    return conn.execute(
        """INSERT INTO complaints (citizen_id, request_id, title, description, location, category_id, status,
               assigned_officer_id, embedding) VALUES (%s, gen_random_uuid(), 'T', %s, 'L',
               (SELECT min(id) FROM categories), %s, %s, %s::vector) RETURNING id""",
        (citizen, text, "ASSIGNED" if assigned else "SUBMITTED", assigned, vector_literal(fake_vector(text))),
    ).fetchone()[0]


def test_complaint_search_respects_the_scope_given_by_the_backend(client, database_url, monkeypatch):
    monkeypatch.setattr(knowledge, "SEARCH_MIN_SCORE", 0.3)
    with psycopg.connect(database_url, autocommit=True) as conn:
        alice, bob, officer = (
            _user(conn, "CITIZEN", "alice@test.local"),
            _user(conn, "CITIZEN", "bob@test.local"),
            _user(conn, "OFFICER", "off@test.local"),
        )
        alices = _complaint(conn, alice, "dark street broken streetlight lamp", assigned=officer)
        bobs = _complaint(conn, bob, "dark street broken streetlight lamp near park")

    def ids(**scope):
        response = client.post("/search/complaints", json={"query": "broken streetlight lamp dark street", **scope})
        return {hit["complaintId"] for hit in response.json()}

    assert ids(citizenId=alice) == {alices}
    assert ids(officerId=officer) == {alices}
    assert {alices, bobs} <= ids()


def test_scoped_search_finds_matches_the_index_would_crowd_out(database_url, embeddings, monkeypatch):
    """On a large table the planner walks the HNSW index, which returns its ~40 nearest rows before the scope
    filter applies; seqscan and sort are disabled here to force that plan on a small table."""
    monkeypatch.setattr(knowledge, "SEARCH_MIN_SCORE", 0.3)
    with psycopg.connect(database_url, autocommit=True) as conn:
        crowd, target = _user(conn, "CITIZEN", "crowd@test.local"), _user(conn, "CITIZEN", "target@test.local")
        for i in range(120):
            # A distinct letters-only tag per row keeps the vectors near the query without making them identical.
            tag = "".join(chr(ord("a") + int(digit)) for digit in str(i))
            _complaint(conn, crowd, f"broken streetlight lamp dark street {tag}")
        mine = _complaint(conn, target, "broken streetlight lamp near the park gate at night")

    separator = "&" if "?" in database_url else "?"
    pool = db.open_pool(f"{database_url}{separator}options=-c%20enable_seqscan%3Doff%20-c%20enable_sort%3Doff")
    try:
        hits = knowledge.search_complaints(pool, "broken streetlight lamp dark street", target, None, 5)
    finally:
        pool.close()

    assert [hit.complaint_id for hit in hits] == [mine]
