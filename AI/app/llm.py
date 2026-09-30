"""Model providers, chosen per task.

Groq writes text (fast), with Gemini as fallback. Gemini does vision and embeddings, which Groq does not offer.
Embeddings never fall back to another model: vectors from different models are not comparable.
"""

import json
import logging
from collections.abc import Callable
from functools import lru_cache

import groq
import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

from app.config import get_settings
from app.prompts import EMBED_DOCUMENT, EMBED_QUERY

log = logging.getLogger(__name__)

EMBEDDING_DIMENSIONS = 768  # matches the vector(768) columns
TIMEOUT_SECONDS = 30
PROVIDER_ERRORS = (genai_errors.APIError, groq.APIError, httpx.HTTPError)


class AIUnavailable(Exception):
    """No provider produced a usable answer."""


@lru_cache
def _gemini() -> genai.Client:
    settings = get_settings()
    return genai.Client(api_key=settings.gemini_api_key, http_options=types.HttpOptions(timeout=TIMEOUT_SECONDS * 1000))


@lru_cache
def _groq() -> groq.Groq:
    return groq.Groq(api_key=get_settings().groq_api_key, timeout=TIMEOUT_SECONDS, max_retries=1)


def generate[T](
    system: str,
    prompt: str,
    schema: dict,
    parse: Callable[[dict], T],
    image: tuple[bytes, str] | None = None,
) -> tuple[T, str]:
    """Structured output from the first provider whose answer `parse` accepts.

    `parse` raises ValueError (pydantic's ValidationError included) for output the application cannot use,
    and the next provider is tried. Returns the parsed value and the "provider:model" that produced it.
    """
    settings = get_settings()
    candidates = [] if image else [("groq", settings.groq_model)]
    candidates += [("gemini", model) for model in settings.gemini_model_list]
    for provider, model in candidates:
        try:
            if provider == "groq":
                raw = _groq_json(model, system, prompt, schema)
            else:
                raw = _gemini_json(model, system, prompt, schema, image)
            return parse(json.loads(raw)), f"{provider}:{model}"
        except ValueError as ex:
            log.warning("Unusable output from %s:%s: %s", provider, model, type(ex).__name__)
        except PROVIDER_ERRORS as ex:
            log.warning("%s:%s failed: %s", provider, model, _describe(ex))
    raise AIUnavailable("No AI provider produced a usable response")


def _groq_json(model: str, system: str, prompt: str, schema: dict) -> str:
    response = _groq().chat.completions.create(
        model=model,
        messages=[{"role": "system", "content": system}, {"role": "user", "content": prompt}],
        response_format={"type": "json_schema", "json_schema": {"name": "result", "strict": True, "schema": schema}},
        reasoning_effort="low",
        temperature=0.2,
        max_completion_tokens=2000,
    )
    return response.choices[0].message.content or ""


def _gemini_json(model: str, system: str, prompt: str, schema: dict, image: tuple[bytes, str] | None) -> str:
    contents = [types.Part.from_bytes(data=image[0], mime_type=image[1]), prompt] if image else prompt
    response = _gemini().models.generate_content(
        model=model,
        contents=contents,
        config=types.GenerateContentConfig(
            system_instruction=system,
            response_mime_type="application/json",
            response_json_schema=schema,
            temperature=0.2,
            max_output_tokens=2000,
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        ),
    )
    return response.text or ""


def embed_documents(items: list[tuple[str, str]]) -> list[list[float]]:
    """Embeds (title, text) pairs for storage."""
    return _embed([EMBED_DOCUMENT.format(title=title, text=text) for title, text in items])


def embed_query(text: str) -> list[float]:
    return _embed([EMBED_QUERY.format(text=text)])[0]


def _embed(texts: list[str]) -> list[list[float]]:
    try:
        response = _gemini().models.embed_content(
            model=get_settings().gemini_embedding_model,
            # One Content per text: a single multi-part content would be embedded as one combined input.
            contents=[types.Content(parts=[types.Part(text=text)]) for text in texts],
            config=types.EmbedContentConfig(output_dimensionality=EMBEDDING_DIMENSIONS),
        )
    except PROVIDER_ERRORS as ex:
        raise AIUnavailable(f"Embedding failed: {_describe(ex)}") from ex
    vectors = [embedding.values or [] for embedding in response.embeddings or []]
    if len(vectors) != len(texts) or any(len(vector) != EMBEDDING_DIMENSIONS for vector in vectors):
        raise AIUnavailable("Embedding response had an unexpected shape")
    return vectors


def _describe(ex: Exception) -> str:
    """Error summary for logs: status only, never request content or keys."""
    for attribute in ("code", "status_code"):
        if isinstance(getattr(ex, attribute, None), int):
            return f"{type(ex).__name__} {getattr(ex, attribute)}"
    return type(ex).__name__
