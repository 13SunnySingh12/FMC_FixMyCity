"""FMC AI service. Called only by the Spring Boot backend, authenticated with a shared internal key."""

import hmac
import logging
import threading
from contextlib import asynccontextmanager
from typing import Annotated

import psycopg
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from psycopg_pool import ConnectionPool

from app import analysis, db, knowledge
from app.config import get_settings
from app.llm import AIUnavailable
from app.schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    AskRequest,
    AskResponse,
    ComplaintHit,
    ComplaintSearchRequest,
    KnowledgeHit,
    KnowledgeSearchRequest,
    SyncResponse,
    WriteRequest,
    WriteResponse,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("fmc.ai")


def _initial_sync(pool: ConnectionPool, knowledge_dir: str) -> None:
    try:
        result = knowledge.sync(pool, knowledge_dir)
        log.info(
            "Knowledge base synced: %d embedded, %d deleted, %d total", result.embedded, result.deleted, result.total
        )
    except (AIUnavailable, psycopg.Error, OSError) as ex:  # startup must not fail on a brief outage
        log.warning(
            "Knowledge base sync failed at startup (%s); it can be re-run via /knowledge/sync", type(ex).__name__
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.pool = db.open_pool(settings.database_url)
    threading.Thread(target=_initial_sync, args=(app.state.pool, settings.knowledge_dir), daemon=True).start()
    yield
    app.state.pool.close()


def require_internal_key(x_internal_key: str = Header(default="")) -> None:
    expected = get_settings().ai_service_api_key
    if not hmac.compare_digest(x_internal_key.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Missing or invalid internal key")


def get_pool(request: Request) -> ConnectionPool:
    return request.app.state.pool


Pool = Annotated[ConnectionPool, Depends(get_pool)]


app = FastAPI(title="FMC AI service", lifespan=lifespan)
api = APIRouter(dependencies=[Depends(require_internal_key)])


@app.exception_handler(AIUnavailable)
def ai_unavailable(request: Request, ex: AIUnavailable) -> JSONResponse:
    return JSONResponse(status_code=503, content={"detail": "AI providers are temporarily unavailable"})


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@api.post("/assist/write", response_model=WriteResponse)
def write(request: WriteRequest) -> WriteResponse:
    return analysis.improve(request)


@api.post("/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    return analysis.analyze(request)


@api.post("/search/complaints", response_model=list[ComplaintHit])
def search_complaints(request: ComplaintSearchRequest, pool: Pool) -> list[ComplaintHit]:
    return knowledge.search_complaints(pool, request.query, request.citizen_id, request.officer_id, request.limit)


@api.post("/search/knowledge", response_model=list[KnowledgeHit])
def search_knowledge(request: KnowledgeSearchRequest, pool: Pool) -> list[KnowledgeHit]:
    return knowledge.search_knowledge(pool, request.query, request.limit)


@api.post("/assistant/ask", response_model=AskResponse)
def ask(request: AskRequest, pool: Pool) -> AskResponse:
    return knowledge.ask(pool, request.question)


@api.post("/knowledge/sync", response_model=SyncResponse)
def sync_knowledge(pool: Pool) -> SyncResponse:
    return knowledge.sync(pool, get_settings().knowledge_dir)


app.include_router(api)
