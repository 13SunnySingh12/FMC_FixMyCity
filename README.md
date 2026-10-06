# FixMyCity (FMC)

[![CI](https://github.com/13SunnySingh12/FMC_FixMyCity/actions/workflows/ci.yml/badge.svg)](https://github.com/13SunnySingh12/FMC_FixMyCity/actions/workflows/ci.yml)

FixMyCity is an AI-assisted civic complaint management system. Citizens report local problems such as potholes, uncollected garbage, broken streetlights, blocked drains and water supply faults, attach a photo, and follow each complaint until it is fixed. Department officers resolve complaints with investigation notes and photo proof, and administrators route and monitor the work.

AI supports every step: it improves the wording of a report, reads the photo, suggests a category, priority and department, summarises the complaint for officers, powers meaning-based search, and answers civic questions from the project's own knowledge base.

## Contents

- [Key features](#key-features)
- [System architecture](#system-architecture)
- [Complaint lifecycle](#complaint-lifecycle)
- [How a complaint is processed](#how-a-complaint-is-processed)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Environment variables](#environment-variables)
- [Running the project](#running-the-project)
- [Testing](#testing)
- [API reference](#api-reference)
- [Database](#database)
- [Security](#security)
- [Deployment](#deployment)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [License](#license)

## Key features

**Citizens**

- Register and sign in with email and password.
- Report a problem with a title, description, category, location and an optional photo (JPEG, PNG or WebP, up to 5 MB).
- Use **Improve with AI** to get clearer wording; nothing changes unless the citizen accepts the suggestion.
- Follow each complaint along its route (Submitted, Assigned, In progress, Resolved, Closed), see who holds it, and read the timestamped log, investigation notes and resolution photos.
- Once a complaint is resolved, confirm the fix with a 1–5 rating, which closes it, or reopen it with a reason.

**Officers**

- Work a queue of the complaints assigned to them, filtered by stage and priority.
- See the description, photo, location, the citizen's contact number and the AI analysis.
- Start work, add investigation notes, upload resolution-proof photos (up to five for each round of work) and mark the complaint resolved.
- Hand a complaint to a colleague or send it back to a department's queue.

**Administrators**

- See totals and counts by status, category, priority and department, with a warning when an AI analysis has failed.
- Browse all complaints with filters, assign them to an officer or a department queue, edit category and priority, close resolved complaints and retry a failed AI analysis.
- Add officers, move them between departments, and deactivate or reactivate citizen and officer accounts.
- Manage departments and categories, including which department each category is routed to.

**AI**

- Background analysis of every new complaint: suggested category, priority (Low, Medium, High) and department, a short summary for officers, and what the photo shows.
- A writing assistant that turns a rough description into a clearer complaint without inventing details.
- Semantic search over complaints and civic guidance; complaint results are limited to what the caller is allowed to see.
- **Ask FMC**, a retrieval-augmented assistant that answers from the civic knowledge base, lists its sources, and says so when the knowledge base does not cover a question.

**Interface**

- Responsive from phone to desktop, with light and dark themes that follow the system setting.
- Keyboard accessible, with a skip link, visible focus, labelled form fields and reduced-motion support.

## System architecture

```mermaid
flowchart TD
    U["Citizen, officer or admin<br/>(browser)"]
    SPA["Frontend<br/>React single-page app served by nginx"]
    API["Backend<br/>Spring Boot REST API"]
    AI["AI service<br/>FastAPI"]
    DB[("PostgreSQL + pgvector<br/>records and vectors")]
    B2[("Backblaze B2<br/>private bucket")]
    LLM["Groq and Google Gemini"]

    U -->|HTTPS| SPA
    SPA -->|"REST /api with login cookie"| API
    API -->|"records, migrations"| DB
    API -->|"uploads, signed URLs"| B2
    API -->|"REST with internal key"| AI
    AI -->|"embeddings, vector search"| DB
    AI -->|"reads photo by signed URL"| B2
    AI -->|"text, vision, embeddings"| LLM
    U -.->|"views photos by signed URL"| B2
```

| Component | Responsibility |
|---|---|
| **Frontend** | React single-page app. It talks only to the backend under `/api`, on the same origin (nginx in production, the Vite dev proxy in development). |
| **Backend** | Authentication, role checks and every complaint rule. Owns the database schema (Flyway), uploads photos to Backblaze B2, issues signed URLs, runs AI analysis as background jobs and forwards interactive AI requests with the caller's scope. |
| **AI service** | Complaint analysis, the writing assistant, embeddings, semantic search and the retrieval-augmented assistant. Called only by the backend, authenticated with a shared key. |
| **Database** | One PostgreSQL database holds both application records and embeddings (pgvector); there is no separate vector database. |
| **Object storage** | Photos live in a private Backblaze B2 bucket. The database stores only their object keys, and files are read through signed URLs that expire after ten minutes. |

## Complaint lifecycle

```mermaid
flowchart LR
    S([Submitted]) -->|assign| A([Assigned])
    A -->|start work| P([In progress])
    P -->|resolve| R([Resolved])
    R -->|feedback or close| C([Closed])
    R -.->|reopen| A
    P -.->|reassign| A
    A -.->|to department queue| S
    P -.->|to department queue| S
```

| Step | Who | Rule |
|---|---|---|
| Assign | Admin | To an active officer, or to a department's queue to be assigned later. |
| Start work | Assigned officer | Only an assigned complaint can be started. |
| Resolve | Assigned officer | Needs at least one investigation note and one proof photo added since the latest assignment, so a reopened complaint cannot be resolved again with old evidence. |
| Feedback | Citizen who reported it | A 1–5 rating closes the complaint; each complaint takes feedback once. |
| Close | Admin | Resolved complaints only. |
| Reopen | Citizen who reported it | Resolved complaints only, with a reason. It returns to the same officer, or to the department's queue if that officer is no longer active. |
| Reassign | Admin, or the assigned officer | To another active officer, or back to a department's queue. |

The server enforces every rule. Each complaint response lists the actions the caller may take next, and the interface shows only those.

## How a complaint is processed

```mermaid
sequenceDiagram
    autonumber
    actor C as Citizen
    participant F as Frontend
    participant B as Backend
    participant D as PostgreSQL
    participant S as Backblaze B2
    participant A as AI service
    participant M as Groq / Gemini

    C->>F: Submit complaint with a photo
    F->>B: POST /api/complaints (multipart)
    B->>B: Check role, validate fields and image signature
    B->>D: Insert complaint and first timeline entry
    B->>S: Upload photo under a generated key
    B->>D: Save the object key and commit
    B-->>F: 201 Created, AI status PENDING
    F-->>C: Complaint page with its route

    Note over B,M: After the commit, a background job analyses the complaint
    B->>A: POST /analyze (text and a signed photo URL)
    A->>S: Fetch the photo by signed URL
    A->>M: Vision: what the photo shows
    A->>M: Triage: category, priority, department, summary
    A->>M: Embed the complaint text
    A-->>B: Validated suggestions and embedding
    B->>D: Store AI fields and vector
    F->>B: Poll the complaint until analysis finishes
    B-->>F: Complaint with AI analysis
```

- **Reliable background work.** The analysis state is stored on the complaint row, so it survives restarts and resumes on startup. An analysis is attempted up to four times with growing delays between attempts; after that an administrator can retry it.
- **AI output is never trusted blindly.** Answers must match a JSON schema, the category and department must be ones the backend offered, and the embedding must have the expected size. An unusable answer falls through to the next model or is retried.
- **People stay in charge.** AI results are suggestions. The AI fills in a priority only when none is set, and never overwrites one chosen by an administrator.
- **Model roles.** Groq generates text, with Gemini as fallback. Gemini handles image recognition and embeddings (`gemini-embedding-2`, 768 dimensions). Embeddings never fall back to another model, because vectors from different models are not comparable.
- **Knowledge base.** The assistant retrieves from the Markdown documents in [AI/knowledge](AI/knowledge) plus a department directory generated from the live categories and departments. Chunks are re-embedded only when their content changes: when the AI service or the backend starts, and whenever an administrator edits departments or categories. If no passage is relevant enough, the assistant answers that the topic is not covered instead of generating one.

## Tech stack

| Layer | Technology |
|---|---|
| Frontend | React 19, React Router 8, Vite 8, plain CSS with custom properties, Phosphor Icons, self-hosted Overpass fonts |
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security, Data JPA, Validation, Actuator), Maven |
| Authentication | Email and password; BCrypt hashes; HMAC-signed (HS256) JWT carried in an `HttpOnly` cookie, validated by Spring Security's resource server |
| AI service | Python 3.12, FastAPI, Uvicorn, Pydantic, psycopg 3 with a connection pool, uv |
| AI models | Groq (`openai/gpt-oss-120b`) for text; Google Gemini (`gemini-3.5-flash-lite`, `gemini-3.5-flash`) for vision and as text fallback; `gemini-embedding-2` for embeddings. Model names are configurable. |
| Database | PostgreSQL (set up for Neon) with pgvector 0.8.0 or newer; Flyway migrations; Hibernate schema validation |
| Object storage | Backblaze B2 through its S3-compatible API (AWS SDK for Java v2) |
| Testing | JUnit Jupiter, Spring MockMvc, Mockito, Awaitility and Testcontainers (backend); pytest and Testcontainers (AI service); Vitest and Testing Library (frontend); Ruff and Oxlint for linting |
| Delivery | Docker, Docker Compose, nginx, GitHub Actions, GitHub Container Registry |

## Project structure

```text
FMC_FixMyCity/
├── Frontend/                     React single-page app
│   ├── src/
│   │   ├── pages/                Screens for citizens, officers and admins
│   │   ├── components/           Shell, complaint actions, lists, shared UI
│   │   ├── styles/               Design tokens and component styles
│   │   ├── api.js                Fetch wrapper and error handling
│   │   └── auth.jsx              Session state
│   ├── nginx.conf                Serves the app, proxies /api, sets security headers
│   └── Dockerfile
├── Backend/                      Spring Boot API
│   ├── src/main/java/com/fixmycity/
│   │   ├── auth/                 Login, JWT cookie, security configuration
│   │   ├── complaint/            Complaint lifecycle, entities and endpoints
│   │   ├── department/           Departments and categories
│   │   ├── user/                 Account management
│   │   ├── storage/              Backblaze B2 uploads and signed URLs
│   │   ├── ai/                   AI service client and background jobs
│   │   ├── admin/                Analytics
│   │   └── common/               Error handling, rate limiting
│   ├── src/main/resources/db/migration/   Flyway migrations
│   ├── src/test/                 Integration tests
│   └── Dockerfile
├── AI/                           FastAPI AI service
│   ├── app/                      Endpoints, model providers, prompts, analysis, RAG
│   ├── knowledge/                Civic knowledge base (Markdown)
│   ├── tests/
│   └── Dockerfile
├── .github/workflows/ci.yml      Tests and image publishing
├── docker-compose.yml            Runs the three services together
└── .env.example                  Configuration template
```

## Getting started

### Prerequisites

- **Docker** with Docker Compose, to run the stack and the backend and AI service tests.
- For development without containers: **Java 21** and **Maven**, **Python 3.12** with **uv**, and **Node.js 24** with npm.
- Accounts and services:
  - a **PostgreSQL** database with the **pgvector** extension, version 0.8.0 or newer (the project is set up for Neon);
  - a private **Backblaze B2** bucket and an application key;
  - a **Google Gemini** API key and a **Groq** API key.

### 1. Clone the repository

```bash
git clone https://github.com/13SunnySingh12/FMC_FixMyCity.git
cd FMC_FixMyCity
```

### 2. Configure the environment

```bash
cp .env.example .env
```

Fill in `.env` as described in [Environment variables](#environment-variables). All three services read this one file.

### 3. Start the application

Use Docker Compose or run the services individually, as described in [Running the project](#running-the-project).

### 4. Sign in

- The first administrator account is created at backend startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD`, if no administrator exists yet.
- Administrators add officers under **People**.
- Citizens create their own accounts from the sign-in page.

## Environment variables

Copy [.env.example](.env.example) to `.env` at the repository root. Keep one `KEY=value` per line with no inline comments, and never commit `.env`.

```env
DATABASE_URL=
JWT_SECRET=
AI_SERVICE_API_KEY=
ADMIN_EMAIL=
ADMIN_PASSWORD=
B2_ENDPOINT=
B2_BUCKET=
B2_KEY_ID=
B2_APPLICATION_KEY=
GEMINI_API_KEY=
GROQ_API_KEY=
```

| Variable | Used by | Purpose |
|---|---|---|
| `DATABASE_URL` | Backend, AI service | PostgreSQL connection URL in the form `postgresql://user:password@host/database`. For Neon, use the direct (not pooled) connection string. |
| `JWT_SECRET` | Backend | Key that signs login tokens. At least 32 bytes. |
| `AI_SERVICE_API_KEY` | Backend, AI service | Shared secret the backend sends to the AI service. At least 16 characters. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Backend | First administrator account. The password must be at least 12 characters. |
| `B2_ENDPOINT` | Backend | S3-compatible endpoint, in the form `s3.<region>.backblazeb2.com`. |
| `B2_BUCKET` | Backend | Name of the private bucket. |
| `B2_KEY_ID`, `B2_APPLICATION_KEY` | Backend | Backblaze application key with access to that bucket. |
| `GEMINI_API_KEY` | AI service | Google Gemini API key (vision, embeddings, text fallback). |
| `GROQ_API_KEY` | AI service | Groq API key (text generation). |

Optional overrides:

| Variable | Default | Purpose |
|---|---|---|
| `AI_SERVICE_URL` | `http://localhost:8000` | Where the backend reaches the AI service. Docker Compose sets it to `http://ai:8000`. |
| `FRONTEND_PORT` | `8081` | Host port Docker Compose publishes the frontend on. |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Text model. |
| `GEMINI_MODELS` | `gemini-3.5-flash-lite,gemini-3.5-flash` | Vision and fallback text models, tried in order. |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-2` | Embedding model. Changing it requires re-embedding stored vectors. |

## Running the project

### With Docker Compose

```bash
docker compose up --build
```

Open http://localhost:8081. Only the frontend is published; the backend and AI service are reachable only inside the Compose network.

### For development

Run each service from its own folder, in this order. The backend and AI service read the root `.env` themselves.

**1. Backend** on port 8080. It applies the database migrations on startup.

```bash
cd Backend
mvn spring-boot:run
```

**2. AI service** on port 8000. It loads the knowledge base on startup.

```bash
cd AI
uv run uvicorn app.main:app --port 8000
```

**3. Frontend** on http://localhost:5173. The dev server proxies `/api` to the backend.

```bash
cd Frontend
npm ci
npm run dev
```

## Testing

```bash
cd Backend
mvn verify
```

```bash
cd AI
uv run ruff check
uv run pytest
```

```bash
cd Frontend
npm run lint
npm test
```

- The backend and AI service tests start PostgreSQL with pgvector through Testcontainers and apply the real migrations, so Docker must be running.
- The automated tests replace Backblaze B2 and the model providers with test doubles; they never call Neon, Backblaze B2, Gemini or Groq.

## API reference

All endpoints are served by the backend under `/api`. Requests are authenticated by the login cookie set at sign-in; an `Authorization: Bearer <token>` header is also accepted.

### Accounts

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Create a citizen account and sign in. |
| POST | `/api/auth/login` | Public | Sign in. |
| POST | `/api/auth/logout` | Public | Clear the login cookie. |
| GET | `/api/auth/me` | Signed in | The current user. |

### Complaints

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/api/complaints` | Citizen | Report a problem. Multipart form: `title`, `description`, `location`, `categoryId`, optional `image` and `requestId` (resubmitting the same `requestId` returns the same complaint). |
| GET | `/api/complaints` | Signed in | Paged list of the complaints the caller may see. Filters: `status`, `categoryId`, `departmentId`, `priority`, `page`, `size`. |
| GET | `/api/complaints/{id}` | Signed in | Details, timeline, notes, signed photo URLs, AI analysis and the caller's allowed actions. |
| POST | `/api/complaints/{id}/reopen` | Citizen | Reopen a resolved complaint with a `reason`. |
| POST | `/api/complaints/{id}/feedback` | Citizen | Rate the fix (`rating` 1–5, optional `comment`); closes the complaint. |
| POST | `/api/complaints/{id}/start` | Assigned officer | Start work. |
| POST | `/api/complaints/{id}/notes` | Assigned officer | Add an investigation note. |
| POST | `/api/complaints/{id}/proofs` | Assigned officer | Upload resolution-proof photos (multipart `images`), up to five for each round of work. |
| POST | `/api/complaints/{id}/resolve` | Assigned officer | Mark as resolved. |
| POST | `/api/complaints/{id}/assignment` | Admin, assigned officer | Assign to an officer (`officerId`) or a department queue (`departmentId`). |
| PATCH | `/api/complaints/{id}` | Admin | Change the category or priority. |
| POST | `/api/complaints/{id}/close` | Admin | Close a resolved complaint. |
| POST | `/api/complaints/{id}/analysis/retry` | Admin | Re-run a failed AI analysis. |

### Reference data and administration

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/api/categories`, `/api/departments` | Signed in | Categories and departments. |
| GET | `/api/officers` | Admin, officer | Active officers, optionally by `departmentId`. |
| GET | `/api/admin/users` | Admin | Paged accounts by `role`. |
| POST | `/api/admin/officers` | Admin | Create an officer. |
| PATCH | `/api/admin/users/{id}` | Admin | Activate or deactivate an account; move an officer to another department. |
| POST, PUT, DELETE | `/api/admin/departments`, `/api/admin/departments/{id}` | Admin | Manage departments. |
| POST, PUT, DELETE | `/api/admin/categories`, `/api/admin/categories/{id}` | Admin | Manage categories and their routing. |
| GET | `/api/admin/analytics` | Admin | Totals and distributions. |

### AI

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/api/ai/write` | Citizen | Writing assistant. |
| POST | `/api/assistant/ask` | Signed in | Ask the civic assistant a `question`. |
| GET | `/api/search/complaints?q=` | Signed in | Semantic search over the complaints the caller may see. |
| GET | `/api/search/knowledge?q=` | Signed in | Semantic search over civic guidance. |

The AI endpoints share a limit of 20 requests per user per minute.

### Errors

Errors are [problem details](https://www.rfc-editor.org/rfc/rfc9457) with a plain-language `detail`; validation failures add an `errors` map of field messages.

| Status | Meaning |
|---|---|
| 400 | Invalid input. |
| 401 | Not signed in, or the session has ended. |
| 403 | The caller's role may not do this. |
| 404 | Not found, including complaints the caller is not allowed to see. |
| 409 | The request conflicts with the current state, for example resolving without evidence or a duplicate name. |
| 413 | Upload too large. |
| 429 | Too many AI requests or failed sign-in attempts. |
| 503 | The database, the AI service or image storage is temporarily unavailable. |

`GET /actuator/health` reports backend health without authentication.

### AI service (internal)

The backend is the AI service's only client. Every endpoint except `GET /health` requires the `X-Internal-Key` header.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/analyze` | Analyse a complaint and embed its text. |
| POST | `/assist/write` | Writing assistant. |
| POST | `/assistant/ask` | Retrieval-augmented answer. |
| POST | `/search/complaints`, `/search/knowledge` | Vector search. |
| POST | `/knowledge/sync` | Re-embed changed knowledge-base chunks. |

## Database

The backend owns the schema through Flyway migrations in [Backend/src/main/resources/db/migration](Backend/src/main/resources/db/migration), and Hibernate validates it at startup. The first migration creates the tables and enables pgvector; the second seeds six departments and six categories (Roads, Garbage, Streetlights, Drainage, Water Supply, Other), which administrators can change.

| Table | Contents |
|---|---|
| `users` | Accounts with a role (citizen, officer or admin), password hash and, for officers, a department. |
| `departments`, `categories` | Reference data; each category is routed to a department. |
| `complaints` | The complaint, its status, priority and assigned officer, the AI analysis fields and a 768-dimension embedding. |
| `complaint_status_history` | One row per timeline entry, with who made the change. |
| `complaint_notes` | Officers' investigation notes. |
| `complaint_attachments` | Backblaze B2 object keys of complaint photos and resolution proofs. |
| `complaint_feedback` | The citizen's rating and comment, one per complaint. |
| `knowledge_chunks` | Knowledge-base passages with their embeddings. |

- Embeddings are indexed with HNSW using cosine distance.
- Searches limited to one citizen's or officer's complaints use pgvector's iterative index scans, which is why pgvector 0.8.0 or newer is required.
- Applied migrations are checksummed. Change the schema by adding a new migration, never by editing an existing one.

## Security

- Passwords are hashed with BCrypt. Ten wrong passwords lock sign-in for that account for up to ten minutes.
- The login token is a signed JWT, valid for eight hours, in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie. Roles and account status are re-read from the database on every request, so deactivating an account takes effect immediately.
- Authorization is enforced on the server for every endpoint. Complaints a user may not see respond with 404, including through search.
- Uploaded photos are identified by their file signature rather than the client's file name or content type, limited to JPEG, PNG and WebP up to 5 MB, and stored under server-generated keys in a private bucket.
- Only the backend holds the Backblaze B2 credentials. Browsers and the AI service read photos through signed URLs that expire after ten minutes.
- The AI service accepts requests only with the shared internal key and fetches images only from Backblaze B2 over HTTPS.
- nginx sends a Content Security Policy and related security headers with the frontend.

## Deployment

The repository contains container images and a CI pipeline, and no configuration for a specific hosting platform.

- **Images.** Each service has its own Dockerfile ([Backend](Backend/Dockerfile), [AI](AI/Dockerfile), [Frontend](Frontend/Dockerfile)). All three images define health checks. The backend and AI service run as a non-root user; the frontend image serves the built app with nginx.
- **Compose.** [docker-compose.yml](docker-compose.yml) runs the three services together and gives each one only the environment variables it needs.
- **CI.** [GitHub Actions](.github/workflows/ci.yml) runs the backend, AI service and frontend test suites on every pull request and every push to `main`, then builds the three images. Pushes to `main` publish them to GitHub Container Registry as `ghcr.io/<owner>/fmc-backend`, `ghcr.io/<owner>/fmc-ai` and `ghcr.io/<owner>/fmc-frontend`, tagged `latest` and with the commit SHA.

When deploying the images elsewhere:

- Supply the same environment variables as in `.env`.
- The frontend's nginx forwards `/api` to `http://backend:8080`, so the backend must be reachable under the host name `backend`.
- Point the backend at the AI service with `AI_SERVICE_URL`, and keep the AI service off the public network.
- Serve the frontend over HTTPS. The login cookie is `Secure`, and browsers only exempt `localhost` from that requirement.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Backend stops with `JWT_SECRET must be at least 32 bytes` | Use a longer `JWT_SECRET`. |
| Backend stops with `DATABASE_URL must look like postgresql://user:password@host/database` | Use the standard PostgreSQL URL, not a JDBC URL. |
| Backend stops with `B2 storage is not configured` or `B2_ENDPOINT must look like s3.<region>.backblazeb2.com` | Set all four `B2_*` variables; the endpoint is the host name only. |
| Log shows `No admin account exists; set ADMIN_EMAIL and a 12+ character ADMIN_PASSWORD to create one` | Set both variables and restart. The account is created only while no administrator exists, so changing these values later does not change an existing administrator. |
| AI service fails to start with a validation error for `ai_service_api_key` | `AI_SERVICE_API_KEY` must be at least 16 characters, and identical for the backend and the AI service. |
| Values from `.env` are ignored or look wrong | Keep one `KEY=value` per line with no inline comments. |
| Port 8081 is already in use | Add `FRONTEND_PORT=8082` (or another free port) to `.env` and start again. |
| The assistant answers that the knowledge base does not cover any question | The knowledge base could not be loaded, usually because the embedding provider was unreachable or `GEMINI_API_KEY` is wrong. Check the AI service log, fix the cause, then restart the AI service or the backend to load it again. |
| A complaint shows that its AI analysis could not be completed | The complaint is still handled normally. Check the AI service log and the provider keys; an administrator can then choose **Retry AI analysis** on the complaint. |
| `Too many failed sign-in attempts` | Wait up to ten minutes. |
| Sign-in does not persist on a host other than `localhost` | The login cookie is `Secure`; serve the site over HTTPS. |
| Photos stop loading on a page that has been open for a while | Signed photo links expire after ten minutes. Choose **Show photos again** or reload the page. |
| Tests fail before running any test | The backend and AI service tests need Docker running. |

## Known limitations

- Rate limits and the AI analysis scheduler are kept in the memory of a single backend instance. Running several backend instances would need a shared store for them.
- Each complaint carries one photo from the citizen, and up to five resolution-proof photos for each round of work (a new round starts when the complaint is assigned, reassigned or reopened).

## License

This repository does not currently include a license file.
