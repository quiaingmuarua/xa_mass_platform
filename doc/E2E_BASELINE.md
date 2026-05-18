# E2E Baseline

Last updated: 2026-05-14

Status: current global E2E baseline.

This is the short release-gate baseline for active-mainline E2E coverage.
Detailed suite inventory stays in [../xa-mass-server/README.md](../xa-mass-server/README.md).
Overall testing-system placement stays in [./TESTING_BASELINE.md](./TESTING_BASELINE.md).

## 0. Fast Intent

Use Boot-shell E2E when the real risk is:

- real host/runtime wiring
- representative assignment and result-convergence behavior through the server shell
- external worker parity across process, language, or adapter boundaries

Do not use Boot-shell E2E as the first home for:

- the full worker-selection competition matrix
- every scheduling/contention permutation
- projection-first lifecycle proof

E2E is the platform-viability proof surface, not the only or highest-value
proof of scheduling correctness. The full competition matrix belongs primarily
to engine acceptance/concurrency tests, while E2E keeps representative real-host
assignment, polling, routing, and result-convergence scenarios.

## 1. What Counts As Real E2E

An active-mainline E2E test must, unless explicitly scoped lower:

1. start the real `xa-mass-server` Spring Boot entry
2. use real HTTP for the lifecycle path under test
3. use the real active transport adapter when runtime dispatch/result write-back is part of the risk
4. assert real task/message state, not only mocks or listener calls

White-box fixtures are allowed for setup and fault injection, but not as a replacement for the path under test.

Current mainline note:

- today the Boot-shell E2E path validates the stable polling external-worker path plus the current websocket/socket realtime adapter paths
- pull-style shell coverage is currently represented by `PollingWorkerTaskFlowIntegrationTest`, `CrawlerPullWorkerSdkRegistrationIntegrationTest`, `ExternalWorkerPollingApiIntegrationTest`, and `TransportChannelWiringIntegrationTest`
- cross-language / cross-adapter worker parity is represented by `ExternalWorkerParitySuite`, covering Java and Node workers across polling, WebSocket, and socket adapters

Fixture note:

- E2E tests may still use white-box fixtures for setup and fault injection
- prefer SDK capability entrypoints such as `MassSdkApplication.registerWorker(...)`, `replaceDefaultRules(...)`, `createTaskShell(...)`, `appendTaskItems(...)`, and `executeTaskCommand(..., "SEAL")` for batch/file ingest setup code
- WorkerContext compatibility registration has been removed; E2E setup should prefer worker attributes, event bindings, transport presence, and runtime load proof
- current active E2E fixtures have eliminated direct `WorkerManager` and `RuleManager` setup writes; remaining direct manager mutation is limited to intentional `TaskManager` invariant/fault-injection scenarios

Proof-surface note:

- E2E is the preferred proof surface when the real risk is integrated lifecycle wiring, host/runtime interaction, transport interplay, or distributed edge behavior
- E2E is not where the full worker-selection competition matrix should live; keep engine-first scheduling proof for contention, redispatch, and contract-aware convergence
- compatibility projection may still be asserted as bounded residue, but it is not the primary proof surface for lifecycle correctness
- when E2E claims trace coverage, read canonical sink output through
  `xa-mass-trace` or the same query backend path rather than asserting raw log
  strings

## 2. Mandatory Release-Gate Scenarios

Core lifecycle:

- `create -> approve -> assign -> run -> complete`
- `create -> approve -> assign -> fail -> terminal`
- `reject -> approve`
- `reject -> BLOCKED` exposes `holdReason=REVIEW_REJECTED`
- `pause -> resume`
- `approve -> assign -> running -> terminate -> delete`
- `running -> pause -> callback -> terminal`
- `SESSION task -> complete current messages -> remain non-terminal -> explicit terminate`

Robustness:

- duplicate callback replay is idempotent
- late callback after manual terminal closure is ignored
- mixed results close with `MIXED_MESSAGE_RESULTS`
- task detail exposes shell aggregate fields such as `intakeStatus`; item-level terminal residue is not part of the public detail contract

Assignment and capacity:

- `READY` task with no current match is retried
- `minRequiredWorkerCount` gates `READY -> RUNNING`
- `batchSize=1` multi-round dispatch completes across rounds
- assignment skips dispatch if the task left `READY` during matching
- each dispatch creates attempt state that remains consistent with message projection
- retry creates a new attempt and re-queues the logical message without duplicating the compatibility message row
- `BATCH` lease expiry consumes retry budget as attempt loss; exhausted budget closes the item as failure rather than logical timeout
- representative multi-task assignment survives real server + SDK + engine wiring without double assignment
- representative worker scheduling/routing remains correct under the assignment scenarios carried by the server E2E suite

Worker scheduling and compatibility:

- worker attribute / routing-tag selection chooses an eligible worker
- worker can execute tasks without WorkerContext compatibility setup
- polling/pull worker path can execute `create -> approve -> dispatch -> result -> terminal`
- SDK-created worker resources can register as `OFFLINE`, connect through pull transport, poll work, submit result output, and disconnect back to offline
- external polling worker API can register a worker, mark it online, poll `TaskDispatchItem`, submit `TaskResultReport`, and return offline
- the runnable Node polling worker example can join through `/worker-api/v1/**`, surface capability in `/api/v1/catalog/*`, complete task work, and exit cleanly
- targeted worker debug runs through normal `create -> approve -> assign -> dispatch -> result -> terminal`, with fixed-worker selection carried by `Task.sharedConfig.targetWorkerId`
- legacy WorkerContext compatibility tests, where retained, must stay clearly
  named and must not be used as mainline scheduling proof
- worker/resource capacity is reusable after normal terminal completion
- worker/resource capacity is reusable after manual terminate

Control console:

- `/`, `/tasks`, and `/resources/workers` return the backend-hosted SPA shell through the real Boot entry
- `/status`, `/status/tasks`, `/status/workers`, `/status/rules`, and `/config` remain redirect aliases only

Audit:

- public `GET /api/v1/tasks/{taskId}` stays shell/aggregate-only
- task-state validation and `needsResolution=true` assertions run through explicit diagnostic surfaces, not public task detail payload

## 3. Change Rule

If a change touches:

- state transitions
- terminal convergence
- matching semantics
- retry semantics
- worker lock / capacity / resource release
- lifecycle API contracts
- policy interaction precedence

then acceptance requires:

1. engine scheduling/kernel coverage for the changed path
2. representative E2E coverage for the changed host/runtime path
3. trace coverage for the critical transition

For policy interaction changes, also cover the touched pairwise interaction from [../xa-mass-engine/POLICY_INTERACTION_BASELINE.md](../xa-mass-engine/POLICY_INTERACTION_BASELINE.md).

Trace coverage note:

- trace coverage means the scenario can be reconstructed from canonical
  `ExecutionEvent` output using `xa-mass-trace` commands such as `timeline`,
  `stats`, or `validate`, or using the same query backend path in-process
- raw log lines may still assist diagnosis, but they are not the release-gate
  proof surface for trace coverage

## 4. Relation To Local Kernel Tests

- E2E does not replace deterministic engine/transport invariant tests
- scheduling correctness should be proved engine-first; E2E should prove that the same behavior survives real host/runtime wiring
- local kernel tests should continue to prove retry, expiry, release, finality, and convergence ordering directly
- what should move upward into E2E/chaos/black-box is projection-first proof style for integrated or distributed behavior
