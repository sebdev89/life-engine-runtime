-- life-engine-runtime — Phase-2 persistence baseline.
--
-- Owns DB `life_engine_runtime` (schema `public`). Generic run history only:
-- runs, append-only events, agent/tool stage records, LLM call records.
-- No vertical schema (CryptoBot or otherwise) lives here — verticals own
-- their own DB.

CREATE TABLE IF NOT EXISTS runtime_run (
    id              UUID         PRIMARY KEY,
    status          VARCHAR(32)  NOT NULL,
    workflow_id     VARCHAR(255) NOT NULL,
    correlation_id  VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_runtime_run_workflow_created
    ON runtime_run (workflow_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_runtime_run_status
    ON runtime_run (status);

CREATE TABLE IF NOT EXISTS runtime_event (
    event_id    UUID         PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES runtime_run (id) ON DELETE CASCADE,
    type        VARCHAR(64)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    source      VARCHAR(128) NOT NULL,
    attributes  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    terminal    BOOLEAN      NOT NULL DEFAULT FALSE,
    seq         BIGSERIAL
);

CREATE INDEX IF NOT EXISTS idx_runtime_event_run_seq
    ON runtime_event (run_id, seq);
CREATE INDEX IF NOT EXISTS idx_runtime_event_run_type
    ON runtime_event (run_id, type);

CREATE TABLE IF NOT EXISTS runtime_agent_stage_record (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id       UUID         NOT NULL REFERENCES runtime_run (id) ON DELETE CASCADE,
    stage_id     VARCHAR(128) NOT NULL,
    stage_type   VARCHAR(32)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    duration_ms  BIGINT,
    input        TEXT,
    output       TEXT,
    error        TEXT,
    metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    seq          BIGSERIAL
);

CREATE INDEX IF NOT EXISTS idx_runtime_agent_stage_record_run_seq
    ON runtime_agent_stage_record (run_id, seq);

CREATE TABLE IF NOT EXISTS runtime_llm_call_record (
    id              UUID         PRIMARY KEY,
    run_id          UUID         NOT NULL REFERENCES runtime_run (id) ON DELETE CASCADE,
    stage_id        VARCHAR(128),
    agent_id        VARCHAR(128),
    provider        VARCHAR(64),
    model           VARCHAR(255),
    prompt          TEXT,
    raw_response    TEXT,
    parsed_response TEXT,
    parse_error     TEXT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT       NOT NULL DEFAULT 0,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    seq             BIGSERIAL
);

CREATE INDEX IF NOT EXISTS idx_runtime_llm_call_record_run_seq
    ON runtime_llm_call_record (run_id, seq);
CREATE INDEX IF NOT EXISTS idx_runtime_llm_call_record_agent
    ON runtime_llm_call_record (agent_id);
