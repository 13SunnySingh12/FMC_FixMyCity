# FMC — Requirement Traceability & Progress

Source of truth: [`FMC.md`](../FMC.md). Every row maps an FMC requirement to its implementation and verification path.

Legend: `[✓]` done & verified · `[→]` in progress · `[ ]` pending · `[!]` manual action required · `[✗]` blocked

## Citizen (FMC A.1–A.10)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 1 | Registration & login | Backend `auth` (BCrypt, JWT in httpOnly cookie), Frontend login/register | MockMvc auth tests; runtime check on Neon ✓; browser E2E pending | [→] |
| 2 | Create complaint (title, description, category, location, image) | `POST /api/complaints` (multipart, idempotent via requestId) | API verified on Neon + B2; UI pending | [→] |
| 3 | Category selection | Seeded categories, `GET /api/categories` | API verified on Neon + B2; UI pending | [→] |
| 4 | Image upload → B2 `complaints/{complaintId}/{fileName}` | `StorageService` (B2 S3 API) → `complaints/{id}/{uuid}.{ext}`; signature-checked, ≤ 5 MB | Real B2 upload, byte-identical signed-URL fetch, unsigned 401 ✓; UI pending | [→] |
| 5 | Location submission | `complaints.location` (required) | API verified on Neon + B2; UI pending | [→] |
| 6 | Complaint tracking | `GET /api/complaints` scoped to the caller | API verified on Neon + B2; UI pending | [→] |
| 7 | Status timeline (Submitted → Assigned → In Progress → Resolved → Closed) | `complaint_status_history` with actor and note | API verified on Neon + B2; UI pending | [→] |
| 8 | Complaint history | Paged, filterable history list | API verified on Neon + B2; UI pending | [→] |
| 9 | Reopen resolved complaint | `POST …/reopen` (RESOLVED → ASSIGNED; inactive officer → department queue) | Lifecycle tests ✓; UI pending | [→] |
| 10 | Resolution feedback | `POST …/feedback` (RESOLVED → CLOSED, one per complaint) | API verified on Neon + B2; UI pending | [→] |

## AI (FMC B.11–B.20)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 11–14 | Classification, priority, department, summary | FastAPI `/analyze`: one strict-schema Groq call (Gemini fallback) constrained to real category/department ids | Live through the backend: filed as "Other", analysed in the background in 7.4s → Roads / Roads & Public Works, MEDIUM ✓; UI pending | [→] |
| 15 | Writing assistant | FastAPI `/assist/write` (keeps facts, lists missing details) | Live via `/api/ai/write` (1.2s) ✓; UI pending | [→] |
| 16 | Image recognition (B2 image via signed URL) | B2 signed URL (HTTPS, B2 host only) → Gemini vision → findings fed into triage; failures degrade to text-only | Live: real B2 photo → "pothole clearly visible" ✓; startup recovery analysed a pre-existing complaint ✓ | [→] |
| 17 | Embeddings | `gemini-embedding-2`, 768 dims, retrieval prefixes | Live: 42 KB chunks, unit-norm vectors in Neon ✓ | [→] |
| 18 | Vector database | pgvector on Neon (`vector(768)`, HNSW cosine) | Neon MCP ✓ | [→] |
| 19 | Semantic search (complaints, role-scoped; civic info) | FastAPI `/search/complaints` (scope from backend) and `/search/knowledge` | Live via `/api/search/*`: citizen sees only own complaints, admin sees all ✓; UI pending | [→] |
| 20 | RAG civic assistant | FastAPI `/assistant/ask`: live department directory + 7 civic docs; threshold 0.68 calibrated on real data | Live via `/api/assistant/ask`: grounded reopen guidance with sources ✓; UI pending | [→] |

## Officer (FMC C.21–C.28)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 21 | Officer login | Shared auth, role `OFFICER` | Auth + officer tests ✓ | [✓] |
| 22 | Officer dashboard | `GET /api/complaints` scoped to the assigned officer, status/priority filters | API verified on Neon + B2; UI pending | [→] |
| 23 | View assigned complaint (image, location, AI analysis) | Detail with signed image URLs, AI analysis, allowed actions | API verified on Neon + B2; UI pending | [→] |
| 24 | Update status | `…/start`, `…/resolve` | API verified on Neon + B2; UI pending | [→] |
| 25 | Investigation notes | `POST …/notes` | API verified on Neon + B2; UI pending | [→] |
| 26 | Resolution proof → B2 `resolution-proofs/{complaintId}/{fileName}` | `POST …/proofs` → `resolution-proofs/{id}/{uuid}.{ext}` (max 5) | API verified on Neon + B2; UI pending | [→] |
| 27 | Mark resolved (requires note + proof) | Requires note + proof added since the latest (re)assignment | Negative tests + real run (409 without evidence) ✓; UI pending | [→] |
| 28 | Reassign to officer or department | `POST …/assignment` — officer or department target | Lifecycle tests ✓; UI pending | [→] |

## Admin (FMC D.29–D.36)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 29 | Admin dashboard | Frontend admin home | E2E | [ ] |
| 30 | User (citizen) management | `GET/PATCH /api/admin/users` — paged list, activate/deactivate (immediate) | Integration tests ✓; UI pending | [→] |
| 31 | Officer management | `POST /api/admin/officers`, department change, activate/deactivate | Integration tests ✓; UI pending | [→] |
| 32 | Department management | `/api/admin/departments` CRUD, delete blocked when in use | Integration tests ✓; UI pending | [→] |
| 33 | Complaint management | All complaints with filters; edit category/priority; close | Integration tests ✓; UI pending | [→] |
| 34 | Complaint assignment | `POST …/assignment` by admin | API verified on Neon + B2; UI pending | [→] |
| 35 | Category management | `/api/admin/categories` CRUD with department routing | Integration tests ✓; UI pending | [→] |
| 36 | Basic analytics | `GET /api/admin/analytics`: totals, pending, resolved, by status, category, priority (incl. unset), department, failed AI | Integration test ✓; UI pending | [→] |

## Cross-cutting (FMC architecture & stack)

| Requirement | Implementation | Status |
|---|---|---|
| Spring Security, JWT, password hashing, RBAC | Resource-server JWT (HMAC) from header or httpOnly cookie, BCrypt, roles reloaded from DB per request, `/api/admin/**` admin-only, per-complaint ownership (invisible complaints return 404) | [✓] |
| B2 private bucket; backend-only credentials; short-lived signed URLs | AWS SDK v2 S3 client + presigner (10-minute URLs, issued only after an access check); uploads removed if the transaction rolls back | [✓] |
| Spring Boot ↔ FastAPI over REST | `RestClient` (HTTP/1.1, separate interactive/background timeouts) + shared internal key | [✓] |
| Neon PostgreSQL + pgvector, one database | Flyway migrations owned by the backend (`V1__schema`, `V2__reference_data`) — applied to Neon and verified with the Neon MCP (pgvector 0.8.6, HNSW indexes, routing seed) | [✓] |
| Long-running AI work survives browser/server restarts | `ai_status` in the complaint row, atomic claim, ×4 backoff retries, admin retry, startup recovery (verified live) | [✓] |
| Docker, Maven, GitHub Actions CI/CD | Per-service Dockerfiles, Compose, CI workflow | [ ] |
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
Current Step:          React frontend
Completed:             Foundation; schema on Neon; live credential checks; authentication; departments, categories and accounts; complaint lifecycle with B2 storage; FastAPI AI service; backend ↔ AI integration (all live-verified)
Manual Action Required: None
Tests Passed:          Backend 43/43 (stable across runs); AI service 19/19; auth and full complaint lifecycle runtime-verified on Neon + B2
Tests Failed:          —
Known Issues:          JDK 21 notice about Mockito's dynamically loaded agent (test-only, harmless)
Verification data:     Runtime checks create citizens with @fixmycity.test emails (clearly marked, safe to remove)
```
