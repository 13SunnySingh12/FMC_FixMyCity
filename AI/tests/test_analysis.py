import json

import httpx
import pytest

from app import analysis, llm
from app.schemas import AnalyzeRequest

CATEGORIES = [
    {"id": 1, "name": "Roads", "description": "Potholes", "departmentId": 10},
    {"id": 3, "name": "Streetlights", "description": "Broken lights", "departmentId": 30},
]
DEPARTMENTS = [
    {"id": 10, "name": "Roads & Public Works"},
    {"id": 30, "name": "Street Lighting & Electrical"},
]


def request(**overrides) -> dict:
    return {
        "title": "Streetlight not working",
        "description": "The streetlight outside the school has been off for a week.",
        "location": "School Lane",
        "categoryId": 1,
        "categories": CATEGORIES,
        "departments": DEPARTMENTS,
        **overrides,
    }


def triage_json(category="3", department="30", priority="MEDIUM") -> str:
    return json.dumps(
        {
            "category_id": category,
            "department_id": department,
            "priority": priority,
            "summary": "Streetlight outside the school on School Lane has been off for a week.",
        }
    )


def test_analysis_returns_validated_suggestions_and_embedding(client, monkeypatch):
    prompts = []

    def fake_generate(system, prompt, schema, parse, image=None):
        prompts.append(prompt)
        assert schema["properties"]["category_id"]["enum"] == ["1", "3"]
        return parse(json.loads(triage_json(priority="HIGH"))), "fake:model"

    monkeypatch.setattr(llm, "generate", fake_generate)
    body = client.post("/analyze", json=request()).json()

    assert (body["categoryId"], body["departmentId"], body["priority"]) == (3, 30, "HIGH")
    assert body["imageStatus"] == "NONE" and body["imageFindings"] is None
    assert len(body["embedding"]) == llm.EMBEDDING_DIMENSIONS
    assert "No image attached." in prompts[0] and "Category chosen by the citizen: Roads" in prompts[0]


def test_invalid_model_output_falls_through_to_the_next_provider(monkeypatch, embeddings):
    monkeypatch.setattr(llm, "_groq_json", lambda *a: triage_json(category="999"))  # id that was not offered
    monkeypatch.setattr(llm, "_gemini_json", lambda *a: triage_json())

    result = analysis.analyze(AnalyzeRequest.model_validate(request()))

    assert result.category_id == 3
    assert result.model.startswith("gemini:")


def test_an_empty_model_response_falls_through_to_the_next_provider(monkeypatch, embeddings):
    class EmptyGroq:
        """A client whose completion carries no choices, as providers return when they filter an answer."""

        class chat:
            class completions:
                @staticmethod
                def create(**kwargs):
                    return type("Completion", (), {"choices": []})()

    monkeypatch.setattr(llm, "_groq", lambda: EmptyGroq)
    monkeypatch.setattr(llm, "_gemini_json", lambda *a: triage_json())

    result = analysis.analyze(AnalyzeRequest.model_validate(request()))

    assert result.model.startswith("gemini:")


def test_provider_errors_and_bad_json_are_skipped_until_all_fail(monkeypatch, embeddings):
    def connection_error(*args):
        raise httpx.ConnectError("unreachable")

    monkeypatch.setattr(llm, "_groq_json", connection_error)
    monkeypatch.setattr(llm, "_gemini_json", lambda *a: "not json")

    with pytest.raises(llm.AIUnavailable):
        analysis.analyze(AnalyzeRequest.model_validate(request()))


def test_image_problems_do_not_block_text_classification(monkeypatch, embeddings):
    monkeypatch.setattr(llm, "_groq_json", lambda *a: triage_json())

    result = analysis.analyze(AnalyzeRequest.model_validate(request(imageUrl="https://example.com/photo.jpg")))

    assert result.image_status == "FAILED"
    assert result.category_id == 3


@pytest.mark.parametrize(
    "url",
    [
        "http://169.254.169.254/latest/meta-data",
        "https://evil.example/x.jpg",
        "https://backblazeb2.com.evil.example/x.jpg",
        "file:///etc/passwd",
    ],
)
def test_image_fetch_only_allows_b2_over_https(url):
    with pytest.raises(ValueError):
        analysis.fetch_image(url)
