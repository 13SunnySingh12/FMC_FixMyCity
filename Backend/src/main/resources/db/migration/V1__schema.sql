-- FMC schema. One Neon database holds records and vectors (pgvector); image files live in Backblaze B2
-- and only their object keys are stored here.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE departments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE categories (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(80)  NOT NULL UNIQUE,
    description   VARCHAR(500),
    department_id BIGINT REFERENCES departments (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL CHECK (role IN ('CITIZEN', 'OFFICER', 'ADMIN')),
    phone         VARCHAR(20),
    department_id BIGINT REFERENCES departments (id),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_officer_has_department CHECK (role <> 'OFFICER' OR department_id IS NOT NULL)
);

CREATE TABLE complaints (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    citizen_id          BIGINT       NOT NULL REFERENCES users (id),
    request_id          UUID         NOT NULL,
    title               VARCHAR(150) NOT NULL,
    description         TEXT         NOT NULL,
    location            VARCHAR(300) NOT NULL,
    category_id         BIGINT       NOT NULL REFERENCES categories (id),
    department_id       BIGINT REFERENCES departments (id),
    assigned_officer_id BIGINT REFERENCES users (id),
    status              VARCHAR(12)  NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority            VARCHAR(6) CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),

    -- AI analysis: persisted so processing survives browser and server restarts.
    ai_status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
        CHECK (ai_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    ai_attempts         INT          NOT NULL DEFAULT 0,
    ai_error            VARCHAR(500),
    ai_category_id      BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    ai_priority         VARCHAR(6) CHECK (ai_priority IN ('LOW', 'MEDIUM', 'HIGH')),
    ai_department_id    BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    ai_summary          TEXT,
    ai_image_findings   TEXT,
    ai_model            VARCHAR(120),
    ai_updated_at       TIMESTAMPTZ,
    embedding           vector(768),

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A retried submission (same client request id) must not create a second complaint.
    CONSTRAINT complaints_request_unique UNIQUE (citizen_id, request_id),
    -- Only SUBMITTED complaints are unassigned; every later status has a responsible officer.
    CONSTRAINT complaints_officer_matches_status CHECK ((status = 'SUBMITTED') = (assigned_officer_id IS NULL))
);

CREATE INDEX complaints_citizen_idx ON complaints (citizen_id, created_at DESC);
CREATE INDEX complaints_officer_idx ON complaints (assigned_officer_id, status);
CREATE INDEX complaints_status_idx ON complaints (status, created_at DESC);
CREATE INDEX complaints_ai_open_idx ON complaints (ai_status) WHERE ai_status IN ('PENDING', 'PROCESSING');
CREATE INDEX complaints_embedding_idx ON complaints USING hnsw (embedding vector_cosine_ops);

CREATE TABLE complaint_status_history (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id BIGINT        NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    status       VARCHAR(12)   NOT NULL,
    note         VARCHAR(1000),
    changed_by   BIGINT        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX complaint_status_history_complaint_idx ON complaint_status_history (complaint_id, created_at);

CREATE TABLE complaint_notes (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id BIGINT        NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    author_id    BIGINT        NOT NULL REFERENCES users (id),
    body         VARCHAR(2000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX complaint_notes_complaint_idx ON complaint_notes (complaint_id, created_at);

CREATE TABLE complaint_attachments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id  BIGINT       NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    kind          VARCHAR(20)  NOT NULL CHECK (kind IN ('COMPLAINT_IMAGE', 'RESOLUTION_PROOF')),
    object_key    VARCHAR(300) NOT NULL UNIQUE,
    original_name VARCHAR(255),
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    uploaded_by   BIGINT       NOT NULL REFERENCES users (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX complaint_attachments_complaint_idx ON complaint_attachments (complaint_id, kind);

CREATE TABLE complaint_feedback (
    complaint_id BIGINT PRIMARY KEY REFERENCES complaints (id) ON DELETE CASCADE,
    rating       SMALLINT      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment      VARCHAR(1000),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Civic knowledge base for the RAG assistant; chunks are re-embedded only when their content hash changes.
CREATE TABLE knowledge_chunks (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source       VARCHAR(200) NOT NULL,
    chunk_index  INT          NOT NULL,
    title        VARCHAR(300) NOT NULL,
    content      TEXT         NOT NULL,
    content_hash CHAR(64)     NOT NULL,
    embedding    vector(768)  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source, chunk_index)
);

CREATE INDEX knowledge_chunks_embedding_idx ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);
