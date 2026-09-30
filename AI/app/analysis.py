"""Complaint analysis (image recognition, classification, priority, department, summary, embedding) and the
complaint writing assistant."""

import logging
from urllib.parse import urlsplit

import httpx
from pydantic import BaseModel, Field

from app import llm
from app.prompts import (
    TRIAGE_PROMPT,
    TRIAGE_SYSTEM,
    VISION_PROMPT,
    VISION_SCHEMA,
    VISION_SYSTEM,
    WRITE_PROMPT,
    WRITE_SCHEMA,
    WRITE_SYSTEM,
    triage_schema,
)
from app.schemas import AnalyzeRequest, AnalyzeResponse, Priority, WriteRequest, WriteResponse

log = logging.getLogger(__name__)

MAX_IMAGE_BYTES = 5 * 1024 * 1024
IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}
B2_HOST_SUFFIX = ".backblazeb2.com"


class _Vision(BaseModel):
    problem_detected: bool
    findings: str = Field(min_length=3, max_length=1200)


class _Triage(BaseModel):
    category_id: str
    department_id: str
    priority: Priority
    summary: str = Field(min_length=10, max_length=600)


class _Written(BaseModel):
    title: str = Field(min_length=3, max_length=150)
    description: str = Field(min_length=10, max_length=5000)
    missing_details: list[str] = Field(max_length=5)


def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    image_status, findings, vision_model = "NONE", None, None
    if request.image_url:
        try:
            vision, vision_model = llm.generate(
                VISION_SYSTEM,
                VISION_PROMPT.format(title=request.title),
                VISION_SCHEMA,
                _Vision.model_validate,
                image=fetch_image(request.image_url),
            )
            findings = (
                vision.findings if vision.problem_detected else f"No civic problem clearly visible. {vision.findings}"
            )
            image_status = "ANALYZED"
        except (llm.AIUnavailable, httpx.HTTPError, ValueError) as ex:
            # The complaint text alone still gets classified; the missing image input is recorded.
            log.warning("Image recognition failed: %s", type(ex).__name__)
            image_status = "FAILED"

    category_ids = {str(category.id) for category in request.categories}
    department_ids = {str(department.id) for department in request.departments}

    def parse(data: dict) -> _Triage:
        triage = _Triage.model_validate(data)
        if triage.category_id not in category_ids or triage.department_id not in department_ids:
            raise ValueError("model chose an id that is not offered")
        return triage

    triage, model = llm.generate(
        TRIAGE_SYSTEM,
        _triage_prompt(request, findings, image_status),
        triage_schema(sorted(category_ids), sorted(department_ids)),
        parse,
    )
    embedding = llm.embed_documents([(request.title, f"{request.description}\nLocation: {request.location}")])[0]
    return AnalyzeResponse(
        category_id=int(triage.category_id),
        department_id=int(triage.department_id),
        priority=triage.priority,
        summary=triage.summary.strip(),
        image_status=image_status,
        image_findings=findings,
        embedding=embedding,
        model=model if vision_model is None else f"{model}; vision {vision_model}",
    )


def improve(request: WriteRequest) -> WriteResponse:
    prompt = WRITE_PROMPT.format(
        title=request.title.strip() or "(none)",
        description=request.description.strip(),
        location=request.location.strip() or "(not given)",
    )
    written, model = llm.generate(WRITE_SYSTEM, prompt, WRITE_SCHEMA, _Written.model_validate)
    return WriteResponse(
        title=written.title.strip(),
        description=written.description.strip(),
        missing_details=[detail.strip() for detail in written.missing_details[:3] if detail.strip()],
        model=model,
    )


def fetch_image(url: str) -> tuple[bytes, str]:
    """Downloads a complaint image from a B2 signed URL. Only B2 over HTTPS is allowed (no SSRF)."""
    parts = urlsplit(url)
    if parts.scheme != "https" or not (parts.hostname or "").endswith(B2_HOST_SUFFIX):
        raise ValueError("image URL is not a Backblaze B2 URL")
    with httpx.stream("GET", url, timeout=20, follow_redirects=False) as response:
        response.raise_for_status()
        content_type = response.headers.get("content-type", "").split(";")[0].strip()
        if content_type not in IMAGE_TYPES:
            raise ValueError("unsupported image type")
        data = bytearray()
        for chunk in response.iter_bytes():
            data += chunk
            if len(data) > MAX_IMAGE_BYTES:
                raise ValueError("image too large")
    return bytes(data), content_type


def _triage_prompt(request: AnalyzeRequest, findings: str | None, image_status: str) -> str:
    departments = {department.id: department for department in request.departments}
    citizen_category = next((c.name for c in request.categories if c.id == request.category_id), "unknown")
    categories = "\n".join(
        f"{c.id}: {c.name} — {c.description or 'no description'} — "
        f"{departments[c.department_id].name if c.department_id in departments else 'no usual department'}"
        for c in request.categories
    )
    department_lines = "\n".join(f"{d.id}: {d.name} — {d.description or 'no description'}" for d in request.departments)
    image_text = {"NONE": "No image attached.", "FAILED": "An image was attached but could not be analysed."}.get(
        image_status, findings
    )
    return TRIAGE_PROMPT.format(
        title=request.title,
        description=request.description,
        location=request.location,
        citizen_category=citizen_category,
        image_findings=image_text,
        categories=categories,
        departments=department_lines,
    )
