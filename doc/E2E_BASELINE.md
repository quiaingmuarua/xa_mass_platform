# E2E Baseline

Last updated: 2026-04-19

This is the short release-gate baseline for active-mainline E2E coverage.
Detailed inventory stays in [./INTEGRATION_TESTS.md](./INTEGRATION_TESTS.md).

## 1. What Counts As Real E2E

An active-mainline E2E test must, unless explicitly scoped lower:

1. start the real `xa-mass-mock` Spring Boot entry
2. use real HTTP for the lifecycle path under test
3. use the real WebSocket gateway when runtime dispatch/result write-back is part of the risk
4. assert real task/message state, not only mocks or listener calls

White-box fixtures are allowed for setup and fault injection, but not as a replacement for the path under test.

## 2. Mandatory Release-Gate Scenarios

Core lifecycle:

- `create -> approve -> assign -> run -> complete`
- `create -> approve -> assign -> fail -> terminal`
- `reject -> approve`
- `reject -> BLOCKED` exposes `holdReason=REVIEW_REJECTED`
- `pause -> resume`
- `approve -> assign -> running -> terminate -> delete`
- `running -> pause -> callback -> terminal`
- `openEnded -> complete current messages -> remain non-terminal -> seal -> terminal`

Robustness:

- duplicate callback replay is idempotent
- late callback after manual terminal closure is ignored
- mixed results close with `MIXED_MESSAGE_RESULTS`
- task detail and message detail expose `intakeStatus` and item-level `finalReason`

Assignment and capacity:

- `READY` task with no current match is retried
- `minRequiredWorkerCount` gates `READY -> RUNNING`
- `batchSize=1` multi-round dispatch completes across rounds
- assignment skips dispatch if the task left `READY` during matching
- each dispatch creates attempt state that remains consistent with message projection
- retry creates a new attempt and re-queues the logical message without duplicating the `TaskMsg` row

Worker and context:

- worker-context attribute routing selects the right context
- stateless worker can execute tasks without routing-required context
- same worker can own multiple contexts without overwrite
- releasing one context does not release sibling contexts
- worker/context is reusable after normal terminal completion
- worker/context is reusable after manual terminate

Audit:

- `GET /status/api/tasks/{taskId}` exposes valid state-audit output
- `needsResolution=true` is visible when task/message state diverges

## 3. Change Rule

If a change touches:

- state transitions
- terminal convergence
- matching semantics
- retry semantics
- worker lock or context release
- lifecycle API contracts

then acceptance requires both:

1. E2E coverage for the changed path
2. trace coverage for the critical transition
