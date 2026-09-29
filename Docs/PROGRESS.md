# FMC — Requirement Traceability & Progress

Source of truth: [`FMC.md`](../FMC.md). Every row maps an FMC requirement to its implementation and verification path.

Legend: `[✓]` done & verified · `[→]` in progress · `[ ]` pending · `[!]` manual action required · `[✗]` blocked

## Citizen (FMC A.1–A.10)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 1 | Registration & login | Backend `auth` (BCrypt, JWT in httpOnly cookie), Frontend login/register | MockMvc auth tests; browser E2E | [ ] |
| 2 | Create complaint (title, description, category, location, image) | `POST /api/complaints` (multipart), `complaints` table | Integration test; E2E | [ ] |
| 3 | Category selection | `categories` table (seeded), `GET /api/categories` | Integration test | [ ] |
| 4 | Image upload → B2 `complaints/{complaintId}/{fileName}` | `StorageService` (S3 API), `complaint_attachments` | Real B2 upload + signed-URL fetch | [ ] |
| 5 | Location submission | `complaints.location` | Validation test | [ ] |
| 6 | Complaint tracking | `GET /api/complaints` (citizen scope) | Scope tests; E2E | [ ] |
| 7 | Status timeline (Submitted → Assigned → In Progress → Resolved → Closed) | `complaint_status_history` | Lifecycle test | [ ] |
| 8 | Complaint history | Citizen list page | E2E | [ ] |
| 9 | Reopen resolved complaint | `POST /api/complaints/{id}/reopen` (RESOLVED → ASSIGNED) | Lifecycle test | [ ] |
| 10 | Resolution feedback | `POST /api/complaints/{id}/feedback` (RESOLVED → CLOSED) | Lifecycle test | [ ] |

## AI (FMC B.11–B.20)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 11–14 | Classification, priority, department, summary | FastAPI `/analyze` — one structured LLM call (Groq primary, Gemini fallback), validated against real category/department ids | Unit tests (mocked providers); real-provider run | [ ] |
| 15 | Writing assistant | FastAPI `/assist/write`, proxied by Spring | Real-provider run; E2E | [ ] |
| 16 | Image recognition (B2 image via signed URL) | FastAPI fetches signed URL → Gemini vision → findings feed classification | Real B2 + Gemini run | [ ] |
| 17 | Embeddings | Gemini embeddings, 768 dims | Dimension check; real run | [ ] |
| 18 | Vector database | pgvector on Neon (`vector(768)`, HNSW cosine) | Schema check via Neon MCP | [ ] |
| 19 | Semantic search (complaints, role-scoped; civic info) | FastAPI `/search/*`, scope enforced by Spring | Scope tests; real run | [ ] |
| 20 | RAG civic assistant | FastAPI `/assistant/ask`: retrieve KB → grounded answer + sources; honest "not found" when nothing retrieved | Real run; no-retrieval test | [ ] |

## Officer (FMC C.21–C.28)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 21 | Officer login | Shared auth, role `OFFICER` | Auth tests | [ ] |
| 22 | Officer dashboard | Assigned complaints, status & priority | E2E | [ ] |
| 23 | View assigned complaint (image, location, AI analysis) | Detail endpoint with signed URLs | Authorization tests | [ ] |
| 24 | Update status | ASSIGNED → IN_PROGRESS → RESOLVED | Lifecycle test | [ ] |
| 25 | Investigation notes | `complaint_notes` | Integration test | [ ] |
| 26 | Resolution proof → B2 `resolution-proofs/{complaintId}/{fileName}` | `StorageService`, `complaint_attachments` | Real B2 run | [ ] |
| 27 | Mark resolved (requires note + proof) | Server-side rule | Negative test | [ ] |
| 28 | Reassign to officer or department | `POST /api/complaints/{id}/assignment` (admin, or currently assigned officer) | Authorization tests | [ ] |

## Admin (FMC D.29–D.36)

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 29 | Admin dashboard | Frontend admin home | E2E | [ ] |
| 30 | User (citizen) management | List, activate/deactivate | Integration test | [ ] |
| 31 | Officer management | Create officer, set department, activate/deactivate | Integration test | [ ] |
| 32 | Department management | CRUD, delete blocked when in use | Integration test | [ ] |
| 33 | Complaint management | All complaints, filters, edit category/priority, close, retry AI | Integration test | [ ] |
| 34 | Complaint assignment | Assign to department or officer | Lifecycle test | [ ] |
| 35 | Category management | CRUD, delete blocked when in use | Integration test | [ ] |
| 36 | Basic analytics | Totals, pending, resolved, by category, by priority | Integration test | [ ] |

## Cross-cutting (FMC architecture & stack)

| Requirement | Implementation | Status |
|---|---|---|
| Spring Security, JWT, password hashing, RBAC | Resource-server JWT (HMAC), BCrypt, role checks + per-complaint ownership | [ ] |
| B2 private bucket; backend-only credentials; short-lived signed URLs | AWS SDK v2 S3 client + presigner | [ ] |
| Spring Boot ↔ FastAPI over REST | `RestClient` + shared internal API key | [ ] |
| Neon PostgreSQL + pgvector, one database | Flyway migrations owned by the backend | [ ] |
| Long-running AI work survives browser/server restarts | Persistent `ai_status` + background worker with retries and startup recovery | [ ] |
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

## Current state

```text
Current Step:          Initial analysis complete; repository foundation next
Completed Before Stop: Spec analysis, environment/tool audit, Neon inspection, requirement matrix
Manual Action Required: Credentials (see README "Configuration") — needed before integration verification
Tests Passed:          —
Tests Failed:          —
Known Issues:          —
Git Commit:            —
Git Push Status:       —
```
