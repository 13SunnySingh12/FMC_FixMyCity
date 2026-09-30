"""Request and response models. JSON uses camelCase to match the Spring backend."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

Priority = Literal["LOW", "MEDIUM", "HIGH"]


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class CategoryIn(ApiModel):
    id: int
    name: str = Field(max_length=80)
    description: str | None = Field(default=None, max_length=500)
    department_id: int | None = None


class DepartmentIn(ApiModel):
    id: int
    name: str = Field(max_length=120)
    description: str | None = Field(default=None, max_length=500)


class AnalyzeRequest(ApiModel):
    title: str = Field(min_length=1, max_length=150)
    description: str = Field(min_length=1, max_length=5000)
    location: str = Field(min_length=1, max_length=300)
    category_id: int
    image_url: str | None = Field(default=None, max_length=4000)
    categories: list[CategoryIn] = Field(min_length=1, max_length=100)
    departments: list[DepartmentIn] = Field(min_length=1, max_length=100)


class AnalyzeResponse(ApiModel):
    category_id: int
    department_id: int
    priority: Priority
    summary: str
    image_status: Literal["NONE", "ANALYZED", "FAILED"]
    image_findings: str | None
    embedding: list[float]
    model: str


class WriteRequest(ApiModel):
    title: str = Field(default="", max_length=150)
    description: str = Field(min_length=10, max_length=5000)
    location: str = Field(default="", max_length=300)


class WriteResponse(ApiModel):
    title: str
    description: str
    missing_details: list[str]
    model: str


class ComplaintSearchRequest(ApiModel):
    query: str = Field(min_length=2, max_length=300)
    citizen_id: int | None = None
    officer_id: int | None = None
    limit: int = Field(default=10, ge=1, le=50)


class ComplaintHit(ApiModel):
    complaint_id: int
    score: float


class KnowledgeSearchRequest(ApiModel):
    query: str = Field(min_length=2, max_length=300)
    limit: int = Field(default=5, ge=1, le=10)


class KnowledgeHit(ApiModel):
    source: str
    title: str
    content: str
    score: float


class AskRequest(ApiModel):
    question: str = Field(min_length=3, max_length=500)


class Source(ApiModel):
    title: str
    source: str


class AskResponse(ApiModel):
    answer: str
    grounded: bool
    sources: list[Source]
    model: str | None


class SyncResponse(ApiModel):
    embedded: int
    deleted: int
    total: int
