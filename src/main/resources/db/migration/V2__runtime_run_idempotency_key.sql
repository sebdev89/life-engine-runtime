-- Optional caller-supplied idempotency key on runtime_run.
--
-- A repeated POST /api/runtime/runs with the same idempotencyKey must return the
-- already-created run instead of starting a duplicate. The column is nullable —
-- callers that do not send a key keep the existing semantics (every POST creates
-- a run). Uniqueness is enforced only for non-null keys via a partial unique
-- index, which also gives the lookup path an index scan.

ALTER TABLE runtime_run
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_runtime_run_idempotency_key
    ON runtime_run (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
