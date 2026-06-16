#!/usr/bin/env bash
# smoke-capability-layer.sh — Sprint 4a: E2E Capability Layer validation
#
# Proves the full integration:
#
#   DevAgent → Runtime → rag.query TOOL → dev-context AGENT → dev-answer AGENT
#
# What this script validates:
#   1. Runtime UP
#   2. DevAgent UP
#   3. rag.query registered in Runtime ToolRegistry
#   4. dev.knowledge-answer.v1 registered in Runtime WorkflowRegistry
#   5. DevAgent /ask returns runtimeStatus=SUCCEEDED with an answer
#   6. Runtime run events contain TOOL_STARTED + TOOL_SUCCEEDED (rag.query)
#   7. Stage ordering: rag-query → dev-context → dev-answer
#   8. (optional) search.web registered when SEARCH_ENABLED=true
#
# Pre-conditions:
#   Runtime:  RAG_TOOL_ENABLED=true, RAG_BASE_URL set
#             RUNTIME_SECURITY_ENABLED=false OR pass RUNTIME_TOKEN
#   DevAgent: DEV_AGENT_RAG_DELEGATE_TO_RUNTIME=true
#             DEV_AGENT_SECURITY_ENABLED=false OR pass DEVAGENT_TOKEN
#
# Usage:
#   ./scripts/smoke-capability-layer.sh [RUNTIME_URL] [DEVAGENT_URL] [RUNTIME_TOKEN] [DEVAGENT_TOKEN]
#
# Examples:
#   ./scripts/smoke-capability-layer.sh
#   ./scripts/smoke-capability-layer.sh http://localhost:8090 http://localhost:8093
#   ./scripts/smoke-capability-layer.sh http://rt:8090 http://da:8093 "eyJ..." "eyJ..."

set -euo pipefail

RUNTIME_URL="${1:-http://localhost:8090}"
DEVAGENT_URL="${2:-http://localhost:8093}"
RUNTIME_TOKEN="${3:-}"
DEVAGENT_TOKEN="${4:-}"

SEARCH_ENABLED="${SEARCH_ENABLED:-false}"
QUESTION="${SMOKE_QUESTION:-What is the dev.knowledge-answer workflow and how does it use RAG?}"
MAX_WAIT_SECS=30

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
PASS=0; FAIL=0

pass()  { echo -e "  ${GREEN}✓${NC} $1"; PASS=$((PASS+1)); }
fail()  { echo -e "  ${RED}✗${NC} $1"; FAIL=$((FAIL+1)); }
info()  { echo -e "  ${YELLOW}→${NC} $1"; }
step()  { echo -e "\n${CYAN}Step $1:${NC} $2"; }

curl_get() {
  local url="$1" token="${2:-}"
  if [ -n "$token" ]; then
    curl -sf -H "Authorization: Bearer $token" "$url"
  else
    curl -sf "$url"
  fi
}

curl_post() {
  local url="$1" body="$2" token="${3:-}"
  if [ -n "$token" ]; then
    curl -sf -X POST -H "Content-Type: application/json" \
         -H "Authorization: Bearer $token" -d "$body" "$url"
  else
    curl -sf -X POST -H "Content-Type: application/json" -d "$body" "$url"
  fi
}

py() { python3 -c "$1" 2>/dev/null; }

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   Sprint 4a — Capability Layer E2E Smoke                ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo "  Runtime   : $RUNTIME_URL"
echo "  DevAgent  : $DEVAGENT_URL"
echo "  Auth RT   : ${RUNTIME_TOKEN:+token set}${RUNTIME_TOKEN:-none (security must be disabled)}"
echo "  Auth DA   : ${DEVAGENT_TOKEN:+token set}${DEVAGENT_TOKEN:-none (security must be disabled)}"
echo "  Search    : $SEARCH_ENABLED"
echo "  Question  : $QUESTION"

# ── Step 1: Runtime health ─────────────────────────────────────────────────────
step 1 "Runtime health"
RT_STATUS=$(curl_get "$RUNTIME_URL/api/runtime/health" "$RUNTIME_TOKEN" \
  | py "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" || echo "ERROR")
if [ "$RT_STATUS" = "UP" ]; then
  pass "Runtime health = UP"
else
  fail "Runtime health = $RT_STATUS (expected UP) — is Runtime running at $RUNTIME_URL?"
  exit 1
fi

# ── Step 2: DevAgent health ────────────────────────────────────────────────────
step 2 "DevAgent health"
DA_STATUS=$(curl_get "$DEVAGENT_URL/api/dev-agent/health" "$DEVAGENT_TOKEN" \
  | py "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" || echo "ERROR")
if [ "$DA_STATUS" = "UP" ]; then
  pass "DevAgent health = UP"
else
  fail "DevAgent health = $DA_STATUS (expected UP) — is DevAgent running at $DEVAGENT_URL?"
  exit 1
fi

# ── Step 3: rag.query registered in Runtime ────────────────────────────────────
step 3 "rag.query tool registered in Runtime"
TOOLS=$(curl_get "$RUNTIME_URL/api/runtime/tools" "$RUNTIME_TOKEN" || echo "[]")
if py "import sys,json; tools=json.loads('''$TOOLS'''); assert any(t.get('toolId')=='rag.query' for t in tools)"; then
  pass "rag.query found in GET /api/runtime/tools"
else
  fail "rag.query NOT found in GET /api/runtime/tools"
  info "Ensure RAG_TOOL_ENABLED=true in Runtime environment"
  info "Tools available: $(py "import json; tools=json.loads('''$TOOLS'''); print([t.get('toolId') for t in tools])" || echo "$TOOLS")"
  exit 1
fi

# ── Step 4: dev.knowledge-answer.v1 registered ────────────────────────────────
step 4 "dev.knowledge-answer.v1 workflow registered"
WORKFLOWS=$(curl_get "$RUNTIME_URL/api/runtime/workflows" "$RUNTIME_TOKEN" || echo "[]")
if py "import json; wfs=json.loads('''$WORKFLOWS'''); assert any(w.get('workflowId')=='dev.knowledge-answer.v1' for w in wfs)"; then
  pass "dev.knowledge-answer.v1 found in GET /api/runtime/workflows"
else
  fail "dev.knowledge-answer.v1 NOT found in GET /api/runtime/workflows"
  exit 1
fi

# ── Step 5 (optional): search.web registered ──────────────────────────────────
if [ "$SEARCH_ENABLED" = "true" ]; then
  step "5 (search)" "search.web tool registered (SEARCH_ENABLED=true)"
  if py "import json; tools=json.loads('''$TOOLS'''); assert any(t.get('toolId')=='search.web' for t in tools)"; then
    pass "search.web found in GET /api/runtime/tools"
  else
    fail "search.web NOT found — ensure runtime.tools.search.enabled=true"
  fi
fi

EXPECT_SEARCH_STAGE="false"
if [ "$SEARCH_ENABLED" = "true" ]; then
  EXPECT_SEARCH_STAGE="true"
fi

# ── Step 6: DevAgent /ask ─────────────────────────────────────────────────────
step 6 "Submit question via DevAgent POST /api/dev-agent/ask"
QUESTION_ESCAPED=$(py "import json,sys; print(json.dumps('$QUESTION'))")
ASK_BODY="{\"question\":$QUESTION_ESCAPED}"

ASK_RESP=$(curl_post "$DEVAGENT_URL/api/dev-agent/ask" "$ASK_BODY" "$DEVAGENT_TOKEN" || echo "")
if [ -z "$ASK_RESP" ]; then
  fail "DevAgent /ask returned empty response"
  exit 1
fi

RUNTIME_RUN_ID=$(py "import json; d=json.loads('''$ASK_RESP'''); print(d.get('runtimeRunId',''))" || echo "")
RUNTIME_STATUS=$(py "import json; d=json.loads('''$ASK_RESP'''); print(d.get('runtimeStatus',''))" || echo "")
RETRIEVAL_STATUS=$(py "import json; d=json.loads('''$ASK_RESP'''); print(d.get('retrievalStatus',''))" || echo "")
ANSWER=$(py "import json; d=json.loads('''$ASK_RESP'''); print(d.get('answer','')[:120])" || echo "")
CONFIDENCE=$(py "import json; d=json.loads('''$ASK_RESP'''); print(d.get('confidence',''))" || echo "")

if [ -n "$RUNTIME_RUN_ID" ] && [ "$RUNTIME_RUN_ID" != "None" ] && [ "$RUNTIME_RUN_ID" != "null" ]; then
  pass "DevAgent returned runtimeRunId=$RUNTIME_RUN_ID"
else
  fail "DevAgent response missing runtimeRunId"
  info "Response: $ASK_RESP"
  exit 1
fi

# ── Step 7: Validate DevAgent response ────────────────────────────────────────
step 7 "Validate DevAgent response"

if [ "$RUNTIME_STATUS" = "SUCCEEDED" ]; then
  pass "runtimeStatus = SUCCEEDED"
else
  fail "runtimeStatus = $RUNTIME_STATUS (expected SUCCEEDED)"
  info "Response: $ASK_RESP"
fi

if [ -n "$ANSWER" ] && [ "$ANSWER" != "None" ]; then
  pass "answer present: \"${ANSWER:0:80}...\""
else
  fail "answer is blank"
fi

info "confidence=$CONFIDENCE  retrievalStatus=$RETRIEVAL_STATUS"

# ── Step 8: Fetch Runtime run and validate events ─────────────────────────────
step 8 "Validate Runtime run events (via runtimeRunId)"
RUN_DETAIL=$(curl_get "$RUNTIME_URL/api/runtime/runs/$RUNTIME_RUN_ID" "$RUNTIME_TOKEN" || echo "{}")

EVENTS_JSON=$(py "
import json, sys
d = json.loads('''$RUN_DETAIL''')
print(json.dumps([e.get('type') for e in d.get('events', [])]))
" || echo "[]")

check_event() {
  local evt="$1"
  if py "import json; evts=json.loads('''$EVENTS_JSON'''); assert '$evt' in evts"; then
    pass "Event $evt present"
  else
    fail "Event $evt missing"
  fi
}

check_event "TOOL_STARTED"
check_event "TOOL_SUCCEEDED"
check_event "AGENT_STARTED"
check_event "AGENT_SUCCEEDED"
check_event "RUN_SUCCEEDED"

if py "import json; evts=json.loads('''$EVENTS_JSON'''); assert 'TOOL_FAILED' not in evts and 'RUN_FAILED' not in evts"; then
  pass "No TOOL_FAILED or RUN_FAILED events"
else
  fail "Unexpected failure events found"
  info "Events: $EVENTS_JSON"
fi

# ── Step 9: Validate rag-query stage in run ───────────────────────────────────
step 9 "Validate rag-query stage in Runtime run"
RAG_STAGE=$(py "
import json, sys
d = json.loads('''$RUN_DETAIL''')
for s in d.get('agentStages', []):
    if s.get('stageId') == 'rag-query':
        print(json.dumps(s))
        break
" || echo "")

if [ -n "$RAG_STAGE" ] && [ "$RAG_STAGE" != "None" ]; then
  RAG_STAGE_STATUS=$(py "import json; print(json.loads('''$RAG_STAGE''').get('status',''))" || echo "")
  if [ "$RAG_STAGE_STATUS" = "SUCCEEDED" ]; then
    pass "rag-query stage status = SUCCEEDED"
  else
    fail "rag-query stage status = $RAG_STAGE_STATUS"
  fi

  RAG_OUTPUT_STATUS=$(py "
import json
stage = json.loads('''$RAG_STAGE''')
output = stage.get('output', '{}')
try:
    out = json.loads(output) if isinstance(output, str) else output
    print(out.get('status', '?'))
except Exception:
    print('?')
" || echo "?")
  if [ "$RAG_OUTPUT_STATUS" = "ok" ] || [ "$RAG_OUTPUT_STATUS" = "error" ]; then
    pass "rag-query tool output status = $RAG_OUTPUT_STATUS"
  else
    fail "rag-query tool output status = $RAG_OUTPUT_STATUS (expected ok or error)"
  fi
else
  fail "rag-query stage not found in run detail"
  info "Available stages: $(py "import json; d=json.loads('''$RUN_DETAIL'''); print([s.get('stageId') for s in d.get('agentStages',[])])" || echo "?")"
fi

# ── Step 10: Validate stage ordering ──────────────────────────────────────────
if [ "$EXPECT_SEARCH_STAGE" = "true" ]; then
  step 10 "Validate stage ordering (search-web → rag-query → dev-context → dev-answer)"
else
  step 10 "Validate stage ordering (rag-query → dev-context → dev-answer)"
fi

STAGE_ORDER=$(py "
import json, sys
d = json.loads('''$RUN_DETAIL''')
evts = d.get('events', [])
started = [e.get('stageId') for e in evts if e.get('type') == 'STAGE_STARTED' and e.get('stageId')]
print(json.dumps(started))
" || echo "[]")

SRC_IDX=$(py "import json; stages=json.loads('''$STAGE_ORDER'''); print(stages.index('search-web') if 'search-web' in stages else -1)" || echo "-1")
RAG_IDX=$(py "import json; stages=json.loads('''$STAGE_ORDER'''); print(stages.index('rag-query')  if 'rag-query'  in stages else -1)" || echo "-1")
CTX_IDX=$(py "import json; stages=json.loads('''$STAGE_ORDER'''); print(stages.index('dev-context') if 'dev-context' in stages else -1)" || echo "-1")
ANS_IDX=$(py "import json; stages=json.loads('''$STAGE_ORDER'''); print(stages.index('dev-answer')  if 'dev-answer'  in stages else -1)" || echo "-1")

ORDER_OK="true"
if [ "$EXPECT_SEARCH_STAGE" = "true" ] && [ "$SRC_IDX" -lt 0 ] 2>/dev/null; then
  ORDER_OK="false"
  fail "search-web stage not found (expected when SEARCH_ENABLED=true)"
fi
if [ "$RAG_IDX" -lt 0 ] 2>/dev/null; then
  ORDER_OK="false"
  fail "rag-query stage not found"
fi
if [ "$ORDER_OK" = "true" ] && [ "$CTX_IDX" -gt "$RAG_IDX" ] && [ "$ANS_IDX" -gt "$CTX_IDX" ] 2>/dev/null; then
  if [ "$EXPECT_SEARCH_STAGE" = "true" ]; then
    pass "Stage order: search-web($SRC_IDX) → rag-query($RAG_IDX) → dev-context($CTX_IDX) → dev-answer($ANS_IDX)"
  else
    pass "Stage order: rag-query($RAG_IDX) → dev-context($CTX_IDX) → dev-answer($ANS_IDX)"
  fi
else
  fail "Stage order incorrect or missing stages"
  info "Observed stage order: $STAGE_ORDER"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "─────────────────────────────────────────────────────────"
TOTAL=$((PASS+FAIL))
if [ "$FAIL" -eq 0 ]; then
  echo -e "  ${GREEN}ALL $PASS/$TOTAL checks PASSED${NC} — Capability Layer: VERIFIED"
  echo ""
  if [ "$SEARCH_ENABLED" = "true" ]; then
    echo "  DevAgent → Runtime → search.web → rag.query → dev-context → dev-answer ✓"
  else
    echo "  DevAgent → Runtime → rag.query → dev-context → dev-answer ✓"
  fi
else
  echo -e "  ${RED}$FAIL/$TOTAL checks FAILED${NC}, $PASS passed"
  echo ""
  echo "  Check pre-conditions:"
  echo "    Runtime:  RAG_TOOL_ENABLED=true"
  echo "    DevAgent: DEV_AGENT_RAG_DELEGATE_TO_RUNTIME=true"
  exit 1
fi
echo ""
