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

## Run it

With Docker, one command builds and starts all three services against the services configured in `.env`:

```bash
docker compose up --build
```

Open http://localhost:8081 (set `FRONTEND_PORT` to use another port). Sign in with `ADMIN_EMAIL` and `ADMIN_PASSWORD`, add officers under **People**, and citizens register themselves.

For development, run each service from its folder. The backend and AI service read the root `.env` themselves:

```bash
cd Backend && mvn spring-boot:run
```

```bash
cd AI && uv run uvicorn app.main:app --port 8000
```

```bash
cd Frontend && npm ci && npm run dev
```

The frontend dev server runs on http://localhost:5173 and proxies `/api` to the backend on port 8080, the same single-origin setup nginx gives the container.

## Test

```bash
cd Backend && mvn verify
```

```bash
cd AI && uv run ruff check && uv run pytest
```

```bash
cd Frontend && npm run lint && npm test
```

Backend integration tests start PostgreSQL with pgvector through Testcontainers, so Docker must be running. No test touches Neon, Backblaze B2, Gemini or Groq.

## Continuous integration

[GitHub Actions](.github/workflows/ci.yml) runs the three test suites on every push and pull request, then builds the three container images. Pushes to `main` publish them to GitHub Container Registry as `ghcr.io/<owner>/fmc-backend`, `fmc-ai` and `fmc-frontend`.
