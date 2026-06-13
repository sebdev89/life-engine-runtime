# Sprint 2 — Runtime FAQ loop fix (BogaBot)

**Repo:** `life-engine-runtime`  
**Scope:** `business-chat.reply.v1` only — no UI, no BC service refactor.

## Problem

Follow-ups like *"¿Cómo es la primera consulta?"* after a legal complaint were classified as `greeting` and answered with a generic fallback, ignoring seeded FAQs.

## Fixes (R-1..R-3)

| ID | Change | File |
|----|--------|------|
| R-1 | FAQ overlap remaps `greeting`/`unclear` → `support` | `BusinessFaqMatcher`, `BusinessContextAgent` |
| R-2 | Deterministic FAQ reply before reply LLM | `BusinessReplyAgent` |
| R-3 | Sticky handoff when complaint appears in history | `BusinessHandoffService` |

## Verify

```bash
cd life-engine-runtime
./mvnw test -Dtest=BusinessFaqMatcherTest,BusinessHandoffServiceTest,BusinessChatReplyWorkflowTest
```

Manual: replay BogaBot thread → chip **Primera consulta** should return 30-minute meeting FAQ.
