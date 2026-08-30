-- Runtime V3 F1 — el event log pasa a ser la verdad (ADR-RT-003, ADR-RT-012).
--
-- La columna `seq` y el índice (run_id, seq) YA EXISTEN desde V1: esta migración no los crea ni
-- los renumera. Lo único que agrega es la garantía que faltaba, para que el 0 de
-- RuntimeEvent.UNASSIGNED_SEQ —"todavía no pasó por el log"— no pueda confundirse nunca con un
-- número de orden real.
--
-- Sin DDL destructivo. Rollback: DROP CONSTRAINT runtime_event_seq_positive.

ALTER TABLE runtime_event
    ADD CONSTRAINT runtime_event_seq_positive CHECK (seq > 0);
