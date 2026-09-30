# FMC — FixMyCity

AI-powered civic complaint management: citizens report local problems with a photo and location, officers resolve them with notes and proof, and admins route and monitor everything. AI helps write complaints, reads the photo, suggests category, priority and department, summarises for officers, and answers civic questions from the project's own knowledge base.

The full product specification is in [FMC.md](FMC.md). How each requirement was implemented and verified is recorded in [Docs/PROGRESS.md](Docs/PROGRESS.md).

> Status: every requirement in FMC.md is implemented and verified; the progress file records how each one was checked.

## Features

- **Citizens** register, report a problem (title, description, category, location, optional photo), improve the wording with AI, follow each complaint along its route (Submitted, Assigned, In progress, Resolved, Closed), confirm the fix with a 1–5 rating or reopen it.
- **Officers** work a queue of the complaints assigned to them, add investigation notes and resolution-proof photos, and resolve only once both exist for the current round of work; they can hand a complaint to a colleague or back to a department.
- **Admins** assign complaints, edit category and priority, close resolved complaints, manage citizens, officers, departments and categories, and see totals by status, category, priority and department.
- **AI** analyses every complaint in the background (category, priority, department, summary, photo findings), powers semantic search over complaints and civic guidance, and answers questions through a retrieval-augmented assistant that cites its sources.

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

| Folder | Contents |
|---|---|
| [Frontend](Frontend) | React single-page app (Vite), served by nginx in production |
| [Backend](Backend) | Spring Boot API: authentication, complaint rules, B2 storage, database migrations (Flyway), background AI jobs |
| [AI](AI) | FastAPI service: analysis, writing assistant, embeddings, semantic search, RAG; civic knowledge base in [AI/knowledge](AI/knowledge) |
| [Docs](Docs) | Requirement traceability and design decisions |

## Configuration

All services read one `.env` at the repository root. Copy [.env.example](.env.example) to `.env` and fill it in; `.env` is gitignored and must never be committed.

| Variable | Used by | Source |
|---|---|---|
| `DATABASE_URL` | backend, AI service | Neon console → Connect (direct connection string) |
| `JWT_SECRET` | backend | Random, at least 32 bytes (48+ recommended) |
| `AI_SERVICE_API_KEY` | backend, AI service | Random shared secret, at least 16 characters |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | backend | First admin account, created on first start (password of 12+ characters) |
| `B2_ENDPOINT`, `B2_BUCKET`, `B2_KEY_ID`, `B2_APPLICATION_KEY` | backend | Backblaze B2 private bucket and application key |
| `GEMINI_API_KEY` | AI service | Google AI Studio |
| `GROQ_API_KEY` | AI service | Groq console |

Optional overrides, with their defaults, are listed at the end of `.env.example`: `AI_SERVICE_URL`, `FRONTEND_PORT` and the model names.

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

Backend and AI service tests start PostgreSQL with pgvector through Testcontainers, so Docker must be running. No automated test touches Neon, Backblaze B2, Gemini or Groq; those integrations are exercised by the end-to-end checks recorded in [Docs/PROGRESS.md](Docs/PROGRESS.md).

## API

All endpoints live under `/api` and use the login cookie (or an `Authorization: Bearer` header). Errors are [problem details](https://www.rfc-editor.org/rfc/rfc9457) with a plain-language `detail` and, for invalid fields, an `errors` map.

| Endpoint | Who | Purpose |
|---|---|---|
| `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | anyone / signed in | Accounts and the session cookie |
| `GET /complaints`, `GET /complaints/{id}` | signed in | Lists and details, limited to what the caller may see |
| `POST /complaints` (multipart) | citizen | Report a problem with an optional photo |
| `POST /complaints/{id}/reopen`, `/feedback` | citizen | Reopen a resolved complaint, or rate the fix (closes it) |
| `POST /complaints/{id}/start`, `/notes`, `/proofs`, `/resolve` | assigned officer | Work the complaint |
| `POST /complaints/{id}/assignment` | admin, assigned officer | Assign or reassign to an officer or department |
| `PATCH /complaints/{id}`, `POST /complaints/{id}/close`, `/analysis/retry` | admin | Edit category or priority, close, re-run AI analysis |
| `GET /categories`, `GET /departments` | signed in | Reference data |
| `GET /officers` | admin, officer | Active officers, for assignment |
| `/admin/users`, `/admin/officers`, `/admin/departments`, `/admin/categories`, `/admin/analytics` | admin | Management and statistics |
| `POST /ai/write` | citizen | Writing assistant |
| `POST /assistant/ask`, `GET /search/complaints`, `GET /search/knowledge` | signed in | Civic assistant and semantic search |

## Security

- Passwords are hashed with BCrypt. Ten wrong passwords lock that account's sign-in for up to ten minutes.
- The login token is a signed JWT in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie. Roles and account status are re-read on every request, so deactivation takes effect at once.
- Every rule is enforced on the server. Complaints a user may not see answer 404, including through search.
- Photos are checked by their file signature (JPEG, PNG or WebP, up to 5 MB) and stored under server-generated names in a private bucket; browsers and the AI service read them only through signed URLs that expire after ten minutes.
- The AI service is called only by the backend, authenticated with a shared key, and is not published by Docker Compose. It fetches images only from Backblaze B2 over HTTPS.
- Serve the frontend over HTTPS in production: the session cookie is `Secure`, and browsers only exempt `localhost` from that rule.

## Continuous integration

[GitHub Actions](.github/workflows/ci.yml) runs the three test suites on every push and pull request, then builds the three container images. Pushes to `main` publish them to GitHub Container Registry as `ghcr.io/<owner>/fmc-backend`, `fmc-ai` and `fmc-frontend`.
