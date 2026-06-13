#!/usr/bin/env bash
# Runtime agent-testing.evaluate.v1 offline gate.
#
# Usage:
#   ./scripts/smoke-atp-runtime-evaluate.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Runtime agent-testing.evaluate smoke: AgentTestingEvaluateWorkflowTest"
mvn -q test -Dtest=AgentTestingEvaluateWorkflowTest

echo "==> Runtime agent-testing.evaluate smoke: GREEN"
