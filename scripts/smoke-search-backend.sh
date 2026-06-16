#!/usr/bin/env bash
# smoke-search-backend.sh — Sprint 3a: Capability Layer backend smoke
#
# Verifies:
#   1. Runtime health
#   2. search.web is registered in ToolRegistry (GET /api/runtime/tools)
#   3. dev.search-smoke.v1 workflow is registered
#   4. Workflow run with search.web completes SUCCEEDED
#   5. Tool output JSON contains expected fields
#
# Pre-conditions:
#   Runtime running with:
#     SEARCH_ENABLED=true
#     SEARCH_PROVIDER=mock          (or tavily with TAVILY_API_KEY set)
#     runtime.ext.dev-search-smoke.enabled=true   (or via env RUNTIME_DEV_SEARCH_SMOKE_ENABLED=true)
#     RUNTIME_SECURITY_ENABLED=false              (for local smoke; pass TOKEN for secured envs)
#
# Usage:
#   ./scripts/smoke-search-backend.sh [BASE_URL] [TOKEN]
#
# Examples:
#   ./scripts/smoke-search-backend.sh
#   ./scripts/smoke-search-backend.sh http://localhost:8090
#   ./scripts/smoke-search-backend.sh http://runtime:8090 "eyJhbGci..."

set -euo pipefail

BASE_URL="${1:-http://localhost:8090}"
TOKEN="${2:-}"
WORKFLOW_ID="dev.search-smoke.v1"
QUERY="spring boot reactive programming"
MAX_WAIT_SECS=15
POLL_INTERVAL=0.4

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
PASS=0; FAIL=0

pass() { echo -e "  ${GREEN}✓${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "  ${RED}✗${NC} $1"; FAIL=$((FAIL+1)); }
info() { echo -e "  ${YELLOW}→${NC} $1"; }

auth_header() {
  if [ -n "$TOKEN" ]; then
    echo "-H \"Authorization: Bearer $TOKEN\""
  fi
}

curl_get() {
  local url="$1"
  if [ -n "$TOKEN" ]; then
    curl -sf -H "Authorization: Bearer $TOKEN" "$url"
  else
    curl -sf "$url"
  fi
}

curl_post() {
  local url="$1" body="$2"
  if [ -n "$TOKEN" ]; then
    curl -sf -X POST -H "Content-Type: application/json" \
         -H "Authorization: Bearer $TOKEN" -d "$body" "$url"
  else
    curl -sf -X POST -H "Content-Type: application/json" -d "$body" "$url"
  fi
}

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  Sprint 3a — search.web Capability Layer smoke      ║"
echo "╚══════════════════════════════════════════════════════╝"
echo "  Base URL : $BASE_URL"
echo "  Auth     : ${TOKEN:+bearer token set}${TOKEN:-no token (security must be disabled)}"
echo ""

# ── Step 1: Health ────────────────────────────────────────────────────────────
echo "Step 1: Runtime health"
STATUS=$(curl_get "$BASE_URL/api/runtime/health" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" 2>/dev/null || echo "ERROR")
if [ "$STATUS" = "UP" ]; then
  pass "Runtime health = UP"
else
  fail "Runtime health = $STATUS (expected UP). Is the service running at $BASE_URL?"
  exit 1
fi

# ── Step 2: search.web in ToolRegistry ────────────────────────────────────────
echo ""
echo "Step 2: search.web registered in ToolRegistry"
TOOLS=$(curl_get "$BASE_URL/api/runtime/tools")
if echo "$TOOLS" | python3 -c "import sys,json; tools=json.load(sys.stdin); assert any(t['toolId']=='search.web' for t in tools)" 2>/dev/null; then
  pass "search.web found in GET /api/runtime/tools"
else
  fail "search.web NOT found in GET /api/runtime/tools"
  info "Tools available: $(echo "$TOOLS" | python3 -c "import sys,json; print([t['toolId'] for t in json.load(sys.stdin)])" 2>/dev/null || echo "$TOOLS")"
  info "Ensure SEARCH_ENABLED=true in Runtime environment"
  exit 1
fi

# ── Step 3: dev.search-smoke.v1 registered ────────────────────────────────────
echo ""
echo "Step 3: dev.search-smoke.v1 registered"
WORKFLOWS=$(curl_get "$BASE_URL/api/runtime/workflows")
if echo "$WORKFLOWS" | python3 -c "import sys,json; wfs=json.load(sys.stdin); assert any(w['workflowId']=='$WORKFLOW_ID' for w in wfs)" 2>/dev/null; then
  pass "$WORKFLOW_ID found in GET /api/runtime/workflows"
else
  fail "$WORKFLOW_ID NOT found in GET /api/runtime/workflows"
  info "Ensure runtime.ext.dev-search-smoke.enabled=true in Runtime environment"
  exit 1
fi

# ── Step 4: Start workflow run ────────────────────────────────────────────────
echo ""
echo "Step 4: Submit workflow run"
INPUT_JSON=$(python3 -c "import json; print(json.dumps(json.dumps({\"query\":\"$QUERY\",\"maxResults\":3})))")
CORR_ID="smoke-$(date +%s)"
RUN_BODY="{\"workflowId\":\"$WORKFLOW_ID\",\"input\":$INPUT_JSON,\"correlationId\":\"$CORR_ID\"}"

RUN_RESP=$(curl_post "$BASE_URL/api/runtime/runs" "$RUN_BODY")
RUN_ID=$(echo "$RUN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['runId'])" 2>/dev/null || echo "")
if [ -n "$RUN_ID" ]; then
  pass "Run started: runId=$RUN_ID"
else
  fail "Failed to start run. Response: $RUN_RESP"
  exit 1
fi

# ── Step 5: Poll for terminal status ─────────────────────────────────────────
echo ""
echo "Step 5: Poll for SUCCEEDED (timeout ${MAX_WAIT_SECS}s)"
DEADLINE=$(($(date +%s) + MAX_WAIT_SECS))
FINAL_STATUS=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  RUN_DETAIL=$(curl_get "$BASE_URL/api/runtime/runs/$RUN_ID" 2>/dev/null || echo "{}")
  FINAL_STATUS=$(echo "$RUN_DETAIL" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
  if [ "$FINAL_STATUS" = "SUCCEEDED" ] || [ "$FINAL_STATUS" = "FAILED" ]; then
    break
  fi
  sleep "$POLL_INTERVAL"
done

if [ "$FINAL_STATUS" = "SUCCEEDED" ]; then
  pass "Run reached SUCCEEDED"
else
  fail "Run status = $FINAL_STATUS (expected SUCCEEDED)"
  info "Run detail: $(curl_get "$BASE_URL/api/runtime/runs/$RUN_ID" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps({k:d[k] for k in ['status','events'] if k in d}, indent=2))" 2>/dev/null)"
  exit 1
fi

# ── Step 6: Verify tool output ────────────────────────────────────────────────
echo ""
echo "Step 6: Validate tool output"
RUN_DETAIL=$(curl_get "$BASE_URL/api/runtime/runs/$RUN_ID")

TOOL_OUTPUT=$(echo "$RUN_DETAIL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
stages = d.get('agentStages', [])
for s in stages:
    if s.get('stageId') == 'search-stage':
        print(s.get('output',''))
        break
" 2>/dev/null || echo "")

if [ -z "$TOOL_OUTPUT" ]; then
  fail "search-stage output not found in run detail"
else
  TOOL_STATUS=$(echo "$TOOL_OUTPUT" | python3 -c "import sys,json; print(json.loads(sys.stdin.read()).get('status','?'))" 2>/dev/null || echo "?")
  PROVIDER=$(echo "$TOOL_OUTPUT" | python3 -c "import sys,json; print(json.loads(sys.stdin.read()).get('provider','?'))" 2>/dev/null || echo "?")
  RESULT_COUNT=$(echo "$TOOL_OUTPUT" | python3 -c "import sys,json; print(len(json.loads(sys.stdin.read()).get('results',[])))" 2>/dev/null || echo "?")

  if [ "$TOOL_STATUS" = "ok" ] || [ "$TOOL_STATUS" = "disabled" ]; then
    pass "Tool status = $TOOL_STATUS (provider=$PROVIDER, results=$RESULT_COUNT)"
  else
    fail "Tool status = $TOOL_STATUS (expected ok or disabled)"
  fi

  if [ "$TOOL_STATUS" = "ok" ] && [ "$RESULT_COUNT" -gt 0 ] 2>/dev/null; then
    pass "Tool returned $RESULT_COUNT result(s)"
  elif [ "$TOOL_STATUS" = "disabled" ]; then
    info "Provider unavailable (no API key). Disabled path verified."
  fi
fi

# ── Step 7: Verify event sequence ─────────────────────────────────────────────
echo ""
echo "Step 7: Verify event ordering"
EVENTS=$(echo "$RUN_DETAIL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
return_types = [e.get('type') for e in d.get('events',[])]
print(json.dumps(return_types))
" 2>/dev/null || echo "[]")

for EVT in RUN_STARTED STAGE_STARTED TOOL_STARTED TOOL_SUCCEEDED STAGE_SUCCEEDED RUN_SUCCEEDED; do
  if echo "$EVENTS" | python3 -c "import sys,json; assert '$EVT' in json.load(sys.stdin)" 2>/dev/null; then
    pass "Event $EVT present"
  else
    fail "Event $EVT missing"
  fi
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "─────────────────────────────────────────────────────"
if [ "$FAIL" -eq 0 ]; then
  echo -e "  ${GREEN}ALL $PASS checks passed${NC} — Capability Layer Sprint 3a: VERIFIED"
else
  echo -e "  ${RED}$FAIL check(s) FAILED${NC}, $PASS passed"
  exit 1
fi
echo ""
