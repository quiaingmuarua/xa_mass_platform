# Task Runtime Owner Module And Starter SDK Convergence Roadmap

Status: completed selected serving-path convergence on 2026-07-01. Named
follow-up fault-proof gaps remain outside this roadmap completion, including
Redis process kill/failover, WebSocket/Socket serving-path network partition,
and broader transport/result infra-fault coverage.

TROM-0 inventory, the initial `xa-mass-task-runtime` semantic contract
skeleton, the first memory/Redis public-port adapter proofs, isolated
task-runtime starter loop-host plus non-serving append-to-claim proof, and
engine cutover-prep mappers now cover append item, worker reservation, policy
snapshot, dispatch binding, result apply command, result decision, progress
consumption. `TaskRuntimeServingLane` is now the starter-backed `EngineConfig`
serving path for append, runtime-ready discovery, claim, result ingest, lease
repair, final result read, and terminal convergence. The default backend is
memory, and `EngineConfig` can select the Redis task-runtime backend through
`xa-mass-task-runtime-starter-sdk` without routing the path through the old
`TaskWorkRuntime` / `TaskResultRuntime`. Server Boot profile routing is now cut
over for memory-local/durable-local backend selection, and
`ExternalWorkerPollingApiIntegrationTest#externalWorkerPollingApiCompletesTaskEndToEnd`
now proves a representative memory-local server -> polling worker -> result ->
terminal path through that serving lane.
`RedisRuntimeExternalWorkerPollingApiIntegrationTest#externalWorkerPollingApiCompletesTaskEndToEndWithRedisTaskRuntime`
proves the same task-runtime serving lane with a Redis task-runtime backend and
Redis polling delivery/endpoint-lease stores.
`RedisRuntimeNodePollingWorkerBlackBoxIntegrationTest#externalNodePollingWorkerCompletesTaskThroughRedisTaskRuntimeServingLane`
now proves the Redis task-runtime serving lane through a true external Node
polling worker process over public HTTP worker APIs.
`RedisRuntimeNodeWebSocketWorkerBlackBoxIntegrationTest#externalNodeWebSocketWorkerCompletesTaskThroughRedisTaskRuntimeServingLane`
proves the same Redis task-runtime serving lane through an external Node
WebSocket worker process and realtime adapter dispatch.
`RedisRuntimeNodeSocketWorkerBlackBoxIntegrationTest#externalNodeSocketWorkerCompletesTaskThroughRedisTaskRuntimeServingLane`
proves the same Redis task-runtime serving lane through an external Node Socket
worker process and raw socket adapter dispatch.
`RedisRuntimeNodePollingLeaseExpiryRedispatchBlackBoxIntegrationTest#externalNodePollingWorkerDroppedResultIsRecoveredByRedisTaskRuntimeLeaseExpiry`
now proves one Redis-backed distributed failure edge: an external Node polling
worker can take a dispatch and exit before result submission, leaving only the
task-runtime active lease; lease expiry then returns the message to scheduler
competition and a second external Node polling worker completes attempt 2
through the same serving lane. The test also runs the canonical trace sink and
analyzer `lease-expiry-redispatch`, requiring `LEASE_EXPIRED`,
`TASK_WORK_ATTEMPT_CLOSED`, and pre/post dispatch-binding evidence.
`RedisRuntimeNodeWebSocketLeaseExpiryRedispatchBlackBoxIntegrationTest#externalNodeWebSocketWorkerDroppedResultIsRecoveredByRedisTaskRuntimeLeaseExpiry`
now proves the same dropped-result recovery edge through realtime WebSocket
adapter dispatch: a first external Node WebSocket worker exits after receiving
the dispatch and before sending a result, task-runtime lease repair/retry
returns the message to competition, and a second external Node WebSocket worker
completes attempt 2 through the same serving lane. The test also runs analyzer
`lease-expiry-redispatch` over canonical trace output.
`RedisRuntimeNodeSocketLeaseExpiryRedispatchBlackBoxIntegrationTest#externalNodeSocketWorkerDroppedResultIsRecoveredByRedisTaskRuntimeLeaseExpiry`
now proves the same dropped-result recovery edge through raw Socket adapter
dispatch: a first external Node Socket worker exits after receiving the dispatch
and before sending a result, task-runtime lease repair/retry returns the message
to competition, and a second external Node Socket worker completes attempt 2
through the same serving lane. The test also runs analyzer
`lease-expiry-redispatch` over canonical trace output.
`RedisRuntimeNodePollingLateResultReplayBlackBoxIntegrationTest#externalNodePollingWorkerLateResultReplayDoesNotOverwriteRetriedRedisFinality`
now proves the polling split-process late-result replay edge: a first external
Node polling worker takes attempt 1, goes offline, submits its old result after
lease expiry, while a second external Node polling worker completes attempt 2;
the Redis task-runtime final result remains the second worker's attempt.
`RedisRuntimeNodeWebSocketLateResultReplayBlackBoxIntegrationTest#externalNodeWebSocketWorkerLateResultReplayDoesNotOverwriteRetriedRedisFinality`
and
`RedisRuntimeNodeSocketLateResultReplayBlackBoxIntegrationTest#externalNodeSocketWorkerLateResultReplayDoesNotOverwriteRetriedRedisFinality`
now prove the same late-result replay edge through realtime WebSocket and raw
Socket adapter dispatch. `RedisTaskRuntimeOwnerReconnectTest` proves Redis
task-runtime owner reconnect over the same namespace can recover an active
lease through expiry, retry, attempt 2 claim, and finality.
`RedisTaskRuntimeNetworkPartitionTest` proves the same public-port recovery
after a Redis network partition through a test-local TCP proxy.
`RedisRuntimeNodePollingNetworkPartitionRedispatchBlackBoxIntegrationTest#externalNodePollingWorkerDroppedResultIsRecoveredAfterRedisNetworkPartition`
proves the polling serving path through a Redis TCP proxy as well: Spring Boot
server, Redis task-runtime, Redis polling delivery/endpoint-lease stores, and
an external Node polling worker recover after the proxy is cut and restored.
Redis process kill/failover, WebSocket/Socket serving-path network partition,
and broader transport/result infra-fault coverage remain unproved. Server Boot assembly no longer
declares, exposes, or injects legacy `TaskWorkRuntime` / `TaskResultRuntime`
beans into the engine-starter serving path. Public old-runtime injection is also closed at the
starter/SDK boundary: `EngineConfig` no longer exposes public old-runtime
getters/setters, and `MassEngineBuilder`, `MassApplicationBuilder`, and
`MassSdk.EngineOptions` no longer accept direct `TaskWorkRuntime` /
`TaskResultRuntime` injection. `TaskFlowLoadModelRunner` no longer constructs a
local legacy runtime bundle and is guarded against reintroducing measured
old-runtime wrappers. `EngineConfig` no longer exposes package-private legacy
runtime fallback handles, no longer constructs runnable in-memory legacy
fallback truth, and now constructs `TaskManager` through the no-old-runtime
constructor instead of holding or passing disabled old-runtime sentinels.
`TaskManager` no-old-runtime constructors now hold no legacy runtime sentinel,
explicit old-runtime constructors have been deleted, and the migrated runtime
entrypoints now require `TaskRuntimeServingLane` instead of falling back to
`TaskWorkRuntime` / `TaskResultRuntime`. `TaskManager` no longer implements the
migrated runtime hot-path port types (`TaskAssignmentRuntimePort`,
`TaskLeaseMaintenancePort`, `TaskDispatchWakeupPort`,
`TaskRuntimeRecoveryPort`, or `TaskResultIngestPort`); those type surfaces are
owned by `TaskRuntimeServingLane` in the selected serving path. The migrated
runtime delegate methods on `TaskManager` that are not required by
`TaskLifecycleService` internal hooks have been deleted; `TaskManager` keeps
only the shell/query/command public surface and a small package-internal
lifecycle hook set. The old no-lane result helper surface
and local delayed retry/wakeup helper surface have been deleted from engine:
`TaskResultService`, `TaskResultVisibleFinalCommitter`,
`TaskDispatchRequestService`, `DelayedDispatchSchedule`, and
`LocalDelayedDispatchSchedule` no longer exist. Migrated serving-lane tests now
use the no-old-runtime constructor instead of local disabled old-runtime
helpers. The old `TaskRuntimeEnqueueOptionsResolver` append helper and its
`WorkEnqueueOptions` engine main-source dependency are also deleted; append
admission/backpressure now flows through the task-runtime append command path,
not the old runtime enqueue option resolver. The assignment claim port is also
cut over: `TaskAssignmentRuntimePort.claimReady`, `TaskManager.claimReady`, and
`SimpleTaskDispatchBinder` now use direct grouped-port claim parameters with `ClaimLeasePolicy`,
`WorkerReservationEvidence`, and `ClaimedWorkItem` instead of old
`WorkerClaimTarget`, `TaskWorkClaimOptions`, or `ClaimedTaskWork` DTOs.
Active lease maintenance is also cut over on the engine hot path:
`TaskLeaseMaintenancePort`, `LeaseExpireWatchdog`, and
`TaskResourceReleaseListener` now use task-runtime `ActiveLeaseRepairCandidate`
values instead of old `ActiveLeaseRecord` DTOs. Starter and embedded-SDK
diagnostic APIs now expose SDK-owned `TaskActiveLeaseSnapshot` values instead of
`ActiveLeaseRecord`; engine active-lease diagnostic/test projection now uses
task-runtime `ActiveLeaseRepairCandidate`; the old runtime API
`ActiveLeaseRecord` file has been deleted.
Starter and embedded-SDK diagnostic stats now expose SDK-owned
`TaskWorkStatsSnapshot` values instead of old `TaskWorkStats` DTOs.
Engine progress and terminal-policy mainline now consumes task-runtime
`TaskRuntimeProgressSnapshot` directly: `TaskStateRuntimePort`,
`TaskStateResolver`, `TaskStateValidator`, `TaskLifecycleService`,
`TraceEventLogger`, and `TaskTerminalPolicy` no longer import old
`TaskWorkStats`, and the transitional `TaskRuntimeProgressSnapshotMapper` has
been deleted.
Task-runtime `FinalResultRow` now preserves worker/batch evidence after active
lease removal so bounded final-result reads do not lose the selected worker.
Task-runtime `FinalResultReadRequest` / `FinalResultWindow` now carry the
`afterSeq`, `nextAfterSeq`, and `totalVisible` read-window contract directly;
memory and Redis runtimes expose the same seq window plus point lookup through
`getFinalResultByMessageId`. Engine, starter, and embedded-SDK result-read
surfaces now map task-runtime `FinalResultRow` / `FinalResultWindow` to
SDK-owned `TaskResultWindowSnapshot` / `TaskWorkFinalSnapshot` values instead
of exposing or reconstructing old `TaskResultRuntimeRow` / `TaskResultWindow`
DTOs.
`TaskManagerLifecycleTest`
now proves lifecycle append/claim behavior through `TaskRuntimeServingLane` over
the new task-runtime memory adapter, and the
obsolete test-side `EngineExample` class that constructed the old memory runtime
pair has been deleted. `TaskSchedulingTestHarness` now drives scheduling,
gating, contention, policy, worker-state, and redispatch tests through
`TaskRuntimeServingLane` over the new task-runtime memory adapter through the
no-old-runtime `TaskManager` constructor. `TaskRedispatchCompetitionTest` now proves stale
expired-lease rejection through task-runtime `RuntimeResultFact` convergence, not the old
`TaskWorkResult` DTO. `TaskFlowLoadModelRunner` no longer carries a synthetic
stale-result hook; stale/late result proof lives in the dedicated engine
stale-lease proof and external worker late-replay E2Es. Manual
chaos/perf/soak diagnostic snapshots now use SDK-owned task runtime snapshots
instead of old runtime DTOs, and `TestingTaskRuntimeOldPathClosureGuardTest`
prevents manual testing runners from reintroducing old task-runtime owner APIs.
Engine
append selected-path routing is now explicit
serving-lane-first code instead of a hidden function-pointer writer or
append-admission flag, and the package-private single-item runtime ingress
helpers have been removed so runtime item creation stays behind the batch append
boundary. `TaskRuntimeRecoveryPortTest` now proves runtime-ready recovery
through `TaskRuntimeServingLane` scheduler discovery and task-runtime backlog
truth instead of a test-only `TaskWorkRuntime.readyTaskIds()` override.
`TaskKernelLifecycleTest` now proves shell/intake/status/delete/termination and
append-backpressure lifecycle behavior through `TaskRuntimeServingLane`, not
old memory runtime constructors or
`getTaskWorkRuntime()` / `getTaskResultRuntime()` getters.
`SimpleTaskDispatchBinderTest` now proves assignment claim, dispatch binding,
lease evidence, submit-failure compensation, and worker release behavior through
the same serving lane instead of direct old memory runtime construction. Old
`TaskResultRuntimeConvergenceTest` now proves duplicate/late result idempotence,
retry-to-ready rediscovery, non-retry final failure, and lease-timeout finality
through `TaskRuntimeServingLane` result/finality/repair ports instead of old
`TaskResultRuntime` staged callback repair, repair candidates, or direct memory
runtime construction. `TaskResultConcurrencyConvergenceTest` now proves
duplicate result, result-vs-expiry, retry-vs-success, and different-message
result apply concurrency through the same serving lane instead of old
`TaskWorkRuntime` / `TaskResultRuntime` DTOs, old memory runtime construction,
or old task result repair barriers. The old production `TaskResultRepairPump`
background class is now deleted, so the selected path cannot accidentally start
a second old result-repair lifecycle. The whole old `TaskResultService`
result-ingest/expiry/failure helper path and its visible-final committer have
also been deleted and guarded; migrated `TaskManager` result and failure
entrypoints now require the serving lane. The local old delayed retry/wakeup
classes used by the previous result helper path are deleted as well. The old
`mass-runtime-redis` ->
`xa-mass-engine` proof bridge `RedisRuntimeTraceIntegrationTest` is deleted;
`mass-runtime-redis` no longer carries test-scope `xa-mass-engine` /
`mass-storage-memory` dependencies just to construct the old engine/runtime pair.

This roadmap creates a task-runtime owner module before any large engine
cleanup. The target is a non-best-effort task runtime that owns logical work
convergence while keeping physical runtime storage and all thread/bootstrap
assembly outside the semantic owner module.

Read with:

- [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [platform_infra/README.md](../platform_infra/README.md)
- [sdk/README.md](../sdk/README.md)
- [score-band-task-runtime-redis-shape.md](../architecture/score-band-task-runtime-redis-shape.md)

Prerequisite:

- [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
  is the completed prerequisite boundary for this roadmap. It creates
  `xa-mass-engine-starter` as the containment module for current
  engine-facing assembly and records the approved starter surfaces, temporary
  value-contract exceptions, and guards in
  [ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md).
- TROM must start from those engine-starter handles instead of rediscovering
  broad `embedded-sdk -> engine` dependency/import leakage. The prerequisite
  cleanup is still best-effort containment, not a new runtime owner, and it
  must not introduce listener-first or event-bus runtime coordination as the
  path into TROM.

## Current Code Observations

- Current task work and result runtime contracts live in
  `xa-mass-task-runtime`; the old `platform_infra/mass-runtime-api`
  `TaskWorkRuntime` / `TaskResultRuntime` API family has been deleted.
- Current memory and Redis task-runtime implementations live in
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`; the old
  `InMemoryTaskWorkRuntime` / `InMemoryTaskResultRuntime` and
  `RedisTaskWorkRuntime` / `RedisTaskResultRuntime` implementations have been
  deleted from `mass-runtime-memory` and `mass-runtime-redis`.
- Current engine code still mixes shell lifecycle, scheduling orchestration,
  dispatch binding, progress/terminal convergence, and compatibility
  projections. The selected runtime hot-path type surface has moved off
  `TaskManager` and onto `TaskRuntimeServingLane`, but surrounding engine loops
  and result-row projections still need closure or proof before the roadmap is
  complete.
- Current engine cut-in sites must be treated as old port/method paths that
  need explicit closure plans: `TaskLifecycleService` / `TaskManager` for
  append and shell counters, `SimpleTaskDispatchBinder` for claim/dispatch,
  `TaskResultIngestPort` / serving-lane result entrypoints for result finality
  and repair, and `EngineRuntimeKernel` for assignment, runtime-ready dispatch,
  lease repair, and result repair loops.
- Current embedded SDK / starter assembly owns much of the process bootstrap
  and runtime thread creation.
- The target Redis shape in
  `architecture/score-band-task-runtime-redis-shape.md` is a physical
  implementation reference. It must not become the public task-runtime module
  contract.
- The older Redis task-runtime roadmap described a Stream / at-least-once
  direction. This roadmap supersedes that direction as the execution entry;
  any retained Redis-shape note must be re-approved under the non-best-effort
  task-runtime boundary below.

## Owner Review

Task runtime belongs to a dedicated task-runtime owner module. It owns the
logical work/result convergence protocol:

```text
accepted append
ready backlog visibility
scheduled retry visibility
active lease ownership
result apply
retry/finality
duplicate and late result handling
lease-timeout repair liveness
task-local final result read semantics
discard cleanup
```

The task-runtime owner module must not own Redis keys, Redis data structures,
Lua scripts, Stream/Pending Entry mechanics, JDBC tables, storage schema,
process threads, Spring beans, embedded SDK facade behavior, transport adapter
loops, or server HTTP routes.

Infra implementation modules own physical storage adapters for the task-runtime
contracts. They may use Redis, memory, Lua, ZSET/LIST/HASH, codec choices, or
future storage primitives, but those choices must stay behind task-runtime
ports and contract tests.

The task-runtime starter SDK owns process assembly and thread lifecycle. It may
create scheduled loops, repair workers, pumps, bootstrap objects, and external
integration wiring. It must not become the owner of task runtime state
transitions.

Engine remains a caller during migration. It may orchestrate task shell policy,
scheduling plane decisions, worker selection, assignment, and terminal policy,
but it should stop owning per-message runtime state. During strangler slices,
engine can adapt old callers to the new task-runtime ports; it must not keep a
second live task-item lifecycle truth.

Transport remains best-effort assigned delivery. It may consume already claimed
dispatch payloads and submit result ingress, but it must not own retry,
finality, lease repair, or task result reliability.

## Boundary Decision

Create three separate module roles:

```text
task-runtime owner module
  semantic contracts, state-machine commands and outcomes, invariants,
  contract-test fixtures, and no physical storage/thread/bootstrap code

platform_infra task-runtime implementation modules
  memory and Redis adapters for the task-runtime contracts
  platform_infra/mass-task-runtime-memory
  platform_infra/mass-task-runtime-redis
  physical key/value/codec/Lua details hidden from callers

task-runtime starter SDK
  runtime bootstrap, thread creation, loop scheduling, external ingress/egress
  wiring, lifecycle handles, and host-facing configuration
```

The semantic owner module is a top-level runtime module:

```text
xa-mass-task-runtime
```

It is parallel to `xa-mass-worker-runtime`, not a `platform_infra` module.
`platform_infra` may host memory/Redis implementation adapters, but it must not
own task-runtime protocol semantics.

The project runtime taxonomy is:

```text
xa-mass-task-runtime
  task item/result convergence runtime

xa-mass-worker-runtime
  worker lifecycle/resource/scheduling-evidence runtime

transport/transport_runtime
  best-effort assigned-delivery executor runtime
```

The task-runtime starter SDK path is:

```text
sdk/xa-mass-task-runtime-starter-sdk
```

This follows the existing `sdk/xa-mass-*` naming pattern and keeps the
ownership clear: SDK/starter assembly, not runtime state owner. TROM-0 still
records the Maven artifact name and final dependency graph, but it does not
re-open the path decision unless implementation finds a concrete repo-layout
blocker.

## Target Module Shape

| Module | Role | May depend on | Must not own |
| --- | --- | --- | --- |
| `xa-mass-task-runtime` | task runtime protocol owner, state-machine contracts, command/outcome values, contract-test suite | base value contracts, kernel SPI values that are explicitly allowed, test fixtures in test scope | Redis keys, memory maps as public shape, Lua, Stream/PEL, Spring, threads, engine implementation, transport implementation, SDK facade |
| `platform_infra/mass-task-runtime-memory` | in-memory implementation for local/dev and contract proof | task-runtime semantic module, narrow infra test helpers | task semantics beyond implementing ports, SDK/startup, engine orchestration |
| `platform_infra/mass-task-runtime-redis` | Redis implementation of the task-runtime ports | task-runtime semantic module, Redis client, codec helpers, low-level Redis keyspace internals | public task-runtime contracts, starter threads, engine scheduling, transport delivery, server HTTP |
| `sdk/xa-mass-task-runtime-starter-sdk` | process bootstrap and thread/lifecycle owner for task runtime | task-runtime semantic module, chosen infra implementation modules, engine/transport ports only as host integration | task item lifecycle truth, Redis key layout, server HTTP contract |
| `xa-mass-engine` | strangler caller during migration; eventual task shell/scheduling/result orchestration only | task-runtime semantic ports, starter-owned runtime handle as needed | per-message queue/lease/retry truth, physical runtime storage, task-runtime threads |

`TaskResultRuntime` converges into `xa-mass-task-runtime` as a logical
sub-contract. The module may keep separate `work` and `result` packages/ports,
but the owner is task-runtime because duplicate/late result handling, retry
exhaustion, finality, final result read, and result-side repair are part of the
same non-best-effort convergence boundary.

`platform_infra/mass-runtime-api` now remains as worker-runtime/shared SPI only.
Do not reintroduce old `TaskWorkRuntime` or `TaskResultRuntime` APIs as a
compatibility source; the end state must keep task-runtime ownership in
`xa-mass-task-runtime`.

## Mechanism Model

The new module is designed around five mechanisms, not a one-to-one copy of
the old engine ports:

| Mechanism | New runtime responsibility | Main old ports closed or narrowed |
| --- | --- | --- |
| Intake / Append Commit | accepted item identity, all-or-rejected batch append, ready backlog frame creation; optional dirty/wakeup hint only | `TaskCommandPort.appendTaskItems*`, old `TaskWorkRuntime.enqueue` path |
| Task Scheduler / Lane Acquire | scheduler-owned task-level eligibility discovery, lane scoring, due lane acquisition, runtime gate/fence validation, dispatchable-lane recovery | `TaskDispatchWakeupPort`, `TaskRuntimeRecoveryPort`, old `readyTaskIds` recovery |
| Worker Reservation Then Claim | consume worker-runtime reservation/admission evidence, convert ready frame to active lease, release reservation on rejected claim | `TaskAssignmentRuntimePort.claimReady`, dispatch compensation hooks |
| Result Apply / Finality Outcome | result callback application, retry/finality, duplicate/late/stale classification, compact outcome facts | `TaskResultIngestPort`, old `TaskResultService` result helper path now deleted |
| Active Lease Repair | discover active tasks, bounded scan active leases, expire through the same result/finality mutation path | `TaskLeaseMaintenancePort`, `LeaseExpireWatchdog`, result repair residue |

The old interfaces close by port; the new module is shaped by mechanisms. This
keeps closure testable without letting current engine interfaces define the new
task-runtime contract.

## Runtime Guarantee Boundary

Hard commitments:

- An accepted item has one runtime owner before append returns, subject to the
  configured storage durability profile.
- Append does not currently guarantee caller-level duplicate suppression. If an
  append response is lost and the caller retries without a stable dedupe key,
  duplicate logical items may be accepted.
- Runtime-owned accepted item identity is still idempotent. Replaying the same
  accepted `taskId + messageId` inside the runtime must not create a second
  logical item. API-level duplicate suppression through caller idempotency keys
  remains a later optional feature.
- A claimed item remains recoverable until result, retry, finality, or discard.
- Result apply, retry, finality, and duplicate/late handling are idempotent by
  `taskId + messageId + attempt evidence`.
- Final result rows are runtime-retained read state, not a durable public
  ledger. A terminal task may keep final results for a bounded retention window,
  with one day as the initial target, then cleanup may remove them.
- Active lease repair is eventually discoverable. Timeout timing may be
  best-effort, but the ability to find and repair active leases is not
  best-effort.
- Redis node-loss durability is only claimed when the selected Redis durability
  profile actually provides it.

Redis durability profile decision:

- The default Redis profile proves runtime transition no-loss for acknowledged
  mutations, not strict Redis-node-loss zero data loss.
- A strict durability profile may be added later, but it must explicitly require
  the Redis persistence/replication acknowledgement policy it depends on, such
  as AOF/fsync and optional `WAIT` behavior, and must fail closed or report
  degraded mode when the environment does not satisfy it.
- Async replica acknowledgement must not be documented as zero data loss.

Serving active-lease cutover gate:

- An append-to-claim path may stop at active lease creation only as a
  non-serving proof. It must not close or route a production entry while the
  new lease lacks a result/retry/finality/repair owner.
- Any production-reachable migrated path that creates a new task-runtime active
  lease must include result apply, retry/finality, and active-lease repair in
  the same slice, or prove the old result/repair callers delegate to the new
  task-runtime owner without writing a second item truth.
- Transport delivery may remain best-effort. This gate is about task-runtime
  active-lease convergence, not transport reliability.

Best-effort commitments:

- Exact timeout moment.
- Exact retry recheck timing.
- Exact fairness across tasks.
- Exact cleanup timing after terminal/discard.
- Transport result ingress delivery before task-runtime-owned retry/timeout
  compensation.

Pre-migration engine-owned retry/timeout compensation is old path residue. It
must be classified in the Old Port Closure Matrix before the affected path
migrates.

The key rule is:

```text
timing may be best-effort; convergence and discoverability may not.
```

Lease repair cost decision:

- The first repair shape uses task-level active-lease discoverability, such as
  an active task registry plus bounded scan of task-local active leases.
- Exact lease-expiry ordering is not part of the default contract.
- Per-lease expiry ZSETs, task-local earliest-expiry hints, or precise timeout
  wakeup indexes are strategy upgrades. They require a later policy/proof that
  accepts the runtime cost.

## Append Admission And Commit Boundary

Append crosses two owners and must keep that boundary small:

- embedded SDK, server, or other callers may do caller-local request validation
  and batch-size checks, but they do not become the intake truth owner;
- engine/task shell owns low-cost defensive intake validation, such as task
  existence, sealed/terminal/intake-window rejection, and configured
  `maxAppendBatchSize`;
- task-runtime owns accepted item identity, runtime enqueue/no-loss for the
  accepted batch, runtime replay idempotency for `taskId + messageId`, and
  ready backlog truth;
- starter or engine assembly may call both owners during migration, but it does
  not own either side's truth.

Append must not become the main consistency project. First-version batch append
is deliberately all-or-rejected:

- `all accepted`: every item in the batch has runtime ownership and ready
  backlog truth;
- `rejected before runtime ownership`: intake/admission failed before any item
  was accepted.

First version does not provide caller idempotency keys, classified partial
append, or a heavy half-commit recovery protocol. If response emission,
aggregate counter update, or wakeup fails after runtime acceptance, the accepted
batch must remain discoverable from task-runtime ready truth. Any later counter
or receipt reconciliation must be owner-local and bounded; it must not require
re-appending duplicate work or adding a bridge that writes second runtime truth.

## Scheduler Discovery First Version

Append intake does not update task-level lane score. The first scheduler
discovery mechanism is task-level and weakly consistent:

- append may emit a best-effort dirty/wakeup hint that carries only `taskId`;
- scheduler owns dirty backlog discovery, gate checks, lane scoring, and due
  acquisition;
- scheduler validates task shell/gate/policy state and ready backlog existence
  before claim;
- claim and result/retry/repair mutations may refresh task-level eligibility
  after they change backlog, active lease, retry, or gate facts;
- if a dirty hint or wakeup is lost, bounded scheduler recovery must eventually
  rediscover accepted ready backlog.

The public contract is not a Redis data-structure contract. Memory and Redis
implementations may use a dirty set, queue, task-level ZSET, or equivalent
bounded-discovery primitive behind the task-runtime ports, but they must not
create one scheduler entry per ready item.

## Aligned Implementation Decisions

These decisions are no longer open blockers for the first implementation
slices unless code proof exposes a concrete contradiction:

- The first migrated serving candidate is append -> scheduler-owned discovery /
  lane acquire -> worker-runtime reservation/admission -> task-runtime claim ->
  task-runtime result/retry/finality and active-lease repair. A path that stops
  at claim remains non-serving proof only.
- First-version append is all accepted or rejected. SDK/server callers may do
  request-local validation and max-batch-size checks, and engine may keep
  low-cost defensive validation, but task-runtime owns accepted item identity
  and ready backlog truth.
- Caller idempotency keys, classified partial append, and heavy append
  half-commit recovery are out of the first contract. Runtime replay
  idempotency by `taskId + messageId` remains required.
- Append writes ready backlog truth and may emit a best-effort dirty/wakeup
  hint. It does not update task lane score, task shell status, or per-item
  scheduler entries. Scheduler/recovery owns task-level score refresh.
- Ready backlog length and task shell status are separate facts. A large ready
  LIST is not a task status model and must not create one scheduler record per
  item.
- Lua, Redis transactions, or an equivalent compare-and-swap mechanism are used
  only for multi-value state transitions that need a fence: claim, result
  apply, lease repair, gate/epoch changes, discard/terminal cleanup, or append
  variants that add idempotency/backlog-cap/meta compare-and-write. Plain first
  append does not need to rewrite lane score through Lua.
- Memory and Redis implementations must implement the public task-runtime
  ports. The semantic module must not hide a production-like pure-memory runtime
  path that bypasses those ports.
- Internal fields inside the new module can stay pragmatic during migration.
  Field cleanup is required only when a field leaks across module boundaries,
  creates a false public contract, or reopens an old owner path.

Remaining owner decisions that may affect later slice boundaries:

- Whether Redis process kill/failover, WebSocket/Socket serving-path network
  partition, and broader transport/result infra-fault proof are required before
  roadmap completion. Redis network partition recovery is now covered at the
  task-runtime public-port level through `RedisTaskRuntimeNetworkPartitionTest`
  and at the Redis Node polling serving-path level through
  `RedisRuntimeNodePollingNetworkPartitionRedispatchBlackBoxIntegrationTest`;
  Redis Node polling/WebSocket/Socket success and dropped-result lease-expiry
  redispatch are trace-observed through canonical trace output; late-replay
  coverage is still black-box/server-result proof.
- Whether strict Redis durability is required beyond acknowledged Redis
  mutations, and which persistence/replication policy is mandatory for it.
- Whether `DUE_TIME` message-level retry visibility is required in the first
  production slice or remains a later profile upgrade after `FAST_READY`.
- Whether manual chaos/diagnostic snapshot proof needs stronger distributed-edge
  semantics beyond its current SDK-owned diagnostic snapshot support. Old
  runtime API/implementation vocabulary has been deleted from the manual runner
  source path and guarded against return. Engine/starter/SDK
  result-read leakage over old
  `TaskResultRuntimeRow` / `TaskResultWindow` is already closed and guarded.
  Engine progress/terminal-policy mainline no longer consumes old
  `TaskWorkStats`; it consumes task-runtime `TaskRuntimeProgressSnapshot`
  directly, and the old progress mapper is deleted and guarded.
  Shared
  scheduling harness tests, idle-close/resource-release fixtures, and the
  `TaskRedispatchCompetitionTest` stale expired-lease proof are no longer
  old-runtime residue. The old no-lane `TaskResultService` / visible-final
  committer path and local delayed-dispatch helper path are deleted.
  `EngineConfig` package-private fallback
  handles, `TaskFlowLoadModelRunner` legacy runtime construction, and the
  old-runtime `EngineExample` class are no longer part of the legacy runtime
  residue. `TaskFlowLoadModelRunner` also no longer keeps a synthetic
  stale-result hook; stale/late result proof is carried by the dedicated
  engine stale-lease proof and external worker late-replay E2Es.
  Package-private single-item runtime ingress helpers are also closed;
  new item writes must pass through batch append/admission.
  `TaskRuntimeRecoveryPortTest` no longer owns a fake ready-task override over
  old `TaskWorkRuntime`; recovery proof now uses task-runtime backlog truth via
  `TaskRuntimeServingLane` scheduler discovery.
  `TaskKernelLifecycleTest` no longer constructs runnable old memory runtimes
  or calls package-private old runtime getters; shell/intake/delete and
  append-backpressure proof runs through `TaskRuntimeServingLane`.
  `SimpleTaskDispatchBinderTest` no longer constructs old memory runtimes or
  reads old runtime getters; assignment/dispatch binder proof now runs through
  `TaskRuntimeServingLane`.

## First Command Field Decisions

Engine/task shell may parse current fat policy objects, but task-runtime public
ports receive only narrow command values:

| Command value | First-version fields |
| --- | --- |
| `AppendAdmissionPolicy` | `maxAppendBatchSize`, optional `maxReadyBacklogItems` when the profile enforces backlog cap |
| `SchedulerEligibilityPolicy` | `runtimeGate`, `dispatchLane`, `nextEligibleAtMillis`, `positiveMatchDelayMillis`, `emptyMatchDelayMillis`, `contentionRecheckDelayMillis` |
| `ClaimLeasePolicy` | `maxItems` as total item claim limit, `leaseMillis`, `attemptPolicyVersion`, `expectedRuntimeEpoch` |
| `RetryPolicySnapshot` | `retryMode`, `maxRetryCount`, `retryDelayMillis`, `retryPolicyVersion` |
| `ResultFinalityPolicySnapshot` | `retryExpiredLeaseFromAnyActiveState`, `expiredLeaseFinalizesAsFailure`, `finalResultRetentionMillis` |
| `RuntimeEpoch` | `taskId`, `epoch`, optional terminal/discard fence token |

These names may be refined during implementation, but the boundary is fixed:
task-runtime public ports must not accept `Task`, `ResolvedTaskSchedulingPolicy`,
`TaskRuntimeProfile`, or engine resolver classes.

## Result Finality And Terminal Split

Task-runtime owns message-level finality. Engine owns task aggregate terminal
policy. The split is:

- task-runtime applies result, consumes retry budget, closes or reopens the
  message attempt, records visible final rows, handles duplicate/late callback
  classification, and emits compact outcome facts;
- engine consumes outcome facts and task-runtime progress snapshots to update
  task progress, trigger terminal policy, publish trace/review/projection
  events, and run task-shell aggregate convergence;
- server review and trace materialization are downstream projections, not
  runtime result truth.

Task-runtime outcome facts should be narrow and engine-neutral, for example:

```text
AttemptClosed
LogicalFinal
ProgressDirty
TerminalCandidate
ResultDuplicateOrLate
ResultRejected
TaskRuntimeProgressSnapshot
```

The exact names may change during TROM-1, but the boundary may not: task-runtime
must not import engine, trace, or server review code, and engine must not keep a
second result-finality truth after a production path moves.

## Default Cost Policy

The default task-runtime path is optimized for high-cardinality task items.
It must support million-item tasks without turning every raw item into a heavy
runtime object, Redis key, durable ledger row, or view DTO.

Default behavior should pay only for high-ROI correctness:

- accepted-item ownership;
- claim exclusivity;
- active-lease recoverability;
- idempotent result/retry/finality;
- bounded final result read retention;
- bounded liveness indexes needed to find active leases or due retry work.

High-cost features are policy opt-ins, not default taxes:

- exact per-message retry due time;
- exact active-lease timeout wakeup;
- strict fairness or per-project quota fairness;
- caller-level append dedupe;
- long-term result archive;
- per-message attempt timeline queries;
- rich per-message operator views on the hot path.

If a task type needs one of these features, the task policy must name it and
the implementation slice must prove the ROI and bounded cost. Do not add a
global per-message index, history table, result ledger, or scan loop merely to
make an edge-case guarantee easier to explain.

## Interface DTO And View Boundary

The new task-runtime module must not copy the current heavy interface, DTO, or
view shapes into public or cross-module contracts by default. Current runtime
and engine views are migration sources, not target contracts.

Semantic task-runtime interfaces that cross module boundaries should expose
only:

- command inputs owned by the caller, such as task id, payload or payload ref,
  requested policy id/version, and optional caller message id;
- runtime-owned handles or evidence, such as message id, attempt number,
  lease/reservation token, retry count, and final sequence;
- compact result/read rows that are needed by the runtime contract;
- opaque payload bytes/refs rather than parsed business fields;
- narrow diagnostics summaries, not per-message view aggregates on hot paths.

Do not expose physical storage names, Redis value shapes, transport envelopes,
server review rows, trace event payloads, or old engine view objects through
the task-runtime semantic module. Rich views belong to server/review/trace
materialization and may lag runtime truth.

This is not a module-internal field-cleanup mandate. Internal implementation
records, package-private DTOs, and test fixtures may carry extra fields during
the first migration if they do not cross module boundaries, become public
contracts, or force other owners to understand task-runtime internals.

## First Real Path Proof Priority

The first production-grade proof is not server view/API completeness and not
transport/result convergence. It is one scheduling path that proves new
task-runtime ready truth can enter assignment competition and become an active
lease through worker-runtime admission:

```text
appendBatch all accepted
  -> task-runtime scheduler discovers task-level eligibility
  -> task-runtime lane acquire / due check
  -> worker-runtime select / reserve / admit
  -> task-runtime claim with worker reservation and runtime epoch fence
```

The integration surface must stay narrow:

- task-runtime exposes append outcome, scheduler/lane-acquire outcome, claim
  preconditions, claimed work, and attempt evidence needed for lease ownership;
- worker-runtime exposes only selected worker, admission/reservation, reservation
  token, and dispatch-target evidence required by the chosen path.

Transport assigned delivery and result apply/finality are the next evaluation
gate. If implementation shows they can close without large hidden cross-module
risk, extend the path through transport/result in TROM-5. If not, record the
transport/result closure gap and split the smallest follow-up; do not use a
bridge that writes second task-runtime truth.

- transport accepts an already assigned delivery request and returns delivery
  outcome or best-effort failure evidence when the transport gate is enabled;
- server view APIs consume projections after runtime acceptance. They are
  important product surfaces, but they are secondary proof for this roadmap.

If a view/API needs extra fields, add them to server/review/trace materialization
unless the field is required by task-runtime convergence, worker-runtime
admission/reservation, or transport assigned-delivery correctness.

Task-runtime claim must not create an active lease before a concrete worker
reservation/admission decision exists. A claim with stale runtime epoch,
missing worker reservation token, or mismatched dispatch-target evidence must be
rejected without making an unbound active lease.

## Non-Goals

- No rewrite of task shell/control-plane storage in the first task-runtime
  slices.
- No server HTTP route or public SDK response redesign.
- No requirement that server view/API parity blocks the first real path proof.
- No transport reliability ownership.
- No classified-partial append or caller idempotency key in the first append
  contract.
- No heavy append half-commit recovery protocol in the first append contract.
- No worker-runtime score-band slot redesign.
- No public Redis key contract.
- No thread creation inside the task-runtime semantic module.
- No Spring component scanning in the task-runtime semantic module.
- No compatibility aliases for superseded internal task-runtime paths once
  in-repo callers move.
- No dual live task-item lifecycle truth between old engine port/method paths
  and new task-runtime paths.
- No use of trace/review rows as runtime acceptance, retry, finality, or lease
  repair truth.
- No copy-forward of current heavy engine/runtime DTOs or view objects as the
  new task-runtime public contract.
- No module-internal field cleanup as a first-slice goal unless the fields leak
  into a cross-module contract or re-open an old owner path.
- No default high-cost consistency feature unless a task policy names it and a
  focused proof shows the ROI.

## Do Not Start With

Do not start by wiring new starter threads into the current engine. That creates
more process behavior before the runtime state machine is proven.

Do not start by implementing Redis keys. The first executable proof is the
semantic state machine and memory/Redis contract parity inside the new
task-runtime module boundary, not physical storage.

Do not start by deleting old engine lifecycle code. First create the new owner,
prove it, route one narrow caller path through it, then remove the old path.

Do not create a facade that forwards to current `TaskWorkRuntime` and call that
the new task-runtime module. The new module must own the convergence protocol,
or it is only another wrapper.

Do not implement the first real path as `task-runtime claim -> worker selection`.
Worker selection/reservation/admission must happen before task-runtime claim, or
task-runtime is forced to own unbound active leases or to reverse-drive worker
selection.

Do not implement a new mechanism path for a production entry unless the old
port/method shutdown is already named. If the shutdown path is unclear, first
converge the old engine mechanism until it can be bypassed, disabled, deleted,
or guarded for the named port/method set.

## Execution Discipline

Every executable TROM slice follows this order:

```text
1. converge the old mechanism if the port/method shutdown path is unclear
2. write the new task-runtime owner path
3. verify the new path with focused owner and cross-boundary proof
4. close the old port/method path for the migrated production entry
```

Old-mechanism convergence is allowed only when it makes shutdown possible. It is
not a general cleanup lane. A convergence slice must name the old writer, loop,
surface, or DTO path that will become closable after the slice.

New functionality must include a predeclared old port/method closure plan before
code lands. The plan must say whether the old path is deleted, disabled for the
named production entry, bypassed behind a guard, or split into a separate
legacy-convergence roadmap because the shutdown mechanism is too tangled for the
current slice.

Verification must prove both sides:

- the new path satisfies the task-runtime owner invariant; and
- the old path cannot still write, repair, claim, or publish the same runtime
  truth for the migrated port/method set or production entry.

If a slice cannot explain how the old path closes, it is not ready to implement
new task-runtime behavior. Either narrow the port/method set or create a
separate old-mechanism convergence roadmap before adding the new path.

## Relationship To Existing Roadmaps

Any retained or restored Redis task-runtime shape roadmap is subordinate to this
roadmap. It may be used only as a lower-level implementation reference after the
semantic owner is accepted. A Stream / at-least-once direction conflicts with
the current non-best-effort task-runtime goal and must not be executed as-is.

Task shell/model split is out of scope for this roadmap. A future shell/model
roadmap may own fat task shell cleanup, but TROM does not depend on a separate
active shell roadmap. TROM only touches shell validation, aggregate
reconciliation, and policy snapshot ports when a task-runtime path needs them.

Broader embedded SDK/server runtime assembly cleanup is a future follow-up, not
a TROM prerequisite. This roadmap creates a task-runtime-specific starter SDK
because task-runtime threads and external ingress/egress must not live in the
semantic module. TROM-0 must still define how that starter relates to
`xa-mass-engine-starter` and `sdk/xa-mass-embedded-sdk`; it must not rely on a
nonexistent embedded-runtime split roadmap to settle startup ownership.

There is no separate task-runtime API extraction roadmap. Engine DTO/import
cleanup is TROM residue work and should happen only when it supports a named
old port/method closure or guards a migrated task-runtime path. Do not create
`xa-mass-task-runtime-api` as an escape route beside the old engine path.

## TROM-0 Module, Caller, And Old Port Closure Matrix

Goal: record exact module paths, classify live callers, and create the Old Port
Closure Matrix that maps current engine task ports to the five new mechanisms
before any new task-runtime behavior lands.

Scope:

- Record `xa-mass-task-runtime` as the top-level semantic owner module and
  remove any remaining wording that treats task-runtime semantics as
  `platform_infra` ownership.
- Record new implementation modules as the target:
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`. Current `mass-runtime-memory` and
  `mass-runtime-redis` remain worker-runtime/shared-infra implementation
  modules, not task-runtime migration sources.
- Record `sdk/xa-mass-task-runtime-starter-sdk` as the task-runtime starter SDK
  module path. TROM-0 records its Maven artifact name and dependency graph, but
  does not reopen the path decision without a concrete repo-layout blocker.
- Inventory all current `TaskWorkRuntime` and `TaskResultRuntime` production
  callers.
- Create the Old Port Closure Matrix for current task-facing ports:
  `TaskAssignmentRuntimePort`, `TaskLeaseMaintenancePort`,
  `TaskDispatchWakeupPort`, `TaskShellLifecycleMaintenancePort`,
  `TaskRuntimeRecoveryPort`, `TaskStateRuntimePort`, `TaskQueryPort`,
  `TaskCommandPort`, and `TaskResultIngestPort`.
- Record the Old Port Closure Matrix in
  [TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_INVENTORY.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_INVENTORY.md).
  The required columns are: old port, method, current callers, truth touched,
  target mechanism, target command/outcome, closure mode, proof, guard, and
  status.
- For each old port method, record:
  current callers, current runtime truth touched, target mechanism, target new
  task-runtime command/outcome, closure mode, first proof, and guard.
- Use closure modes:
  `delete`, `delegate to new runtime`, `split shell part from runtime part`,
  `keep engine-shell`, `engine-internal only`, or
  `requires prerequisite old-mechanism convergence`.
- Treat old-mechanism convergence as a targeted prerequisite only when a port
  cannot be directly deleted, delegated, split, or guarded. Do not create one
  broad "old mechanism cleanup" roadmap.
- Initial expected matrix direction:
  `TaskAssignmentRuntimePort` maps to Worker Reservation Then Claim;
  `TaskDispatchWakeupPort` and `TaskRuntimeRecoveryPort` map to Lane Acquire /
  Wakeup; `TaskLeaseMaintenancePort` maps to Active Lease Repair;
  `TaskResultIngestPort` maps to Result Apply / Finality Outcome;
  `TaskCommandPort.appendTaskItems*` maps to Intake / Append Commit;
  `TaskCommandPort` shell methods, `TaskQueryPort`,
  `TaskStateRuntimePort`, and `TaskShellLifecycleMaintenancePort` remain
  engine-shell/internal unless the matrix proves a runtime-truth method must
  move.
- Add a Task Scheduling Input Inventory covering `TaskRuntimeProfile*`,
  `ResolvedTaskSchedulingPolicy`, `TaskPolicyPresetResolution`, claim/enqueue/
  retry/finality option resolvers, and any current engine value passed toward
  enqueue, scheduler discovery, claim, retry, or result finality. The target is
  narrow command values such as `AppendAdmissionPolicy`, `ClaimLeasePolicy`,
  `RetryPolicySnapshot`, `ResultFinalityPolicySnapshot`, and `RuntimeEpoch`;
  task-runtime public ports must not accept `Task`, `ResolvedTaskSchedulingPolicy`,
  `TaskRuntimeProfile`, or engine resolver classes.
- Choose the first engine cut-in port by closability, not by feature neatness.
  Append intake can be selected first when its old path can close without
  active-lease ownership. Claim/assignment can be selected first as a
  non-serving proof, but production cutover must satisfy the serving
  active-lease gate by including or delegating result/retry/finality and repair.
  Result/finality likely needs a prerequisite matrix entry before serving
  cutover.
- Inventory `platform_infra/mass-runtime-api` as a formerly mixed module.
  Classify every task-runtime symbol, worker low-level SPI symbol, score-band
  slot contract, shared value, and test fixture as:
  `migrate to xa-mass-task-runtime`, `remain low-level shared SPI`,
  `owned by xa-mass-worker-runtime`, `implementation-only`, or `remove`.
- Inventory old `TaskWorkRuntimeContractTest` and
  `TaskResultRuntimeContractTest` coverage as migration seeds. Classify each
  invariant as preserved semantic contract, renamed semantic contract,
  implementation-only proof, or removal candidate.
- Inventory current engine-owned threads and loops that touch task work,
  result repair, lease expiry, or dispatch wakeups.
- Inventory SDK/starter/bootstrap code that currently creates or owns runtime
  loops.
- Produce the final module dependency graph for `xa-mass-task-runtime`,
  `platform_infra/mass-task-runtime-memory`,
  `platform_infra/mass-task-runtime-redis`, the task-runtime starter SDK,
  `xa-mass-engine-starter`, `sdk/xa-mass-embedded-sdk`, and `xa-mass-engine`.
  The graph must name the only host startup owner for each migrated
  task-runtime responsibility.
- Decide whether `sdk/xa-mass-embedded-sdk` only calls the task-runtime starter
  through a start/stop handle, whether `xa-mass-engine-starter` only supplies
  host ports/engine-facing handles, and which module is forbidden from creating
  task-runtime loops.
- Classify existing runtime APIs and values as semantic owner contract,
  implementation DTO, engine residue, projection/read model, or removal
  candidate.
- Reclassify every temporary exception in
  [ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md)
  whose slice is `TROM-0`, `TROM`, or `TROM-4`. Each must land in exactly one
  target bucket: `xa-mass-task-runtime`, engine-shell/internal, task-runtime
  starter contract, SDK public contract, or delete.
- Mark any retained older Redis task-runtime roadmap or inventory as superseded
  or subordinate after the module decision is accepted.
- Mark any required old-mechanism convergence that is too broad for TROM-0 as a
  separate prerequisite roadmap with a named closure target.

Acceptance:

- Inventory names each production caller and whether it should move to
  task-runtime, starter SDK, infra adapter, or engine shell/scheduling.
- Old Port Closure Matrix exists and covers every method on the formerly
  `TaskManager`-exposed task port family plus the retained engine-shell ports.
- The matrix is recorded in
  `TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_INVENTORY.md` using the
  required schema. TROM-0 is not complete while required rows remain pending,
  `_TBD`, or `later classify`.
- Each matrix row maps to exactly one of the five mechanisms, or is explicitly
  classified as engine-shell/internal residue.
- Each matrix row states whether closure is delete, delegate, split, keep,
  engine-internal only, or requires a prerequisite old-mechanism convergence
  roadmap.
- Any prerequisite old-mechanism convergence roadmap has a named port, method
  set, target mechanism, and closure condition. It is not a generic engine
  cleanup roadmap.
- Module-path decision is recorded with Maven artifact names.
- The starter SDK path is recorded as `sdk/xa-mass-task-runtime-starter-sdk`
  with the selected Maven artifact name.
- TROM-0 records a final module dependency graph and single startup-owner rule
  for each migrated task-runtime responsibility. The graph must prevent
  `xa-mass-engine-starter`, `sdk/xa-mass-task-runtime-starter-sdk`, and
  `sdk/xa-mass-embedded-sdk` from all becoming partial loop/bootstrap owners.
- TROM-0 records whether embedded SDK may only call task-runtime starter
  start/stop handles, and whether engine-starter may only provide host ports
  and engine-facing assembly handles.
- Every ECSP temporary value/config exception marked for TROM-0, TROM, or
  TROM-4 is reclassified in the TROM inventory to a target owner/module,
  proof, guard, and removal/retention decision. None may remain as
  `later classify`.
- Existing runtime contract tests are classified as migration seeds, not ignored
  or blindly copied.
- The `mass-runtime-api` split inventory prevents the old module from remaining
  a hidden task-runtime semantic owner and prevents task-runtime from importing
  worker-runtime low-level SPI by accident.
- No code behavior changes are required in this slice.
- The old Redis-only roadmap is no longer an executable parallel direction.
- First migrated port/method set has a written old port/method closure plan
  before TROM-1/TROM-2 implementation starts.
- Any port/method set whose old path cannot be closed has a recorded
  precondition:
  narrow the port/method set, converge the old mechanism first, or split a
  separate old-mechanism convergence roadmap.

## TROM-0A Legacy Mechanism Convergence Gate

Goal: make a specific old port/method set closable when the Old Port Closure
Matrix cannot name a safe shutdown mechanism.

Scope:

- Use this gate only for legacy mechanism work that directly enables shutdown
  of an old port/method set for a named task-runtime mechanism.
- Reduce broad engine paths into port/method-addressable seams where needed.
  Examples: `TaskResultIngestPort` result/finality, `TaskLeaseMaintenancePort`
  repair, `TaskDispatchWakeupPort` wakeup, `TaskRuntimeRecoveryPort` recovery,
  or `TaskCommandPort.appendTaskItems*` append commit.
- Split a dedicated roadmap when the legacy convergence is larger than one
  bounded TROM slice. The split roadmap must name the port/method set it
  unblocks, the new mechanism it enables, and the old port/method path it will
  make closable.
- Do not add new task-runtime behavior in this gate except test fixtures or
  guards needed to prove the old path is now port/method-addressable.

Acceptance:

- The target old port/method set has a concrete shutdown mechanism: delete,
  delegate, split, disable for a named production entry, bypass behind guard,
  or route to new owner.
- The convergence work names the exact old classes/methods/loops affected and
  the TROM mechanism it unblocks.
- Focused tests or guards prove the old mechanism can be isolated without
  changing unrelated task shell, worker-runtime, transport, or server behavior.
- If the old-mechanism convergence is split out, TROM records the prerequisite
  roadmap and does not start the corresponding new path until that prerequisite
  is satisfied.

## TROM-1 Semantic Task Runtime Contract

Goal: introduce the task-runtime protocol in the new semantic module without
physical storage or threads, while preserving an old port/method closure plan
for every contract path that will replace engine behavior.

Scope:

- Define the semantic runtime surface through the five mechanisms:
  Intake / Append Commit, Lane Acquire / Wakeup, Worker Reservation Then Claim,
  Result Apply / Finality Outcome, and Active Lease Repair.
- For each semantic command/outcome, record the old port/method set it is
  intended to replace, delegate from, split from, or leave untouched. Do not add
  contracts that cannot be tied to a named owner invariant and old port/method
  closure.
- Define first-version append admission/commit outcomes across task shell and
  task-runtime: rejected-before-runtime and all-accepted. Classified partial,
  caller idempotency keys, and heavy half-commit recovery are explicit
  non-goals for the first append contract.
- Define stable command/outcome values that do not expose Redis, memory map,
  Stream, ZSET, LIST, HASH, Lua, or queue primitive details.
- Define public/cross-module task-runtime DTOs from runtime contract needs, not
  current engine/runtime view shapes. Module-internal records may remain
  pragmatic during migration as long as they do not become public contract
  fields or leak storage/engine/trace/server ownership.
- Define the owner-level state machine:

```text
READY_BACKLOG
SCHEDULED_RETRY
LEASED
FINAL
DISCARDED
```

- Define attempt evidence: `messageId`, `attemptNo`, `leaseToken` or neutral
  reservation token, worker binding evidence, policy snapshot version, and
  retry count.
- Preserve optional carrier fields such as `eventCode`, `payloadRef`, and
  claim `batchId` from append/reservation to claim so engine can build assigned
  dispatch payloads without reading old runtime envelopes. These fields are
  handler/payload/dispatch-binding carriers, not worker selection, lane scoring,
  transport routing, or lifecycle owner facts.
- Define lane/gate/fence semantics needed by the runtime owner:
  lane-acquire outcome, runtime gate, expected runtime epoch, terminal/discard
  fence, and paused/parked lane behavior.
- Define claim preconditions. A claim must include admitted worker evidence,
  worker reservation token or equivalent owner-neutral reservation proof,
  expected runtime epoch, max items, and lease policy. A claim must not produce
  an active lease without a concrete worker binding.
- `ClaimLeasePolicy.maxItems` is a total item claim limit, not the number of
  worker reservations. A small reservation set may be reused round-robin for a
  larger batch claim, preserving the selected worker/admission evidence on each
  claimed item without creating one scheduler entry per ready item.
- Define append identity as accepted-item identity, not caller-level
  exactly-once submit. Caller API idempotency is deferred, but runtime replay of
  the same accepted `taskId + messageId` remains idempotent.
- Define low-cost aggregate/counter defense for accepted runtime batches when
  shell counters, append receipt, or wakeup fail after runtime acceptance. The
  first contract may rely on scheduler-owned backlog discovery/recovery and
  bounded owner-local reconciliation; append intake must not become the owner of
  task-level lane score. It must not add a cross-module bridge or second runtime
  truth.
- Define message-finality outcome contracts emitted by result apply/retry/
  finality. These outcomes are engine-neutral facts such as attempt closed,
  logical final, progress dirty, terminal candidate, duplicate/late, or
  rejected result; engine consumes them for trace/progress/terminal policy.
- Define a narrow progress snapshot contract with ready, delayed, active,
  success, failure, and lease-timeout counts so engine terminal policy can stop
  reading old `TaskWorkStats` after the migrated path cuts over.
- Define final result retention as bounded runtime read state. The first target
  retention is one day after task terminal, not durable public history.
- Define default-cost behavior: million-item raw backlog support, sparse active
  state, no default caller dedupe, no default per-message due index, no default
  durable result ledger, and no rich per-message view DTO on hot paths. Optional
  item carrier fields may exist only when they are needed by execution
  dispatch or payload lookup.
- Define explicit active-lease discoverability requirements. First contract
  version requires eventual discoverability, not exact lease-expiry ordering.
- Define durability-profile metadata needed to avoid false zero-loss claims.

Acceptance:

- Semantic module compiles without Redis, Spring, engine implementation,
  transport implementation, or SDK facade dependencies.
- No class in the semantic module exposes physical key/value/storage names.
- Public contract DTOs are narrow and justified by runtime behavior; old view
  objects are not copied into the module as compatibility surfaces.
- Module-internal DTO extra fields are not blockers unless they cross a module
  boundary, leak physical/runtime-owner internals, or preserve a second owner
  path.
- State-machine transitions and failure semantics are documented in the module
  README or contract docs.
- Contract surface states that timeout timing is best-effort but repair
  discoverability is required.
- Contract surface states that worker selection/reservation precedes runtime
  claim and that stale epoch or missing reservation evidence cannot create an
  active lease.
- Contract surface states that first-version append is all accepted or rejected,
  has a configured maximum batch size, and writes accepted ready backlog truth.
  Accepted backlog must remain discoverable by scheduler/recovery, but append
  intake is not required to synchronously rewrite lane score.
- Contract surface splits message finality from task terminal convergence and
  forbids task-runtime dependencies on engine, trace, or server review code.
- Contract docs include the intended old port/method closure target for append,
  lane acquire/wakeup, claim/lease, result finality, repair, and final-read
  surfaces.
- No contract is added solely to mirror an old engine DTO or keep an old public
  starter surface alive.

## TROM-2 Contract Test Harness And Memory Proof

Goal: prove the non-best-effort runtime protocol before Redis implementation.

Scope:

- Create a reusable task-runtime contract test suite.
- Add or use the `platform_infra/mass-task-runtime-memory` implementation that
  passes the contract only through public task-runtime ports. The semantic
  module may own abstract contract tests and narrow test fixtures, but it must
  not hide a production-like pure-memory runtime implementation that bypasses
  the public port boundary.
- Cover append id generation / accepted identity, configured max append batch
  size, all-accepted or rejected-before-runtime behavior, accepted ready backlog
  persistence, scheduler-owned backlog discovery, lane acquire, claim
  precondition rejection, claim exclusivity, and active lease creation with
  worker reservation/admission evidence.
- Cover batch claim where `ClaimLeasePolicy.maxItems` exceeds reservation
  count and the runtime reuses reservations round-robin while generating
  distinct lease tokens per claimed item.
- Cover non-guaranteed API-level duplicate submit behavior without a caller
  dedupe key. Runtime-level replay for the same accepted `taskId + messageId`
  remains idempotent.
- Do not require classified partial append or heavy half-commit recovery in the
  first memory proof. A failed wakeup after accepted append must still leave the
  batch discoverable through scheduler/recovery.
- The first memory proof covers result success, retryable failure,
  late/duplicate result classification, active-lease repair discovery, progress
  snapshots, bounded final reads, and discard through public task-runtime ports. Transport
  dispatch handoff and production result-ingress cutover remain TROM-5
  evaluation work.
- Cover result outcome emission for attempt-closed, logical-final,
  progress-dirty, terminal-candidate, duplicate/late, and rejected-result
  classifications without importing engine/trace/server code.
- Prove active leases remain discoverable even when no task score/due-work
  entry remains.
- Prove starter/thread absence in the semantic module through an architecture
  guard.
- Add negative contract fixtures for paths that must not accept both legacy and
  new owner writes for the same item identity.

Acceptance:

- `platform_infra/mass-task-runtime-memory` passes all semantic contract tests
  through the same public task-runtime ports that Redis must implement.
- A failing active-lease discoverability implementation fails a focused test.
- Guards fail if the semantic module imports Redis, Spring, SDK, engine
  implementation, transport implementation, or creates threads/executors.
- Guards fail if the semantic module grows a production-like memory runtime
  implementation instead of keeping memory behavior in the infra module.
- Contract proof includes at least one duplicate-owner negative case: the same
  `taskId + messageId` cannot create two ready/claim/final rows through the new
  public owner. Legacy-writer plus new-owner double-write proof remains part of
  the TROM-5 migrated-path closure guard because it needs a selected old path.

## TROM-3 Infra Adapter SPI And Redis Implementation

Goal: implement the semantic task-runtime protocol over physical infra without
leaking physical shape.

Current slice status:

- `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis` have first public-port adapters.
- Both adapters pass the shared `TaskRuntimePortContractTest` path.
- Infra adapter existence by itself does not cut over a production caller; the
  starter-backed memory/Redis `EngineConfig` cutover is tracked in TROM-5.

Scope:

- Create new memory and Redis infra implementation modules:
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`.
- Both infra modules implement the same public task-runtime ports. They must
  not reach into semantic-module internals or expose memory/Redis physical shape
  as caller-visible contract.
- Put Redis keyspace, codec, Lua/CAS, and physical score/list/hash decisions
  only in the Redis implementation module.
- Use `score-band-task-runtime-redis-shape.md` as the Redis implementation
  direction, but do not expose its key names or data structures to callers.
- Prove active-lease discoverability through a task-level active registry or
  equivalent bounded-discovery mechanism. The first Redis proof does not require
  exact lease-expiry ordering.
- Treat task-local earliest repair hints, per-lease expiry ZSETs, or exact
  timeout wakeup indexes as strategy upgrades. Add them only when a later policy
  or proof needs exact ordering and accepts the cost.
- Separate runtime transition no-loss from Redis node-loss durability. Expose a
  durability profile or explicit startup diagnostic for Redis guarantees.
- Prove Redis implementation can be enabled per migrated path without leaving
  the old physical `mass-runtime-*` implementation as a second live owner for
  the same path.

Acceptance:

- Memory and Redis implementations pass the same public-port contract suite.
- Redis-specific tests prove physical key count/cardinality goals without
  becoming public contract tests.
- Redis implementation proves default low-cost behavior: raw backlog storage is
  proportional to item payload frames, active state is proportional to current
  leases, and opt-in per-message indexes are absent unless policy enables them.
- Redis shape proof demonstrates that append of many ready items does not create
  per-ready-item runtime hashes.
- Redis implementation has no dependency on SDK starter, server, engine
  implementation, or transport implementation.
- Task-runtime callers cannot import Redis keyspace or codec packages.
- Redis profile tests include a fixture proving the old path is disabled or
  bypassed for any path that is enabled on the new implementation.

## TROM-4 Starter SDK Runner Surface And Thread Cutover

Goal: create the task-runtime starter SDK as the owner of new task-runtime
runner/loop-host assembly, without prematurely taking over every existing
engine production loop.

Current slice status:

- `sdk/xa-mass-task-runtime-starter-sdk` exists as an isolated starter module.
- It can bootstrap memory or Redis task-runtime backends through public ports.
- It owns start/stop lifecycle for isolated loop hosts and proves idempotent
  shutdown without leaked loop threads.
- It supports registering starter-owned loops after the runtime handle has
  started, which is required because the current engine kernel discovers some
  loops during kernel assembly.
- The production runtime-ready dispatch pump scheduler is migrated: engine still
  owns dispatch-admission decisions through `RuntimeReadyDispatchPump`, but the
  pump no longer creates a scheduler thread. `EngineRuntimeKernel` contributes
  it as an `EngineRuntimeLoop`, and `MassEngine` registers it with the
  starter-owned `TaskRuntimeHandle`.
- The production lease-expiry watchdog scheduler is migrated the same way:
  engine still owns the lease/max-runtime repair decision through
  `LeaseExpireWatchdog`, but that class no longer creates a scheduler thread.
- `RuntimeReadyDispatchPump` still owns its internal dispatch executor for
  async assignment attempts; that is an execution-owner residue, not a duplicate
  polling loop. It remains a separate closure target if the roadmap later
  requires all assignment execution threads to move out of engine.

Scope:

- Add the starter SDK module under `sdk/xa-mass-task-runtime-starter-sdk`.
- Define bootstrap configuration for memory or Redis task-runtime adapters.
- Define starter-owned runner/loop-host surfaces, such as
  `TaskRuntimeRunner` / `TaskRuntimeLoopHost` or equivalent, for due-task
  polling, lease repair, result repair, dispatch handoff integration, and
  graceful shutdown.
- In this slice, prove starter lifecycle wiring with isolated/in-memory loop
  hosts. Do not migrate all existing engine production loops before the first
  real path proof.
- For migrated production loops, engine may expose a narrow tickable loop
  descriptor, but the starter-owned task-runtime handle must own the scheduler
  thread. Do not let engine import the starter SDK.
- Do not start a starter-owned loop for a production responsibility until the
  old engine loop closure plan for that exact responsibility/path is
  implemented or guarded.
- Record a per-loop cutover plan for current engine-owned task-runtime polling
  and repair loops: runtime-ready dispatch polling, lease repair, and result
  repair residue. Each migrated loop must disable or bypass the old engine loop
  for the same path to avoid double polling or double repair.
- Do not move `TaskAssignWorker` into the task-runtime starter as part of TROM.
  It owns scheduling-plane assignment execution, lane queues, retry wakeups,
  de-duplication, and worker-selection invocation. Moving it requires a
  separate assignment-runtime / scheduling-plane roadmap, not a task-runtime
  starter loop cutover.
- Build this module independently first; the broader embedded-runtime split may
  consume it later, but task-runtime starter work must not wait for
  embedded-sdk cleanup.
- Expose host-facing start/stop handles and health/diagnostic summaries.
- Keep all external interaction through ports: task shell validation/policy,
  worker selection/assignment, transport dispatch, result ingress, trace, and
  optional operator diagnostics.

Acceptance:

- Semantic task-runtime module has no thread creation.
- Starter SDK owns construction and shutdown for new task-runtime loop hosts.
- Starter SDK does not own task item state transitions; it only calls
  task-runtime ports.
- Starter SDK tests prove start/stop idempotency, no leaked threads, and
  memory/Redis bootstrap profile selection for isolated loop hosts, including
  loop registration after the runtime handle is already running.
- No production path runs both an engine-owned loop and a starter-owned loop for
  the same task-runtime responsibility.
- A guard or fixture fails if a migrated production path registers both the old
  engine loop and the new starter loop for runtime-ready dispatch, lease
  repair, or result repair.
- Cutover tests prove the old loop is explicitly disabled or bypassed for the
  migrated path, not merely expected to stay idle.
- For runtime-ready polling and lease repair, the guard proves
  `RuntimeReadyDispatchPump` and `LeaseExpireWatchdog` no longer import or
  create scheduler primitives, `EngineRuntimeKernel` no longer calls their
  scheduler `start()` methods, and `MassEngine` registers the contributed loops
  with the starter-owned task-runtime handle.
- If a loop cannot be disabled cleanly, this slice must stop and create or link
  the required old-mechanism convergence roadmap instead of starting a duplicate
  loop host.

## TROM-5 Engine Strangler Integration And Old Path Closure

Goal: route the TROM-0-selected path through the new task-runtime owner and
close the old engine path only when the migrated path has a complete lease
convergence owner. The first non-serving proof target is append batch ->
scheduler-owned task-level eligibility discovery -> lane acquire ->
worker-runtime reservation/admission -> task-runtime claim. A production
cutover that creates active leases must also converge result/retry/finality and
active-lease repair in the same slice or delegate those old callers to the new
task-runtime owner.

Current slice status:

- An isolated non-serving proof exists in
  `TaskRuntimeNonServingAppendToClaimProofTest`.
- The proof runs append -> scheduler discovery -> synthetic selected-worker
  reservation evidence -> task-runtime claim -> task-runtime result finality
  through the starter memory backend.
- It does not use production `WorkerSelectionRuntime`, transport handoff, or
  engine old path closure, so it does not count as production cutover.
- Engine cutover-prep mapper proofs exist for append item carrier conversion,
  selected-worker reservation evidence, policy snapshot conversion, transport
  dispatch binding conversion, result apply command conversion for dispatch
  failure/lease-timeout compensation, progress snapshot consumption, and
  message-finality result decisions. They define the future engine input shape
  but do not route serving traffic.
- `TaskRuntimeEngineCutoverPreparationTest` composes those mappers with the
  memory task-runtime adapter in test scope. It is still non-serving and does
  not close an old engine path.
- `TaskRuntimeServingLane` now exists as an engine-side replacement for the old
  hot-path ports in the starter-backed `EngineConfig` assembly. It does not
  start threads and does not read physical Redis/memory internals; it drives
  append runtime writes, scheduler discovery, claim, result apply, lease repair,
  active-lease evidence, progress snapshots, final result read, full delete
  discard, work-only terminal cleanup, and terminal convergence through
  task-runtime ports.
- `TaskRuntimeServingLaneTest` proves the serving-lane owner shape over the
  memory task-runtime adapter: append -> scheduler discovery -> claim -> worker
  result -> engine terminal convergence, plus runtime-discovery/claim,
  result-ingress/correlation, dispatch submit/delivery failure compensation,
  lease-timeout repair -> terminal convergence, full delete discard, and
  work-only terminal cleanup through `TaskRuntimeServingLane`, all without
  writing old item/result runtime truth on the proof path.
- `TaskResultRuntimeConvergenceTest` proves the representative result
  convergence behaviors through the serving lane: duplicate/late result replay
  is idempotent, retryable failure returns the same message to scheduler
  discovery without a visible final row, non-retry failure creates one failed
  final row, and lease timeout finality is driven through task-runtime repair
  ports and final-result read. The new final-result row keeps worker/batch
  evidence after active lease removal, so SDK result reads can still expose the
  selected worker and stable attempt id without reading old active-lease
  projection. It intentionally no longer proves old
  `TaskResultRuntime` staged callback repair or repair-candidate barriers.
- `TaskResultConcurrencyConvergenceTest` proves representative concurrent
  result races through the serving lane: duplicate success callbacks, success
  vs lease expiry, retryable failure vs success, and different-message result
  apply concurrency before the task-runtime result port. It intentionally no
  longer proves old work-runtime progress coalescing subclasses or old
  `TaskWorkRuntime` / `TaskResultRuntime` DTO behavior.
- Default `EngineConfig` routing is cut over for the selected starter entry:
  command append, runtime-ready discovery, assignment claim, lease repair,
  result ingress, final result read, and progress/terminal convergence route to
  `TaskRuntimeServingLane`. `EngineConfig` now bootstraps task-runtime through
  `xa-mass-task-runtime-starter-sdk`, with memory as the default backend and an
  explicit Redis backend option. Server profile assembly now maps
  `mass.runtime.mode=memory|redis` to the corresponding task-runtime starter
  backend. The memory-local external polling worker E2E now proves the
  representative server/transport/result path through this serving lane. The
  Redis external polling worker E2E proves the same serving lane with Redis
  task-runtime backend plus Redis polling delivery/endpoint-lease stores.
  Redis Node polling black-box E2E proves the same Redis task-runtime serving
  lane through an external worker process and public HTTP worker APIs.
  Redis Node WebSocket black-box E2E proves a representative non-polling
  realtime adapter path through an external worker process. Redis Node Socket
  black-box E2E proves the raw socket adapter path through an external worker
  process. Redis Node polling lease-expiry redispatch trace-observed E2E proves
  the dropped-result failure edge for polling: a first external process exits
  after receiving work, task-runtime lease repair/retry returns the item to
  competition, and a second external process completes attempt 2 while analyzer
  `lease-expiry-redispatch` observes `LEASE_EXPIRED`,
  `TASK_WORK_ATTEMPT_CLOSED`, and pre/post dispatch binding. Redis Node
  WebSocket lease-expiry redispatch trace-observed E2E proves the same
  dropped-result failure edge through realtime WebSocket dispatch. Redis Node
  Socket lease-expiry redispatch trace-observed E2E proves the same
  dropped-result failure edge through raw Socket dispatch. Redis Node polling, WebSocket, and Socket
  late-result replay black-box E2Es cover stale replay after retry finality.
  Redis task-runtime owner reconnect is covered by
  `RedisTaskRuntimeOwnerReconnectTest`; Redis network partition public-port
  recovery is covered by `RedisTaskRuntimeNetworkPartitionTest`; Redis Node
  polling serving-path network partition recovery is covered by
  `RedisRuntimeNodePollingNetworkPartitionRedispatchBlackBoxIntegrationTest`;
  Redis process kill/failover, WebSocket/Socket serving-path network
  partition, and broader transport/result infra-fault coverage remain unproved.
  Server Boot assembly is now guarded against injecting legacy task work/result
  runtimes back into the engine-starter serving path. Public old-runtime
  injection is removed from `EngineConfig`, `MassEngineBuilder`,
  `MassApplicationBuilder`, and `MassSdk.EngineOptions`, with
  `EngineStarterBackdoorGuardTest` preventing that surface from reopening.
- For the serving lane, `TaskManager.ingestTaskResult(...)` and
  `TaskManager.getResultCorrelation(...)` now require and delegate to
  `TaskRuntimeServingLane`, but `TaskManager` no longer implements
  `TaskResultIngestPort`, and those result-ingest delegate methods have been
  deleted from `TaskManager`. The legacy `TaskResultService` result-ingest,
  expiry, compensation, visible-final committer, and repair-candidate scheduler
  path is deleted and guarded; it is no longer a standalone `TaskManager`
  fallback. The old background `TaskResultRepairPump` lifecycle is also deleted
  and guarded.
- For the serving lane, `TaskManager.getRuntimeDispatchableTasks(...)`,
  `TaskManager.claimReady(...)`,
  `TaskManager.compensateDispatchSubmitFailure(...)`, and
  `TaskManager.compensateDispatchDeliveryFailure(...)` also require and
  delegate to `TaskRuntimeServingLane`, but `TaskManager` no longer implements
  the migrated runtime assignment/recovery port types, and those migrated
  runtime delegate methods have been deleted from `TaskManager`; the old `TaskWorkRuntime`
  claim/recovery path and old `TaskResultService` dispatch failure
  compensation path are no longer present in `TaskManager`.
- Package-private old runtime getters and old result apply/progress helper
  methods have been deleted from `TaskManager`. Selected-path closure is now a
  compile-time absence of the old helper surface, old runtime port type
  surface, and deleted `TaskManager` runtime delegate surface, not just a
  fail-fast branch or visibility change.

Scope:

- Treat BATCH append as the default first entry-path candidate, subject to the
  TROM-0 Old Port Closure Matrix:
  append admission/commit -> scheduler-owned task-level discovery ->
  task-lane acquire/due check -> worker-runtime select/reserve/admit ->
  task-runtime claim with reservation token and expected runtime epoch.
- First-version append is all accepted or rejected. It must enforce a configured
  maximum batch size and must not introduce classified partial append, caller
  idempotency keys, or a heavy half-commit recovery protocol. Append writes
  ready backlog truth; lane score/wakeup is scheduler-owned and may be weakly
  consistent.
- Treat append-to-claim without result/retry/finality/repair as a non-serving
  proof only. It may prove contracts, module wiring, and old-path closure
  mechanics in test fixtures, but it must not route real production traffic or
  disable the old serving entry.
- Any production-reachable migrated path that creates a task-runtime active
  lease must include task-runtime result apply, retry/finality, and
  active-lease repair in this slice, or prove current old result/repair callers
  delegate to the new task-runtime owner and do not write old item truth.
- The default starter path now includes result apply, finality, final read, and
  lease convergence through `TaskRuntimeServingLane`; Redis backend selection is
  an `EngineConfig` bootstrap option through the task-runtime starter SDK, and
  server `memory-local` / `durable-local` context proof covers profile-to-backend
  routing. The memory-local and Redis polling E2Es cover real
  server/worker/result paths through the new serving lane, and the Redis Node
  polling black-box E2E covers the split-process polling worker path through
  public HTTP worker APIs. The Redis Node WebSocket black-box E2E covers one
  representative non-polling realtime adapter path. The Redis Node Socket
  black-box E2E covers the raw socket adapter path. Redis Node polling
  lease-expiry redispatch trace-observed E2E covers one distributed failure edge
  for dropped polling results and task-runtime lease repair/retry through
  analyzer `lease-expiry-redispatch`. Redis Node WebSocket lease-expiry
  redispatch trace-observed E2E covers the same dropped-result failure edge for
  realtime WebSocket dispatch. Redis Node Socket lease-expiry redispatch
  trace-observed E2E covers the same dropped-result failure edge for raw Socket
  dispatch. Redis Node polling, WebSocket, and Socket late-result replay
  black-box E2Es cover split-process stale replies after Redis lease expiry and
  retry finality. Redis task-runtime owner reconnect is covered by a Redis
  public-port proof over the same namespace, Redis task-runtime public-port
  network partition recovery is covered by `RedisTaskRuntimeNetworkPartitionTest`,
  and Redis Node polling serving-path network partition recovery is covered by
  `RedisRuntimeNodePollingNetworkPartitionRedispatchBlackBoxIntegrationTest`,
  while Redis process kill/failover, WebSocket/Socket serving-path network
  partition, and broader transport/result infra-fault edges still need separate
  serving-path evaluation before claiming broad distributed transport production
  cutover. Server
  profile assembly must not pass legacy `TaskWorkRuntime` / `TaskResultRuntime`
  beans into engine builder for the migrated serving path.
- Build an engine serving-lane owner that calls task-runtime semantic ports and
  implements the existing hot-path engine ports only as the old caller
  replacement surface.
- Before routing the path, implement or reference the old port/method closure
  plan from TROM-0/TROM-0A. The slice must name the old `TaskManager`,
  `TaskLifecycleService`, `SimpleTaskDispatchBinder`, `TaskResultIngestPort`,
  `EngineRuntimeKernel`, or `mass-runtime-*` path that is being closed. The old
  `TaskResultService` path is already deleted and should be treated as closed
  residue, not as a bridge target.
- Any further `EngineConfig` runtime routing must still define the old
  append/port/loop disablement for the migrated responsibility in the same
  slice. Config-level injection without old path closure remains a second
  owner, not convergence.
- Keep engine shell validation and scheduling decisions outside task-runtime.
- Keep engine-owned task aggregate counter reconciliation and terminal policy as
  consumers of task-runtime outcomes; do not move trace/review/progress policy
  into task-runtime.
- Add or narrow the worker-runtime integration port needed by this path. It
  should expose only selected worker/admission/reservation/dispatch-target
  evidence, not worker-runtime internal state or score-band implementation
  details.
- Add or narrow the transport handoff port only if the transport/result
  evaluation gate includes transport in this TROM-5 path. It should accept
  already assigned delivery work and return delivery outcome/failure evidence,
  not task lifecycle ownership.
- Runtime claim must consume worker reservation/admission evidence and expected
  runtime epoch. Empty, stale, or rejected claim paths must release or expire
  worker reservations without leaking capacity.
- Keep transport as best-effort delivery only.
- Disable or bypass old per-message runtime mutation for the chosen path.
- Disable or bypass old engine-owned assignment/dispatch/repair loops for the
  migrated path when starter-owned loops take over that responsibility.
- Delete, disable, or guard old DTO/import/public-surface residue only when it
  helps prove the old path cannot be used for the migrated path.
- Emit projection/review/trace after runtime acceptance, not before.
- Treat server view/API parity as a downstream projection concern, not the
  primary proof for this slice.

Acceptance:

- Chosen path has one runtime owner for item state.
- Old engine/runtime mutation path is not also writing the same item truth.
- Closure proof names the exact old path that is deleted, disabled, bypassed, or
  guarded for the migrated path.
- Focused integration proof covers every mechanism selected by the TROM-0 Old
  Port Closure Matrix and proves the corresponding old path is closed.
- Focused first-path proof shows append admission/commit, scheduler-owned
  backlog discovery, lane acquire/due check, worker-runtime select/reserve/admit, and
  task-runtime claim through the new owner.
- A path that stops at claim is explicitly non-serving and cannot be counted as
  production cutover, old serving-entry closure, or roadmap completion.
- A production-reachable migrated path that creates active leases proves
  task-runtime result apply/retry/finality and active-lease repair, or proves
  old result/repair callers delegate to the new task-runtime owner.
- For the selected starter/server path, direct `TaskManager` result-ingest and
  result-correlation calls must delegate to the installed serving lane; focused
  proof must fail if they write/read old `TaskResultRuntime` state instead, and
  `TaskManager` must not re-implement `TaskResultIngestPort`.
- For the selected starter/server path, direct `TaskManager` runtime discovery,
  claim, and dispatch failure compensation calls must delegate to the installed
  serving lane; focused proof must fail if they write old `TaskWorkRuntime` or
  old `TaskResultRuntime` state instead, and `TaskManager` must not
  re-implement the migrated runtime hot-path ports.
- If the selected path includes append, append proof covers accepted-item
  runtime ownership, max batch rejection, all-accepted behavior, and ready
  backlog persistence. Scheduler/recovery proof must show accepted backlog does
  not remain permanently hidden from lane acquire/recovery.
- If the selected path crosses worker-runtime, the proof uses a minimal
  worker/admission/reservation/dispatch-target port.
- If the selected path crosses transport, the proof uses a minimal
  assigned-delivery handoff port.
- The representative server proofs for this slice are the memory-local and
  Redis external polling worker paths. They can prove real transport/result
  integration through the serving lane, but they must not be cited as
  split-process distributed transport/result proof.
- If the selected path includes result/finality, result proof shows
  task-runtime emits message-finality outcome facts and engine consumes them
  for trace/progress/terminal policy without owning a second result-finality
  truth.
- If transport/result is deferred, TROM-5 records the concrete closure gap,
  owner risk, and follow-up proof path. Deferral is allowed only for
  non-serving append-to-claim proof; it is not a production bridge or
  compatibility fallback.
- A stale epoch or missing/mismatched worker reservation cannot create an active
  lease.
- Empty or rejected claims do not leak worker reservations.
- Migrated path has a failing guard/fixture for duplicate old/new loop
  registration.
- Regression guard prevents the chosen path from importing Redis keyspace or
  writing old engine item lifecycle state.
- Regression guard or source test prevents callers in the migrated path from
  returning to the old engine DTO/public starter surface when that surface would
  re-open the old path.
- Public starter/SDK surfaces cannot reintroduce direct old
  `TaskWorkRuntime` / `TaskResultRuntime` injection through `EngineConfig`,
  `MassEngineBuilder`, `MassApplicationBuilder`, or `MassSdk.EngineOptions`.
- No server view/API parity requirement is used as a substitute for the runtime
  path proof.

## TROM-6 Result Runtime Retention And Public Read Boundary

Goal: keep final result read truth inside task-runtime while making bounded
retention explicit.

Current slice status:

- Public final-result read contracts exist in `xa-mass-task-runtime`.
- Memory and Redis adapters prove final rows can be removed after bounded
  retention without using server review or trace rows as runtime truth.

Scope:

- Keep stable final result rows inside the `xa-mass-task-runtime` owner as a
  result sub-contract.
- Define bounded retention and cleanup policy. Initial target: final result rows
  are retained until roughly one day after task terminal, then task-runtime
  cleanup may remove them.
- Record that final result rows are not durable public result history and not a
  long-term audit ledger.
- Define public/cross-module final result DTOs for runtime reads. Internal
  result rows may keep pragmatic fields during migration, but public reads must
  not copy heavy current view rows, review rows, trace payloads, or
  worker/attempt diagnostic fields unless the task-runtime contract needs them
  for duplicate/late result handling or public bounded read semantics.
- Keep stage/repair/barrier semantics as runtime-owned reliability support.
- Ensure server review/export rows remain materialized views.
- Keep trace/audit history separate from runtime result read truth.

Acceptance:

- Public result read semantics are explicit: bounded runtime retention, not
  durable public result truth.
- Terminal cleanup tests prove the one-day retention target can remove final
  result rows without affecting trace/review materialization semantics.
- Duplicate/late callback handling does not depend on server review rows.
- Result contract tests cover final row idempotency, read window ordering,
  barrier repair, and discard cleanup.

## TROM-7 Residue Removal And Guards

Goal: remove old engine/runtime residue after each port/method-backed
production path moves.

Scope:

- Delete old per-message lifecycle state owners after callers move.
- Remove compatibility aliases and hidden fallbacks.
- Keep task-runtime semantic ownership retired from
  `platform_infra/mass-runtime-api`; leave only explicitly classified
  worker-runtime/shared SPI there.
- Retire or archive superseded Redis-shape roadmap text after implementation
  truth moves to owner READMEs and proof registry.
- Update `doc/TASK_LIFECYCLE_BASELINE.md`,
  `doc/INFRA_TRUTH_LAYERS.md`, `platform_infra/README.md`, `sdk/README.md`,
  and module READMEs when the actual owner changes.
- Add guards for forbidden imports and forbidden second-owner paths.
- Keep ECSP boundary guards in the regression set whenever TROM touches
  `xa-mass-engine-starter`, `sdk/xa-mass-embedded-sdk`, starter handles, or
  approved starter surfaces.

Acceptance:

- No old and new task-runtime owner paths remain live for the same port/method
  set or production entry.
- Guards block task-runtime semantic module from storage/thread/bootstrap
  leakage.
- Guards block engine/server/SDK/transport from Redis task-runtime keyspace and
  physical DTO imports.
- Guards block `mass-runtime-api` from regaining task-runtime semantic ownership
  after task-runtime callers move.
- ECSP guards still fail on `MassApplication.getEngine()`,
  `MassEngine.getConfig()`, direct embedded-sdk engine internals, and
  unapproved starter-facing surface expansion.
- Proof registry names the focused non-best-effort task-runtime contract tests
  and startup/starter verification commands.

## Suggested Implementation Order

0. Complete
   [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md).
1. TROM-0: inventory, module naming, first port/method set selection, and old
   port/method closure plan.
2. Conditional TROM-0A: converge the old mechanism first when the shutdown path
   is unclear. Split a separate roadmap if the convergence is too large for one
   bounded TROM slice.
3. TROM-1: semantic contracts and state-machine docs, including old
   port/method closure targets for each new contract surface.
4. TROM-2: contract tests and memory proof, including double-owner negative
   cases.
5. TROM-5: one selected append-to-claim non-serving proof through
   worker-runtime reservation/admission, using the memory proof path when
   sufficient. Promote it to production cutover only when result/retry/finality
   and active-lease repair are included or delegated to the new task-runtime
   owner, then close the old engine path for that production entry.
6. TROM-3: Redis implementation and active-lease discoverability proof for
   paths whose old path is already closed or guardable.
7. TROM-4: starter SDK runner/loop-host surface and thread cutover only for
   responsibilities whose old engine loop has a closure plan.
8. Repeat TROM-5 per selected port/method set or production entry.
9. TROM-6: result-read retention/durability boundary if not already converged.
10. TROM-7: residue deletion, guards, docs, proof registry, archive.

## Verification Candidates

Commands must be corrected after module names are finalized. Candidate proof
shape:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk -am -DskipTests install
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeContractShapeTest,TaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory test "-Dtest=InMemoryTaskRuntimeContractTest,InMemoryTaskRuntimeArchitectureGuardTest,InMemoryTaskRuntimeRetentionTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisScoreBandTaskRuntimeTest,RedisTaskRuntimeArchitectureGuardTest,RedisTaskRuntimeScoreBandKeyspaceProofTest,RedisTaskRuntimeScoreBandAdvanceCandidateTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisTaskRuntimeOwnerReconnectTest,RedisTaskRuntimeNetworkPartitionTest"
.\mvnw.cmd -q -pl sdk/xa-mass-task-runtime-starter-sdk test "-Dtest=TaskRuntimeStarterBootstrapTest,TaskRuntimeStarterLifecycleTest,TaskRuntimeStarterArchitectureGuardTest,TaskRuntimeNonServingAppendToClaimProofTest,TaskRuntimeLoopCutoverGuardTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeServingLaneTest,TaskRuntimeServingLaneOldPathClosureGuardTest,TaskManagerLifecycleTest,TaskKernelLifecycleTest,TaskRuntimeRecoveryPortTest,RuntimeReadyDispatchPumpTest,SimpleTaskDispatchBinderTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest,TaskRuntimePolicySnapshotMapperTest,TaskRuntimeResultFactMapperTest,TaskRuntimeDispatchBindingMapperTest,TaskRuntimeResultDecisionMapperTest,TaskRuntimeAppendItemMapperTest,TaskRuntimeWorkerReservationMapperTest,TaskRuntimeEngineCutoverPreparationTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest,TaskWorkerEligibilityTest,TaskDelayedAvailabilitySchedulingTest,TaskRedispatchCompetitionTest,TaskPolicySchedulingOutcomeTest,WorkerStateReportSchedulingIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-engine -am -DskipTests install
.\mvnw.cmd -q -pl xa-mass-engine-starter test "-Dtest=EngineConfigTaskRuntimeServingLaneTest"
.\mvnw.cmd -q -pl xa-mass-engine-starter -am -DskipTests install
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=MassSdkTest#engineConfigMemoizesRuntimeBoundaries,MassSdkTest#engineOptionsExposeTaskRuntimeBackendSelection"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am -DskipTests install
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=EmbeddedSdkEngineDependencyGuardTest,EngineStarterBackdoorGuardTest,EngineStarterSurfaceInventoryGuardTest,EngineCallerSurfaceInventoryCompletenessGuardTest,EngineStarterWorkerTransportOwnershipGuardTest"
.\mvnw.cmd -q -pl xa-mass-testing test "-Dtest=TaskFlowLoadModelRunnerTaskRuntimeGuardTest"
.\mvnw.cmd -q -pl xa-mass-testing test "-Dtest=WorkerFaultScenarioIndexTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=XaMassServerApplicationTransportRuntimeConfigTest,ServerMemoryLocalProfileContextTest,ServerDurableLocalProfileContextTest,ServerMainSourceArchitectureGuardTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=ExternalWorkerPollingApiIntegrationTest#externalWorkerPollingApiCompletesTaskEndToEnd"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeExternalWorkerPollingApiIntegrationTest#externalWorkerPollingApiCompletesTaskEndToEndWithRedisTaskRuntime"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodePollingWorkerBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodePollingLeaseExpiryRedispatchBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodePollingNetworkPartitionRedispatchBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodePollingLateResultReplayBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodeWebSocketWorkerBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodeWebSocketLeaseExpiryRedispatchBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodeWebSocketLateResultReplayBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodeSocketWorkerBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodeSocketLeaseExpiryRedispatchBlackBoxIntegrationTest"
.\mvnw.cmd -q -pl xa-mass-server test "-Dtest=RedisRuntimeNodeSocketLateResultReplayBlackBoxIntegrationTest"
```

If any slice touches Spring/server startup, add a startup or context proof for
the relevant profile instead of relying only on direct constructor tests.

TROM-0, TROM-5, and TROM-7 must include the ECSP guard regression command when
they touch starter/SDK/engine-starter boundaries. The guard set must prove that
deleted `MassEngine` / `MassApplication` backdoors remain absent, embedded SDK
does not regain direct engine internals, and approved starter surfaces do not
expand without inventory review.

## Roadmap Completion Criteria

- A dedicated task-runtime owner module owns the non-best-effort item/result
  convergence protocol.
- Physical memory/Redis storage details live only in infra implementation
  modules.
- Append has an explicit first-version admission/commit boundary: a batch is
  all accepted or rejected before runtime ownership, obeys configured max batch
  size, does not promise caller idempotency keys, and writes accepted ready
  backlog truth. Scheduler/recovery owns later task-level discoverability.
- Message finality target ownership is recorded as task-runtime outcome facts.
  If transport/result is included in the current path, task terminal policy,
  trace, and progress convergence consume those facts from engine-side owners.
  If deferred, TROM records the follow-up closure gap and does not claim result
  finality has moved.
- The starter SDK owns new task-runtime runner/loop-host construction,
  bootstrap, lifecycle, and host integration for migrated task-runtime
  responsibilities.
- At least one port/method-backed production path uses the new owner without
  dual runtime truth and proves append batch -> scheduler-owned backlog
  discovery -> lane acquire -> worker-runtime selection/reservation/admission
  -> task-runtime claim active lease through minimal ports. Because this is a
  production path, it must also prove result/retry/finality and active-lease
  repair are handled by task-runtime or delegated from old callers to the new
  task-runtime owner.
- Transport assigned delivery -> result apply/finality -> final read is either
  added to that path after implementation evaluation, or recorded as a named
  follow-up with owner risk, old-path closure gap, and proof path. A follow-up
  may keep append-to-claim proof non-serving, but it cannot be used as a
  production bridge or compatibility fallback.
- The first migrated port/method set followed the required order: old mechanism
  convergence if needed, new owner path, proof, and old port/method closure.
- For every migrated port/method-backed path, the old engine/runtime path is
  deleted, disabled, bypassed behind a guard, or explicitly routed to the new
  owner. No path is considered migrated while the closure mechanism is unknown.
- Migrated paths do not run duplicate engine-owned and starter-owned loops for
  the same task-runtime responsibility.
- `platform_infra/mass-runtime-api` no longer acts as hidden task-runtime
  semantic owner for migrated production paths; remaining worker low-level SPI
  is explicitly classified.
- Old engine item lifecycle residue is removed or explicitly tracked by the
  next active slice.
- Owner docs, proof registry, and guards match the implemented behavior.
- Superseded Redis-only direction is archived or rewritten as an implementation
  detail under the new module boundary.
- The prerequisite engine-starter boundary roadmap is complete, and this
  roadmap consumes its final inventory, starter handle decisions, and guard set.
- ECSP boundary guards remain green after TROM changes that touch starter,
  embedded SDK, engine-starter, or approved starter surfaces.
