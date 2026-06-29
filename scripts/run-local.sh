#!/usr/bin/env bash
# Boot life-engine-runtime against local Postgres + LLM.
# JWT_SECRET is shared with auth — read from auth/life-engine-auth/.env.local.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/.." && pwd)"
AUTH_ENV="${PROJECT}/../../auth/life-engine-auth/.env.local"

if [[ -f "${AUTH_ENV}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${AUTH_ENV}"
  set +a
fi

if [[ -z "${JWT_SECRET:-}" || ${#JWT_SECRET} -lt 32 ]]; then
  echo "JWT_SECRET must be set and at least 32 characters." >&2
  echo "Expected in: ${AUTH_ENV}" >&2
  exit 1
fi

export LIFEENGINE_RUNTIME_PERSISTENCE_TYPE=inmem
export FLYWAY_ENABLED=false
export SPRING_FLYWAY_ENABLED=false

cd "${PROJECT}"
exec mvn spring-boot:run \
  -Dspring-boot.run.arguments="--lifeengine.runtime.ext.business-chat.lead-capture.enabled=true"
