#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Supervisor smoke: SupervisorRouteWorkflowTest"
mvn -q test -Dtest=SupervisorRouteWorkflowTest

echo "==> Supervisor smoke: GREEN"
