# FMC — Requirement Traceability & Progress

Source of truth: [`FMC.md`](../FMC.md). Every row maps an FMC requirement to its implementation and verification path.

Legend: `[✓]` done & verified · `[→]` in progress · `[ ]` pending · `[!]` manual action required · `[✗]` blocked

## Citizen (FMC A.1–A.10)

Browser verification ran the React app against a disposable PostgreSQL + pgvector database with the real Backblaze B2, Gemini and Groq services, on desktop (1280px) and phone (390px) widths in both themes.

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 1 | Registration & login | Backend `auth` (BCrypt, JWT in httpOnly cookie); Register and Sign in pages | Auth tests ✓; browser: register, sign in, sign out ✓ | [✓] |
| 2 | Create complaint (title, description, category, location, image) | `POST /api/complaints` (multipart, idempotent via requestId); Report a problem page | Browser: report with photo ✓ | [✓] |
| 3 | Category selection | Seeded categories; category plates on the report form | Browser ✓ | [✓] |
| 4 | Image upload → B2 `complaints/{complaintId}/{fileName}` | `StorageService` (B2 S3 API) → `complaints/{id}/{uuid}.{ext}`; signature-checked, ≤ 5 MB | Real B2 upload, byte-identical signed-URL fetch, unsigned 401 ✓; browser upload and signed display ✓ | [✓] |
| 5 | Location submission | `complaints.location` (required) | Browser ✓ | [✓] |
| 6 | Complaint tracking | `GET /api/complaints` scoped to the caller; My complaints and the complaint page's route strip | Browser ✓ | [✓] |
| 7 | Status timeline (Submitted → Assigned → In Progress → Resolved → Closed) | `complaint_status_history` with actor and note; route strip, holder line and route log | Browser: full cycle including a reopen round ✓ | [✓] |
| 8 | Complaint history | Paged list with status filter | Browser ✓ | [✓] |
| 9 | Reopen resolved complaint | `POST …/reopen` (RESOLVED → ASSIGNED; inactive officer → department queue) | Lifecycle tests ✓; browser reopen at 390px → Assigned to the same officer ✓ | [✓] |
| 10 | Resolution feedback | `POST …/feedback` (RESOLVED → CLOSED, one per complaint) | Browser: 4/5 rating closed the complaint ✓ | [✓] |

## AI (FMC B.11–B.20)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 11–14 | Classification, priority, department, summary | FastAPI `/analyze`: one strict-schema Groq call (Gemini fallback) constrained to real category/department ids; background worker in the backend | Live through the browser: photo complaint analysed in the background (Roads, Medium, Roads & Public Works, summary) ✓ | [✓] |
| 15 | Writing assistant | FastAPI `/assist/write` (keeps facts, lists missing details); "Improve with AI" on the report form | Live through the browser ✓ | [✓] |
| 16 | Image recognition (B2 image via signed URL) | B2 signed URL (HTTPS, B2 host only) → Gemini vision → findings fed into triage; failures degrade to text-only | Live: "What the photo shows" from the uploaded B2 photo ✓; startup recovery ✓ | [✓] |
| 17 | Embeddings | `gemini-embedding-2`, 768 dims, retrieval prefixes | Live: 42 knowledge chunks, unit-norm vectors ✓ | [✓] |
| 18 | Vector database | pgvector (`vector(768)`, HNSW cosine) in the one PostgreSQL database | Neon MCP ✓; Testcontainers pgvector ✓ | [✓] |
| 19 | Semantic search (complaints, role-scoped; civic info) | FastAPI `/search/complaints` (scope from backend) and `/search/knowledge`; Search page | Live through the browser: meaning-based match across complaints and guidance ✓ | [✓] |
| 20 | RAG civic assistant | FastAPI `/assistant/ask`: live department directory + 7 civic docs; threshold 0.68 calibrated on real data; Ask FMC page | Live through the browser: grounded answer with sources; off-topic question declined without generation ✓ | [✓] |

## Officer (FMC C.21–C.28)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 21 | Officer login | Shared auth, role `OFFICER` | Auth + officer tests ✓; browser ✓ | [✓] |
| 22 | Officer dashboard | My queue: complaints assigned to the officer with status and priority filters | Browser ✓ | [✓] |
| 23 | View assigned complaint (image, location, AI analysis) | Complaint page with signed images, AI analysis, allowed actions | Browser ✓ | [✓] |
| 24 | Update status | Start work, Mark resolved | Browser ✓ | [✓] |
| 25 | Investigation notes | `POST …/notes`; inline note form | Browser ✓ | [✓] |
| 26 | Resolution proof → B2 `resolution-proofs/{complaintId}/{fileName}` | `POST …/proofs` → `resolution-proofs/{id}/{uuid}.{ext}` (max 5) | Browser upload ✓ | [✓] |
| 27 | Mark resolved (requires note + proof) | Requires note + proof added since the latest (re)assignment; the page says what evidence is missing | Negative tests + real run (409 without evidence) ✓; browser ✓ | [✓] |
| 28 | Reassign to officer or department | `POST …/assignment`, officer or department target; the officer's Reassign opens the same form admins use | Lifecycle tests ✓; assignment form verified in the browser (as admin) | [✓] |

## Admin (FMC D.29–D.36)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 29 | Admin dashboard | Overview: route counts and measured bars | Browser ✓ | [✓] |
| 30 | User (citizen) management | People → Citizens: paged list, deactivate/reactivate (immediate) | Integration tests ✓; browser list ✓ | [✓] |
| 31 | Officer management | People → Officers: add officer, move department, deactivate/reactivate | Integration tests ✓; browser: officer added and signed in ✓ | [✓] |
| 32 | Department management | Routing page; `/api/admin/departments` CRUD, delete blocked when in use | Integration tests ✓; browser page ✓ | [✓] |
| 33 | Complaint management | All complaints with URL filters; edit category/priority; close | Integration tests ✓; browser ✓ | [✓] |
| 34 | Complaint assignment | Assign form (AI-suggested department preselected) | Browser ✓ | [✓] |
| 35 | Category management | Routing page; `/api/admin/categories` CRUD with department routing | Integration tests ✓; browser page ✓ | [✓] |
| 36 | Basic analytics | `GET /api/admin/analytics` on the Overview | Integration test ✓; browser ✓ | [✓] |

## Cross-cutting (FMC architecture & stack)

| Requirement | Implementation | Status |
|---|---|---|
| Spring Security, JWT, password hashing, RBAC | Resource-server JWT (HMAC) from header or httpOnly cookie, BCrypt, roles reloaded from DB per request, `/api/admin/**` admin-only, per-complaint ownership (invisible complaints return 404) | [✓] |
| B2 private bucket; backend-only credentials; short-lived signed URLs | AWS SDK v2 S3 client + presigner (10-minute URLs, issued only after an access check); uploads removed if the transaction rolls back | [✓] |
| Spring Boot ↔ FastAPI over REST | `RestClient` (HTTP/1.1, separate interactive/background timeouts) + shared internal key | [✓] |
| Neon PostgreSQL + pgvector, one database | Flyway migrations owned by the backend (`V1__schema`, `V2__reference_data`) — applied to Neon and verified with the Neon MCP (pgvector 0.8.6, HNSW indexes, routing seed) | [✓] |
| Long-running AI work survives browser/server restarts | `ai_status` in the complaint row, atomic claim, ×4 backoff retries, admin retry, startup recovery (verified live) | [✓] |
| React frontend (JavaScript + CSS) | Vite SPA, React Router data mode; "Street Signage" design (guide-green band, sign-face buttons, route strip, Overpass/Overpass Mono), light and dark themes; labelled fields, focus management on navigation and after actions, skip link, reduced-motion support; 9 Vitest tests | [✓] |
| Docker, Maven, GitHub Actions CI/CD | Multi-stage non-root images; nginx serves the SPA with CSP and security headers and proxies `/api`; the full Compose stack verified healthy against the real services; the frontend image verified with real B2 photos under its CSP; CI runs Maven verify, pytest + Ruff, and lint + tests + build for the frontend, then publishes three images to GHCR on main | [✓] |
| Excluded by FMC | Kubernetes, Kafka, Redis, microservice orchestration, separate vector DB, predictive analytics, IoT, large CV pipelines | Not used |

## Decisions (recorded before implementation)

Where FMC.md is silent, these choices keep the documented model intact with the least machinery.

1. **Statuses stay exactly FMC's five.** A reopened complaint returns to `ASSIGNED` (same officer); the timeline entry records the citizen's reason.
2. **Feedback closes.** Citizen feedback on a `RESOLVED` complaint moves it to `CLOSED`; admins can also close resolved complaints. Only `RESOLVED` complaints can be reopened.
3. **Resolution requires evidence.** `IN_PROGRESS → RESOLVED` needs at least one investigation note and one resolution-proof image (FMC Problem 6).
4. **Reassignment.** Admins anytime; an officer only for complaints assigned to them. Target an officer (→ `ASSIGNED`) or a department only (→ `SUBMITTED`, awaiting assignment in that department).
5. **Images.** Complaint image is optional (FMC: "can also upload"), one per complaint; up to 5 resolution proofs. JPEG/PNG/WebP, ≤ 5 MB, verified by file signature.
6. **AI suggestions.** Citizens pick the category; AI suggests category, priority and department. Priority is filled from the AI suggestion when unset (citizens don't choose it); admins can override everything.
7. **Provider split.** Groq has no vision or embedding models, so Gemini handles image recognition and embeddings; Groq handles text generation with Gemini as fallback. Embeddings never fall back (mixing models corrupts the vector space).
8. **Frontend stack as specified.** React + JavaScript + CSS (no TypeScript, Tailwind or component kit — FMC doesn't list them).
9. **Framework conventions win.** `.github/workflows` stays at the repo root; Flyway migrations stay in the backend (it owns the schema for both services).
10. **Seed reference data.** FMC's six categories are seeded with one matching department each; department names are editable by admins.
11. **Models (verified live 2026-09-29).** Groq `openai/gpt-oss-120b` for text (strict JSON schema; rated an open manhole HIGH where 20b said MEDIUM). Gemini `gemini-3.5-flash-lite` → `gemini-3.5-flash` ordered fallback for vision and text fallback (other 3.x Flash models returned 503/timeouts under load). `gemini-embedding-2` at 768 dims (auto-normalised). Model ids are configurable because providers retire them.
12. **Knowledge base stays current.** Department routing answers come from a directory generated from the live tables at each sync; static docs cover process. Sync embeds only changed chunks (hash-checked).
13. **Neon scale-to-zero.** Both services keep no idle database connections, so pooled connections do not keep waking the database.

## Current state

```text
Current Step:          Final audit
Completed:             Foundation; schema on Neon; live credential checks; authentication; departments, categories and accounts; complaint lifecycle with B2 storage; FastAPI AI service; backend ↔ AI integration; React frontend (browser-verified end to end); container images and CI for all three services
Manual Action Required: None
Tests Passed:          Backend 44/44; AI service 19/19; frontend 9/9; browser end-to-end on desktop and phone in both themes
Tests Failed:          —
Known Issues:          JDK 21 notice about Mockito's dynamically loaded agent (test-only, harmless)
Verification data:     Runtime checks on Neon created citizens and an officer with @fixmycity.test emails (clearly marked, safe to remove)
```
