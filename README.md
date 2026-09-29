# FMC — FixMyCity

AI-powered civic complaint management: citizens report local problems with a photo and location, officers resolve them with notes and proof, and admins route and monitor everything. AI helps write complaints, reads the photo, suggests category, priority and department, summarises for officers, and answers civic questions from the project's own knowledge base.

The full product specification is in [FMC.md](FMC.md). Verified progress against it is tracked in [Docs/PROGRESS.md](Docs/PROGRESS.md).

> Status: under active development. Only what is marked `[✓]` in the progress file is implemented and verified.

## Architecture

```text
React (JavaScript) ──REST──> Spring Boot (Java 21) ──REST──> FastAPI (Python)
                               │  security, rules, files      │  Gemini: vision, embeddings
                               │                              │  Groq: text generation
                               ▼                              ▼
                    Neon PostgreSQL + pgvector (one database: records and vectors)
                               │
                    Backblaze B2 private bucket (images, via short-lived signed URLs)
```

## Configuration

All services read one `.env` at the repository root. Copy [.env.example](.env.example) to `.env` and fill it in; `.env` is gitignored and must never be committed.

| Variable | Used by | Source |
|---|---|---|
| `DATABASE_URL` | backend, AI service | Neon console → Connect (direct connection string) |
| `JWT_SECRET` | backend | Random, 48+ bytes |
| `AI_SERVICE_API_KEY` | backend, AI service | Random shared secret |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | backend | First admin account, created on first start |
| `B2_ENDPOINT`, `B2_BUCKET`, `B2_KEY_ID`, `B2_APPLICATION_KEY` | backend | Backblaze B2 private bucket and application key |
| `GEMINI_API_KEY` | AI service | Google AI Studio |
| `GROQ_API_KEY` | AI service | Groq console |
