-- W1-05 (TD-TENANCY-001) — la corrida registra a qué tenant pertenece.
--
-- Hasta acá `life-engine-runtime` no mencionaba la palabra tenant en ningún archivo de
-- src/main: 0 coincidencias. Las 79 corridas de `business-chat.reply.v1` en producción tienen
-- exactamente tres claves en `metadata` —input, executor, conversationId— y ninguna dice de
-- quién es la conversación. No se puede auditar por tenant, ni filtrar, ni atribuir un consumo.
--
-- ESTO NO CONVIERTE A RUNTIME EN UN POLICY ENGINE. Runtime sigue sin validar pertenencia, sin
-- consultar tenant_members y sin autorizar por tenant: eso es de Auth y del vertical dueño del
-- producto. Acá el tenant se REGISTRA, no se decide. La diferencia importa porque el motor es
-- genérico y compartido: meterle política lo vuelve el cuello de botella de todos los verticales.
--
-- El valor sale del TOKEN del llamador, nunca del cuerpo del request. Un tenant que llega en el
-- body es un tenant que el llamador eligió.
--
-- NULLABLE a propósito, y no es provisorio por descuido: los tokens service-to-service todavía
-- no llevan tenant (necesitan `act.tenant`, que es W2), y hoy el 100% del tráfico real de
-- Runtime es S2S. Un NOT NULL rechazaría todas las corridas de Business Chat el día del deploy.
-- Las corridas sin atribuir se cuentan en `runtime_tenancy_missing_claim_total` para que la
-- brecha sea visible en vez de silenciosa.
--
-- El contrato congelado `business-chat.reply.v1` NO se toca: el tenant no entra por el input.

ALTER TABLE runtime_run ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- Sin backfill: no existe fuente de verdad para las corridas históricas. Inventar un tenant
-- para ellas sería peor que dejarlas en NULL — un dato falso es indistinguible de uno real una
-- vez escrito. NULL dice "no se sabe", que es exactamente lo que pasa.

-- El orden (tenant_id, created_at DESC) sirve la consulta que se va a hacer de verdad:
-- "las corridas de este tenant, las más nuevas primero".
CREATE INDEX IF NOT EXISTS idx_runtime_run_tenant_created
    ON runtime_run (tenant_id, created_at DESC)
    WHERE tenant_id IS NOT NULL;

COMMENT ON COLUMN runtime_run.tenant_id IS
    'Tenant del llamador, tomado del claim del token. NULL = token sin tenant (S2S hasta W2) o corrida histórica. Runtime lo registra, no lo autoriza.';
