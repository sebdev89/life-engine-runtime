#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Multi-agent smoke: MultiAgentDelegateWorkflowTest"
mvn -q test -Dtest=MultiAgentDelegateWorkflowTest

echo "==> Multi-agent smoke: GREEN"
