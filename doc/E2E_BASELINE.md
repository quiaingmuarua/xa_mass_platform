# E2E Baseline

Last updated: 2026-04-23

This is the short release-gate baseline for active-mainline E2E coverage.
Detailed inventory stays in [./INTEGRATION_TESTS.md](./INTEGRATION_TESTS.md).

## 1. What Counts As Real E2E

An active-mainline E2E test must, unless explicitly scoped lower:

1. start the real `xa-mass-dev-app` Spring Boot entry
2. use real HTTP for the lifecycle path under test
3. use the real active transport adapter when runtime dispatch/result write-back is part of the risk
4. assert real task/message state, not only mocks or listener calls

White-box fixtures are allowed for setup and fault injection, but not as a replacement for the path under test.

Current mainline note:

- today the Boot-shell E2E path validates both the current WebSocket adapter and the pull-style worker path
- pull-style shell coverage is currently represented by `PollingWorkerTaskFlowIntegrationTest`, `CrawlerPullWorkerSdkRegistrationIntegrationTest`, and `TransportChannelWiringIntegrationTest`
- WebSocket is still the mainline adapter for real push/callback gateway risks, but it is no longer the only accepted shell path

Fixture note:

- E2E tests may still use white-box fixtures for setup and fault injection
- prefer SDK capability entrypoints such as `MassSdkApplication.registerWorker(...)`, `registerWorkerContext(...)`, `replaceDefaultRules(...)`, and `createTask(...)` for new setup code
- use `addWorker(...)` and `addWorkerContext(...)` only when a test intentionally needs compatibility with core runtime models or historical fixture state
- current active E2E fixtures have eliminated direct `WorkerManager` and `RuleManager` setup writes; remaining direct manager mutation is limited to intentional `TaskManager` invariant/fault-injection scenarios

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
- polling/pull worker path can execute `create -> approve -> dispatch -> result -> terminal` without WebSocket push
- SDK-created worker resources can register as `OFFLINE`, connect through pull transport, poll work, submit result output, and disconnect back to offline
- targeted worker debug runs through normal `create -> approve -> assign -> dispatch -> result -> terminal`
- fixed-worker debug selection is carried by `Task.sharedConfig.targetWorkerId`, not a separate gateway control path
- same worker can own multiple contexts without overwrite
- releasing one context does not release sibling contexts
- worker/context is reusable after normal terminal completion
- worker/context is reusable after manual terminate

Control console:

- `/`, `/tasks`, and `/resources/workers` return the backend-hosted SPA shell through the real Boot entry
- `/status`, `/status/tasks`, `/status/workers`, `/status/rules`, and `/config` remain redirect aliases only

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
- policy interaction precedence

then acceptance requires both:

1. E2E coverage for the changed path
2. trace coverage for the critical transition

For policy interaction changes, also cover the touched pairwise interaction from [./engine/POLICY_INTERACTION_BASELINE.md](./engine/POLICY_INTERACTION_BASELINE.md).
