import psycopg
import pytest
from pydantic import ValidationError

from app import analysis, knowledge, llm
from app.config import get_settings


def write_request():
    return {
        "title": "",
        "description": "road has big hole since monsoon, cars getting damaged",
        "location": "Station Road",
    }


def test_health_is_open(client):
    assert client.get("/health", headers={"X-Internal-Key": ""}).json() == {"status": "ok"}


def test_api_docs_are_not_served(client):
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404, path


def test_service_refuses_to_start_with_a_short_internal_key(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_API_KEY", "")
    get_settings.cache_clear()
    with pytest.raises(ValidationError):
        get_settings()
    get_settings.cache_clear()


def test_every_ai_endpoint_requires_the_internal_key(client):
    for path in (
        "/assist/write",
        "/analyze",
        "/search/complaints",
        "/search/knowledge",
        "/assistant/ask",
        "/knowledge/sync",
    ):
        assert client.post(path, json={}, headers={"X-Internal-Key": "wrong"}).status_code == 401, path


def test_writing_assistant_returns_validated_suggestion(client, monkeypatch):
    def fake_generate(system, prompt, schema, parse, image=None):
        assert "Station Road" in prompt
        return parse(
            {
                "title": "Large pothole on Station Road",
                "description": "A deep pothole has formed.",
                "missing_details": ["Since when?", "Exact spot?", "Any accidents?", "Extra"],
            }
        ), "fake:model"

    monkeypatch.setattr(llm, "generate", fake_generate)
    body = client.post("/assist/write", json=write_request()).json()

    assert body["title"] == "Large pothole on Station Road"
    assert body["missingDetails"] == ["Since when?", "Exact spot?", "Any accidents?"]
    assert body["model"] == "fake:model"


def test_provider_outage_is_reported_as_503(client, monkeypatch):
    def unavailable(*args, **kwargs):
        raise llm.AIUnavailable("down")

    monkeypatch.setattr(analysis.llm, "generate", unavailable)
    assert client.post("/assist/write", json=write_request()).status_code == 503


def test_database_outage_is_reported_as_503(client, monkeypatch):
    def unreachable(*args, **kwargs):
        raise psycopg.OperationalError("connection refused")

    monkeypatch.setattr(knowledge, "search_knowledge", unreachable)
    response = client.post("/search/knowledge", json={"query": "streetlight"})

    assert response.status_code == 503
    assert response.json() == {"detail": "The database is temporarily unavailable"}


def test_a_connection_the_server_closed_is_replaced_instead_of_failing_the_request(client, database_url):
    assert client.post("/search/knowledge", json={"query": "streetlight"}).status_code == 200
    with psycopg.connect(database_url, autocommit=True) as conn:  # what a database restart does to the pool
        conn.execute(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
            " WHERE pid <> pg_backend_pid() AND backend_type = 'client backend'"
        )

    assert client.post("/search/knowledge", json={"query": "streetlight"}).status_code == 200


def test_input_limits_are_enforced_before_any_model_call(client):
    assert client.post("/assist/write", json={"description": "short"}).status_code == 422
    assert client.post("/assistant/ask", json={"question": "x" * 501}).status_code == 422
