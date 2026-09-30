"""MOCK/TEST INTEGRATION: model providers are replaced by deterministic fakes. PostgreSQL + pgvector runs in a
container with the backend's real Flyway migrations. Real providers are exercised by the live checks instead."""

import hashlib
import math
import os
import re
from pathlib import Path

# Before any app import: test values override the repository .env, so tests never call real providers.
os.environ.update(
    {
        "AI_SERVICE_API_KEY": "test-internal-key",
        "GEMINI_API_KEY": "test-not-a-real-key",
        "GROQ_API_KEY": "test-not-a-real-key",
        "DATABASE_URL": "postgresql://unused@localhost:1/unused",  # replaced by the container URL where needed
    }
)

import psycopg
import pytest
from fastapi.testclient import TestClient
from testcontainers.community.postgres import PostgresContainer

from app import llm, main
from app.config import get_settings

MIGRATIONS = Path(__file__).resolve().parents[2] / "Backend" / "src" / "main" / "resources" / "db" / "migration"
INTERNAL_KEY = {"X-Internal-Key": "test-internal-key"}


def fake_vector(text: str) -> list[float]:
    """Bag-of-words vector: texts sharing words point in similar directions."""
    vector = [0.0] * llm.EMBEDDING_DIMENSIONS
    for word in re.findall(r"[a-z]+", text.lower()):
        vector[int(hashlib.md5(word.encode()).hexdigest(), 16) % llm.EMBEDDING_DIMENSIONS] += 1.0
    norm = math.sqrt(sum(value * value for value in vector)) or 1.0
    return [value / norm for value in vector]


@pytest.fixture(scope="session")
def database_url():
    with PostgresContainer("pgvector/pgvector:pg18", driver=None) as postgres:
        url = postgres.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            for migration in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
                conn.execute(migration.read_text(encoding="utf-8"))
        yield url


@pytest.fixture
def embeddings(monkeypatch):
    """Fake embedder that records how many texts it embedded."""
    calls = []

    def embed_documents(items):
        calls.extend(items)
        return [fake_vector(f"{title} {text}") for title, text in items]

    monkeypatch.setattr(llm, "embed_documents", embed_documents)
    monkeypatch.setattr(llm, "embed_query", fake_vector)
    return calls


@pytest.fixture
def client(database_url, embeddings, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", database_url)
    get_settings.cache_clear()
    monkeypatch.setattr(main, "_initial_sync", lambda pool, knowledge_dir: None)
    with TestClient(main.app, headers=INTERNAL_KEY) as test_client:
        yield test_client
    get_settings.cache_clear()
