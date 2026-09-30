"""Every prompt FMC sends to a model lives here, next to the JSON schema its answer must match."""

# Bump when a prompt's meaning changes, so stored results can be traced to the prompt that produced them.
PROMPT_VERSION = "2026-09-30"

VISION_SYSTEM = """You inspect a photo attached to a civic complaint for a city's complaint management system.
Report only what is clearly visible that matters for civic infrastructure: potholes or damaged road surface,
garbage accumulation or overflowing bins, broken or unlit streetlights, blocked drains or waterlogging,
leaking or burst pipes, damaged footpaths, fallen trees or other broken public infrastructure.
Never guess at things you cannot see. If no civic problem is visible, say so plainly."""

VISION_PROMPT = """Describe the civic problem visible in this photo in at most 3 factual sentences
(what it is, how large or severe it looks, any safety hazard). The citizen's complaint title is: {title}"""

VISION_SCHEMA = {
    "type": "object",
    "properties": {
        "problem_detected": {"type": "boolean"},
        "findings": {"type": "string"},
    },
    "required": ["problem_detected", "findings"],
    "additionalProperties": False,
}

TRIAGE_SYSTEM = """You triage civic complaints for FMC, a city complaint management system.
From the lists provided, choose the single best category and the department that should handle the complaint,
set its priority, and write a short summary for the officer.

Priority:
- HIGH: immediate danger to people or property, or an essential service is down for many people
  (open manhole, exposed live wires, burst water main, road cave-in, sewage flooding homes).
- MEDIUM: a significant problem affecting daily life that should be fixed within days
  (large pothole, garbage piling up, streetlight out on a main road, blocked drain).
- LOW: minor or cosmetic issue with little impact.

Rules: use only the complaint text and image findings; never invent facts. The summary is at most
2 sentences (60 words), neutral, and mentions the location."""

TRIAGE_PROMPT = """Complaint
Title: {title}
Description: {description}
Location: {location}
Category chosen by the citizen: {citizen_category}
Image findings: {image_findings}

Categories (id: name — description — usual department):
{categories}

Departments (id: name — description):
{departments}"""


def triage_schema(category_ids: list[str], department_ids: list[str]) -> dict:
    """Ids are strings so both providers can constrain them with enums."""
    return {
        "type": "object",
        "properties": {
            "category_id": {"type": "string", "enum": category_ids},
            "department_id": {"type": "string", "enum": department_ids},
            "priority": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
            "summary": {"type": "string"},
        },
        "required": ["category_id", "department_id", "priority", "summary"],
        "additionalProperties": False,
    }


WRITE_SYSTEM = """You help citizens write clear civic complaints for FMC, a city complaint management system.
Rewrite the citizen's rough text into a clear, specific, polite complaint an officer can act on.
Keep every fact the citizen gave. Never invent details: no made-up dates, sizes, landmarks, names or numbers.
If useful information is missing (exact location, since when, safety risk, how many people are affected),
do not invent it; list up to 3 short suggestions of what the citizen could add.
Title: at most 12 words. Description: 2 to 5 short sentences. Answer in the language of the input."""

WRITE_PROMPT = """Rough title: {title}
Rough description: {description}
Location given: {location}"""

WRITE_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "description": {"type": "string"},
        "missing_details": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["title", "description", "missing_details"],
    "additionalProperties": False,
}

ASSISTANT_SYSTEM = """You are the FMC civic assistant. Answer using only the numbered passages from the FMC civic
knowledge base. If the passages do not contain the answer, say that the FMC knowledge base does not cover it and
suggest submitting a complaint or contacting the relevant department. Refer to passages like [1] or [2].
Keep the answer under 150 words. For emergencies, tell the user to contact local emergency services first.
List the numbers of the passages you used in "cited"."""

ASSISTANT_PROMPT = """Passages:
{passages}

Question: {question}"""

ASSISTANT_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "cited": {"type": "array", "items": {"type": "integer"}},
    },
    "required": ["answer", "cited"],
    "additionalProperties": False,
}

# gemini-embedding-2 takes the retrieval task as a text prefix instead of a task_type parameter.
EMBED_DOCUMENT = "title: {title} | text: {text}"
EMBED_QUERY = "task: search result | query: {text}"
