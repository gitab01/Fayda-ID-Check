-- Fayda-ID Check — MS SQL Server baseline schema
-- Design rule: no column in this database ever holds biometric image bytes.
-- Scores, hashes and decision metadata only.

CREATE TABLE organizations (
    id              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    code            NVARCHAR(32)   NOT NULL UNIQUE,   -- institutional identifier, e.g. bank code
    display_name    NVARCHAR(160)  NOT NULL,
    status          NVARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at_utc  DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME()
);

-- users.id is assigned, never generated: it is the identity-registry id carried in the JWT
-- `sub`, and an attempt row must hang off the same value the token says. Every other id in this
-- schema is service-generated.
CREATE TABLE users (
    id                  BIGINT NOT NULL PRIMARY KEY,
    national_id_hash    NVARCHAR(64)  NULL,     -- SHA-256(salt || id_number), hex
    id_hash_salt        NVARCHAR(32)  NULL,     -- per-user random salt, never the ID itself
    full_name           NVARCHAR(160) NOT NULL,
    phone               NVARCHAR(32)  NULL,
    org_id              BIGINT NULL REFERENCES organizations(id),
    role                NVARCHAR(24)  NOT NULL DEFAULT 'SUBJECT',  -- SUBJECT | REVIEWER | ADMIN
    status              NVARCHAR(24)  NOT NULL DEFAULT 'PENDING',  -- PENDING | ACTIVE | SUSPENDED
    created_at_utc      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT uq_users_id_hash UNIQUE (national_id_hash)
);

CREATE TABLE verification_attempts (
    id                BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    status            NVARCHAR(16) NOT NULL,          -- CREATED|CAPTURED|INFERRING|DECIDED|EXPIRED|FAILED_RETRYABLE
    challenge_seed    NVARCHAR(64) NOT NULL,          -- server-issued; sequence must match on submit
    challenge_actions NVARCHAR(256) NOT NULL,         -- ordered prompts the seed produced, comma separated
    device_info       NVARCHAR(256) NULL,
    request_key_hash  NVARCHAR(64) NULL,              -- sha256 of the per-attempt payload key id; key itself is never stored
    attempt_no        INT          NOT NULL DEFAULT 1,-- retry counter for the same subject
    started_at_utc    DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
    stage_deadline_utc DATETIME2(3) NULL,             -- timeout for the current step
    completed_at_utc  DATETIME2(3) NULL,
    CONSTRAINT ck_attempt_status CHECK (status IN
        ('CREATED','CAPTURED','INFERRING','DECIDED','EXPIRED','FAILED_RETRYABLE'))
);
CREATE INDEX ix_attempts_user_started ON verification_attempts (user_id, started_at_utc DESC);
CREATE INDEX ix_attempts_status_deadline ON verification_attempts (status, stage_deadline_utc);

-- 1—1 with an attempt: an attempt with no row here is visibly incomplete.
CREATE TABLE decision_records (
    id                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    attempt_id            BIGINT NOT NULL UNIQUE REFERENCES verification_attempts(id),
    liveness_score        DECIMAL(6,5) NOT NULL,   -- attack probability, 0..1
    match_score           DECIMAL(6,5) NOT NULL,   -- cosine similarity, -1..1 normalised to 0..1
    doc_validation_score  DECIMAL(6,5) NOT NULL,
    composite_score       DECIMAL(6,5) NOT NULL,
    decision              NVARCHAR(8)  NOT NULL,   -- PASS | REVIEW | FAIL
    reason_code           NVARCHAR(48) NOT NULL,   -- machine-readable, drives the retry guidance text
    guidance_text         NVARCHAR(256) NULL,      -- actionable instruction shown to the user
    threshold_version     NVARCHAR(24) NOT NULL,   -- which profile produced this outcome
    liveness_model_ver    NVARCHAR(32)  NOT NULL,
    embedding_model_ver   NVARCHAR(32)  NOT NULL,
    decided_at_utc        DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_decision CHECK (decision IN ('PASS','REVIEW','FAIL'))
);
CREATE INDEX ix_decisions_decision_decided ON decision_records (decision, decided_at_utc);

CREATE TABLE document_metadata (
    id               BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    attempt_id       BIGINT NOT NULL REFERENCES verification_attempts(id),
    document_type    NVARCHAR(32)  NOT NULL,   -- NATIONAL_ID | PASSPORT | DRIVING_LICENSE
    id_number_hash   NVARCHAR(64)  NOT NULL,   -- salted hash; the raw number is never persisted
    hash_salt        NVARCHAR(32)  NOT NULL,
    issuing_region   NVARCHAR(64)  NULL,
    expiry_date      DATE          NULL,
    mrz_valid        BIT           NOT NULL DEFAULT 0,
    checksum_valid   BIT           NOT NULL DEFAULT 0,
    ocr_confidence   DECIMAL(5,4)  NULL,
    captured_at_utc  DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT uq_document_attempt UNIQUE (attempt_id)
);

-- Thresholds and weights are data, not code branches: a profile row is the
-- thing decision_records.threshold_version points at.
CREATE TABLE threshold_profiles (
    version             NVARCHAR(24)  NOT NULL PRIMARY KEY,
    weight_liveness     DECIMAL(5,4)  NOT NULL,
    weight_match        DECIMAL(5,4)  NOT NULL,
    weight_document     DECIMAL(5,4)  NOT NULL,
    pass_composite      DECIMAL(6,5)  NOT NULL,   -- composite >= this -> PASS
    review_composite    DECIMAL(6,5)  NOT NULL,   -- >= this -> REVIEW, below -> FAIL
    min_liveness        DECIMAL(6,5)  NOT NULL,   -- hard floor: attack score above this always fails
    min_match           DECIMAL(6,5)  NOT NULL,   -- hard ceiling on similarity for a reject
    max_attempts        INT           NOT NULL,   -- exceeded -> forced REVIEW (step-up)
    active              BIT           NOT NULL DEFAULT 0,
    calibrated_on       NVARCHAR(64)  NULL,       -- held-out set reference
    created_at_utc      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE audit_log (
    id             BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    actor_id       BIGINT NULL REFERENCES users(id),
    actor_kind     NVARCHAR(16) NOT NULL,      -- SUBJECT | REVIEWER | SYSTEM
    action         NVARCHAR(48) NOT NULL,      -- ATTEMPT_STARTED | PAYLOAD_RECEIVED | INFERENCE_CALLED | DECISION_MADE | RECORD_READ
    attempt_id     BIGINT NULL REFERENCES verification_attempts(id),
    ip_address     NVARCHAR(45) NULL,
    outcome        NVARCHAR(24) NOT NULL,
    detail_hash    NVARCHAR(64) NULL,          -- sha256 of free-text detail; details are not stored in the clear
    created_at_utc DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE INDEX ix_audit_attempt_time ON audit_log (attempt_id, created_at_utc);
CREATE INDEX ix_audit_actor_time ON audit_log (actor_id, created_at_utc);
