# Kernel Design Agent Handoff

Status: current local handoff for `kernel_design/`.

This directory owns clean-kernel mechanism contracts and the current Python
executable specification. Kotlin production work lives under `kernel_jvm/` and
must use this workspace as its parity oracle.

## 0. TL;DR

- `kernel_design/` is the current mechanism oracle.
- The superseded Java platform exists only in
  `legacy-java-platform-final-2026-07-24`.
- Historical Java code is useful only as failure-mode evidence, invariant
  input, or anti-pattern context.
- The design target is a small strict kernel, not a simplified weak kernel.
- Owner boundaries matter more than closing a broad demo loop.
- Prefer owner-local executable proof before crossing into another runtime.
- Treat `TaskType` as a supported workload scenario contract, not a bag of
  orthogonal policy switches.
- Add a TaskType only when a concrete scenario cannot be represented by the
  existing types and has its own vertical executable proof.
- Treat Task scheduling as the current control-flow mainline. Worker resource
  and score are independent truth owners called by that mainline, not a second
  peer scheduling application.
- Do not promote route metadata, Adapter connectivity observations, command
  handoff, or result evidence into a Worker lifecycle without a concrete
  scenario and an implemented owner.
- Treat `WorkerGroup.eventCodes` as an immutable capability declaration, not a
  scheduling predicate. Server/control-plane code may validate semantic
  compatibility; Kernel append, matching, and dispatch must not add per-Item
  EventCode checks.

Core kernel shape:

```text
Task score admission and visibility
  -> TaskType profile
     -> TASK_DRIVEN: Task rule, candidate warmup/cache, PRECOMPUTED acquisition
     -> ITEM_DRIVEN: TaskItem rule, no cache, TARGETED acquisition
  -> Task Dispatch
     -> ACTIVE Item: Worker score lease/validation -> TaskItem claim
                     -> DeliverSeed -> WorkerCommand mailbox
     -> no ACTIVE Item: shared empty recheck and emptyCloseAtMillis policy
  -> Worker Delivery Dispatch
     -> outbound Worker command protocol
     -> semantic SeedResult ingress
     -> outcome-class SeedResult queues
  -> Result Routing
     -> TaskItem and Worker truth convergence
```

These are scheduling planes, not necessarily modules. A first Python kernel may
implement them in one package if owner truth remains explicit.

## 1. Trust Order

Use this order inside `kernel_design/`:

1. Python executable spec code and tests under `executable_spec/`
2. Current local design docs under `doc/scheduling/`, `doc/resource-model/`,
   and `doc/runtime-redis/`
3. Superseded shape docs only when their status says they are retained as
   historical context
4. Historical tag material only as legacy/failure-mode context

If a design doc and Python executable spec disagree, describe the gap and do
not silently bend one into the other.

This trust order describes current behavior; it does not authorize code to
override an already aligned interface contract. If code and an agreed contract
diverge, stop and identify which one is stale before editing either side.

### TaskType Scenario Gate

Do not design Task scheduling by enumerating independent policy combinations.
For every proposed TaskType or type-level behavior, identify:

```text
concrete workload and caller
why TASK_DRIVEN and ITEM_DRIVEN cannot represent it
which owner or scheduling invariant differs
which policy decisions are required by that scenario
the create -> dispatch -> result/close vertical proof
```

Scan limits, cadence, priority, fairness, retry intervals, and other bounded
System Policy values do not justify a new TaskType. Tests must prove supported
TaskType scenarios and owner primitives; do not add orchestration branches or
defensive tests for policy combinations that no supported scenario can create.

Do not infer priority, latency class, RPC versus batch behavior, arrival
density, Worker exclusivity, or preemption rights from TaskType. The stable
type distinction is Task-owned reusable Worker rules with candidate
precomputation versus Item-owned complete Worker rules with targeted
acquisition. Identical Item rules remain valid for `ITEM_DRIVEN`.

## 2. First Read

For general kernel work:

1. [README.md](README.md)
2. [doc/README.md](doc/README.md)
3. [doc/scheduling/README.md](doc/scheduling/README.md)

For worker-runtime work:

1. [doc/resource-model/worker-resource-model.md](doc/resource-model/worker-resource-model.md)
2. [doc/scheduling/worker-score-band-scheduling.md](doc/scheduling/worker-score-band-scheduling.md)
3. [doc/scheduling/worker-hot-acquire-lease-protocol.md](doc/scheduling/worker-hot-acquire-lease-protocol.md)
4. [doc/scheduling/assignment-dispatch-scheduling.md](doc/scheduling/assignment-dispatch-scheduling.md)
5. [executable_spec/kernel/worker_runtime.py](executable_spec/kernel/worker_runtime.py)
6. [executable_spec/kernel/worker_score.py](executable_spec/kernel/worker_score.py)
7. [executable_spec/redis_runtime/worker_score.py](executable_spec/redis_runtime/worker_score.py)
8. [executable_spec/tests/test_worker_runtime_contract.py](executable_spec/tests/test_worker_runtime_contract.py)
9. [executable_spec/tests/test_redis_worker_score.py](executable_spec/tests/test_redis_worker_score.py)

For task runtime or task score-band work:

1. [doc/resource-model/task-resource-model.md](doc/resource-model/task-resource-model.md)
2. [doc/scheduling/task-score-band-scheduling.md](doc/scheduling/task-score-band-scheduling.md)
3. [doc/scheduling/task-item-score-band-scheduling.md](doc/scheduling/task-item-score-band-scheduling.md)
4. [executable_spec/kernel/task_runtime.py](executable_spec/kernel/task_runtime.py)
5. [executable_spec/kernel/task_score_band.py](executable_spec/kernel/task_score_band.py)
6. [executable_spec/kernel/task_item_score_band.py](executable_spec/kernel/task_item_score_band.py)
7. [executable_spec/redis_runtime/task_item_score_band.py](executable_spec/redis_runtime/task_item_score_band.py)
8. [executable_spec/redis_runtime/task_runtime.py](executable_spec/redis_runtime/task_runtime.py)
9. [executable_spec/redis_runtime/task_score_band.py](executable_spec/redis_runtime/task_score_band.py)
10. [executable_spec/tests/test_task_runtime_contract.py](executable_spec/tests/test_task_runtime_contract.py)
11. [executable_spec/tests/test_task_item_score_band_contract.py](executable_spec/tests/test_task_item_score_band_contract.py)
12. [executable_spec/tests/test_redis_task_item_score_band.py](executable_spec/tests/test_redis_task_item_score_band.py)
13. [executable_spec/tests/test_redis_task_item_score_band_integration.py](executable_spec/tests/test_redis_task_item_score_band_integration.py)
14. [executable_spec/tests/test_redis_task_runtime.py](executable_spec/tests/test_redis_task_runtime.py)
15. [executable_spec/tests/test_redis_task_runtime_integration.py](executable_spec/tests/test_redis_task_runtime_integration.py)
16. [executable_spec/tests/test_redis_task_score_band.py](executable_spec/tests/test_redis_task_score_band.py)

For assignment dispatch:

1. [doc/resource-model/task-resource-model.md](doc/resource-model/task-resource-model.md)
2. [doc/scheduling/assignment-dispatch-scheduling.md](doc/scheduling/assignment-dispatch-scheduling.md)
3. [executable_spec/scheduling/task_scheduling_profile.py](executable_spec/scheduling/task_scheduling_profile.py)
4. [executable_spec/scheduling/task_worker_allocation.py](executable_spec/scheduling/task_worker_allocation.py)
5. [executable_spec/scheduling/task_dispatch.py](executable_spec/scheduling/task_dispatch.py)
6. [executable_spec/tests/test_task_dispatch_integration.py](executable_spec/tests/test_task_dispatch_integration.py)
7. [executable_spec/tests/test_result_routing_integration.py](executable_spec/tests/test_result_routing_integration.py)

For result routing:

1. [doc/scheduling/result-routing-scheduling.md](doc/scheduling/result-routing-scheduling.md)
2. [doc/runtime-redis/seed-result-runtime-redis-shape.md](doc/runtime-redis/seed-result-runtime-redis-shape.md)
3. [executable_spec/kernel/result_context.py](executable_spec/kernel/result_context.py)
4. [executable_spec/kernel/seed_result_runtime.py](executable_spec/kernel/seed_result_runtime.py)
5. [executable_spec/scheduling/result_routing.py](executable_spec/scheduling/result_routing.py)
6. [executable_spec/redis_runtime/result_routing.py](executable_spec/redis_runtime/result_routing.py)
7. [executable_spec/tests/test_result_routing.py](executable_spec/tests/test_result_routing.py)

For Worker Delivery Protocol changes:

1. [doc/scheduling/worker-delivery-dispatch.md](doc/scheduling/worker-delivery-dispatch.md)
2. [executable_spec/kernel/worker_delivery.py](executable_spec/kernel/worker_delivery.py)
3. [executable_spec/redis_runtime/worker_delivery.py](executable_spec/redis_runtime/worker_delivery.py)
4. [executable_spec/assembly/transport_clients.py](executable_spec/assembly/transport_clients.py)
5. [../server_jvm/src/main/java/com/xa/mass/server/workerdelivery](../server_jvm/src/main/java/com/xa/mass/server/workerdelivery)
6. [executable_spec/tests/test_worker_delivery.py](executable_spec/tests/test_worker_delivery.py)

For process assembly or server entry work:

1. [doc/kernel-application-assembly.md](doc/kernel-application-assembly.md)
2. [executable_spec/assembly/application.py](executable_spec/assembly/application.py)
3. [executable_spec/assembly/resources_command_client.py](executable_spec/assembly/resources_command_client.py)
4. [executable_spec/assembly/assignment_dispatch_application.py](executable_spec/assembly/assignment_dispatch_application.py)
5. [executable_spec/assembly/result_routing_application.py](executable_spec/assembly/result_routing_application.py)
6. [executable_spec/assembly/transport_clients.py](executable_spec/assembly/transport_clients.py)
7. [executable_spec/tests/test_kernel_application.py](executable_spec/tests/test_kernel_application.py)
8. [executable_spec/tests/test_resources_command_client.py](executable_spec/tests/test_resources_command_client.py)
9. [runtime_server/app.py](runtime_server/app.py)

For Worker Delivery Dispatch or a Java Worker:

1. [doc/scheduling/worker-delivery-dispatch.md](doc/scheduling/worker-delivery-dispatch.md)
2. [doc/scheduling/result-routing-scheduling.md](doc/scheduling/result-routing-scheduling.md)
3. [doc/kernel-application-assembly.md](doc/kernel-application-assembly.md)
4. [../server_jvm/src/main/java/com/xa/mass/server/workerdelivery](../server_jvm/src/main/java/com/xa/mass/server/workerdelivery)
5. [../worker_delivery_contract_jvm](../worker_delivery_contract_jvm)
6. [../worker_jvm](../worker_jvm)
7. [executable_spec/assembly/transport_clients.py](executable_spec/assembly/transport_clients.py)

## 2.1 Python Naming Rules

Python workspace names expose owner, mechanism, or process responsibility, not
historical status or storage trivia:

```text
executable_spec/              stable mechanism package, never example/demo
  kernel/                     owner contracts, score mechanisms, internal protocols
  scheduling/                 bounded matching and cross-owner pacer orchestration
  assembly/                   application lifecycle and dependency composition
  constraint_dsl/             standalone constraint compilation/evaluation
  redis_runtime/              Redis-backed implementations of owner contracts
runtime_server/               Python Kernel control command host
```

`kernel_design/runtime_server/` composes only WorkerGroup/Worker upsert, Task
create/approve/close, and `KernelApplication` lifecycle. Java `server_jvm`
hosts TaskItem append, last-success reads, and Worker Delivery. The Python
TaskRuntime and Worker Delivery runtime/clients remain executable-spec oracles
and test support. The runnable external Worker lives in `worker_jvm` and
depends on the shared Java protocol module. The framework-free Adapter
mechanism lives in `worker_delivery_adapter_jvm`; its concrete WebSocket host
is not currently assembled. The Core consumes Server batch HTTP and has no
Spring, Kernel, Redis, thread, or framework lifecycle dependency.

Use these rules:

- package names describe an owner/domain or backend implementation boundary;
- file names describe the primary owner or mechanism in that file;
- contract classes use semantic names such as `TaskScoreBandCore`;
- concrete backend classes use one backend prefix such as
  `RedisTaskScoreBandCore`;
- do not repeat internal storage structures such as `Zset`, `Hash`, `List`, or
  `Lua` in stable class names merely because the first implementation uses them;
- related DTOs and one owner interface may share a file; do not force one class
  per file when it fragments one owner surface;
- test file and test class names should identify the concrete mechanism under
  proof;
- renames replace old names directly; do not retain aliases or compatibility
  packages inside this isolated workspace.

## 2.2 Interface Change Gate

Treat every kernel-facing method, DTO, callback, and owner operation as frozen
unless the current request explicitly changes that contract. An implementation
cleanup must preserve caller, owner, input authority, output meaning, side
effects, bounds, and concurrency semantics.

Before editing an interface or moving an operation between classes, write down:

```text
owner
caller
caller-owned inputs
owner-internal inputs
output meaning
side effects and keys
batch/scan bound owner
concurrency or stale fence
explicitly excluded responsibilities
```

Stop and discuss before implementation when any of these changes:

```text
method signature or DTO shape
which component performs I/O
which component selects or discovers candidates
which component owns a limit, ordering rule, retry, or fairness policy
which owner writes score, lease, queue, descriptor, or result truth
atomicity, CAS, lease, monotonicity, or best-effort semantics
```

Removing a bridge, wrapper, or helper is not permission to redistribute its
operations. Inline the same owner sequence at the caller. Do not move candidate
acquisition into matching, policy into a runtime primitive, persistence into an
orchestrator, or owner validation into a convenience facade merely to reduce a
parameter or call site.

Public inputs must be meaningful and constructible by the caller. Owner-local
score encodings, internal observations, handler maps, Redis ranges, and cached
snapshots stay hidden. Conversely, a caller-selected bounded identity set such
as `workerIds` must not become hidden callee discovery just to shorten the
signature.

Required proof for an intentional interface change:

- update the owning design document before or with code;
- lock the public signature or DTO shape in a contract test;
- prove which owner performs every external read and mutation;
- add a negative assertion for the owner action that must not move;
- scan for the old interface, renamed wrappers, compatibility paths, and stale
  documentation;
- if implementation reality differs from the approved change, stop instead of
  expanding scope.

## 3. Owner Map

Task score-band owns:

```text
task scheduling visibility
task score-state interpretation
bounded task acquire / recheck primitives
```

It does not own Item append, Item score claim/retry/outcome movement, worker
selection, transport delivery, or result finality classification.

TaskRuntime owns:

```text
canonical per-Task Item records
TaskItem validation, defaults, persistence, and bounded record reads
batch Item append orchestration through TaskItemScoreBandCore initialization
Task-scoped last-success payload storage and bounded requested-id reads
```

The external process implementation is split by owner operation, not by truth.
Java implements the same `TaskRuntime` contract in `kernel_jvm` for public Item
append and last-success reads; Python `RedisTaskRuntime` remains the mechanism
oracle and performs internal ResultRouting storage. Both use the same keys.
`server_jvm` selects providers in assembly; it does not define a route-shaped
or Server-local Kernel runtime. There is no proxy fallback, mirrored result
store, or double write.

TaskItemScoreBandCore owns:

```text
initial ACTIVE Item score creation
bounded ACTIVE Item-score acquire
same-tag claim/retry rewrite through exact observed-score fencing
strict-tag ACTIVE < FINAL_FAILED < FINAL_SUCCESS outcome promotion
```

`TaskItem` is the only runtime unit from append through finality. Claiming it
does not create a `Work` / `WorkItem` model, id, store, runtime, or owner.

Item append callers provide TaskItem fields only. Append scheduling policy maps
Item priority to initial due milliseconds; Task config owns `maxRetryTimes`.
The Python oracle passes those stable initialization inputs to
TaskItemScoreBandCore. The Java `RedisTaskRuntime` provider reproduces only this
initial record-plus-score operation behind the same owner contract. Tag,
timeSlot, suffix, score bounds, and initial score never cross the HTTP API.

Score is not a resource mutation lock. Task/worker metadata writes, dynamic
attribute writes, item append, result/evidence writes, projections, and trace
must not acquire or refresh score. Initialization establishes the first score;
the active scheduling plane is the only routine writer for acquirable scores;
explicit lifecycle commands may invoke only declared approve/reject/pause/
resume/close or scheduling-serviceability transitions.

Worker-runtime owns:

```text
worker group descriptor and worker descriptor truth
dynamic attribute update ingress
bounded worker candidate matching
worker score acquire / recovery / hot lease / dirty / release semantics
```

Worker-runtime surfaces in the current Python spec:

```text
WorkerResourceCatalog
  group descriptors, worker descriptors, low-frequency metadata

WorkerDynamicAttributeRuntime
  bounded dynamic attribute updates and owner reads; handler tables stay internal

WorkerCandidateMatcher
  one worker group, caller-supplied bounded worker id batch, ordered candidate
  constraints, and matched Worker lease evidence

WorkerScoreCore
  bounded HOT observation, batched exact-score lease, RECOVERY_RECHECK acquisition, and score transitions
```

Assignment-dispatch owns:

```text
one scheduling-round composition
candidate ranking after worker-runtime matching
short assignment plan evidence
Item score claim timing
dispatch-time Worker lease disposition through WorkerScoreCore owner primitives
queued DeliverSeed creation
```

Within Task Dispatch, `TaskDispatchPacer` owns the bounded RUNNING round,
suffix routing, mailbox publication, and Task-score pacing.
`TaskItemDispatcher` owns one suffix-zero Task's Item observation, candidate
acquisition, exact Item claim, DeliverSeed construction, and Worker command
construction. It is not another Pacer or lifecycle owner.

It does not own Task lifecycle truth, Worker resource or score truth, Worker
score encoding/storage, result finality, transport delivery, or transport
session internals. The canonical allocation/dispatch/result lease sequence is
defined by
[Worker HOT_ACQUIRE Lease Protocol](doc/scheduling/worker-hot-acquire-lease-protocol.md).

Worker Delivery Dispatch owns:

```text
Server point WorkerId polling through an explicit endpointManagerId binding
Server bounded cursor access for a long-lived Adapter's sparse mailbox
stable WorkerCommandEnvelope forwarding to the already selected Worker
Server point Worker result and Adapter batch SeedResult validation/append
framework-free Java Adapter session/dispatch/result-buffer mechanism
```

Its boundary starts after Task Dispatch handles mailbox publication and ends
after SeedResult append. It does not select Workers, claim Items, mutate Task
score, interpret Worker score, or renew/release Worker leases. The current
polling HTTP slice accepts Worker-originated `200/1xxx`; the long-lived Adapter
batch ingress accepts `3xxx` pre-execution rejection evidence. Polling never
scans a mailbox, and `system-polling` is only a logical route binding.

The framework-free Java Adapter Core owns one configured non-system-polling
mailbox per instance through the Server batch HTTP API, session generations,
one-round dispatch, bounded result buffering, and `3001` versus `UNKNOWN`
classification. A future WebSocket host may own only frame/connection
adaptation, round scheduling, and process lifecycle; `server_jvm` does not
currently start that host. Workers upsert before connecting. Missing session
evidence may produce `3001`; expiry, disconnect, missing result, and any
failure after send was attempted remain UNKNOWN. Different endpoint-manager
identities may run in parallel; same-endpoint distributed ownership remains
unsupported.

Result-routing owns:

```text
three outcome-class SeedResult queues through SeedResultRuntime
bounded consume, opaqueResultContext decode, owner-key grouping, and handler
delegation through ResultRoutingPacer
Task-scoped last-success result payload storage before FINAL_SUCCESS promotion
through the selected Task result handler
current built-in policy performs no Item score mutation for `1xxx/3xxx`; the
existing claim becomes due naturally
workerGroupId plus opaque workerLeaseScore pass-through to WorkerScoreCore exact
release for `200/1xxx` or exact RECOVERY_RECHECK demotion for `3xxx` through
the selected Worker result handler
valid routed-evidence counting
```

SeedResultRuntime may classify only the public outcome class needed to select
its queue; it must not decode context. ResultRoutingPacer must not interpret
current Item score, reproduce same-tag/cross-tag rules, select workers, parse
transport sessions as truth, depend directly on Task/Worker runtime owners, or
refresh task or worker score as a generic side effect. Built-in owner-operation
policy belongs to `ResultRoutingBuiltinPolicies`; replacement policy uses the
same stable handler contracts. SeedResult queues are not partitioned by endpointManagerId,
exact subcode, Task, WorkerGroup, or producer source. The current result
projection is a bounded Java read of requested Task-scoped last-success
payloads. It exposes neither failure history nor pending/final state. Exhausted
ACTIVE budget is finalized by Item dispatch acquire.

A future trusted pre-execution-rejection policy may accelerate Item retry only
after the opaque Item claim fence is carried to a TaskItem score-owner exact
release operation. Adapter crash, timeout, missing response, and other
`UNKNOWN` evidence cannot release an Item claim early.

## 4. Worker-Runtime Boundary Rules

WorkerGroup is a stable scheduling entry boundary. Task creation/admission
selects a worker group; task/item payloads do not scan all worker groups.

Worker score is an acquisition coordinate, not worker lifecycle truth:

```text
HOT_ACQUIRE
  positive score; worker may enter hot worker admission after validation

RECOVERY_RECHECK
  negative score; recovery validation lane, not a worker selection lane
```

Hot score lease rules:

```text
acquire_hot_acquire_candidates(..., limit)
  read-only bounded due HOT_ACQUIRE query
  returns workerId -> observedScore mapping to allocation pacer
  scan order is not part of the public contract

acquire_observed_hot_score_leases(...)
  before matching, batches one exact CAS per observed Worker
  preserves laneRank and clears dirty while writing each future lease

renew_active_hot_score_leases(...)
  requires clean active HOT_ACQUIRE observed score
  exact-validates an already sufficient lease or extends it
  returns an independent result per Worker and STALE on dirty

dispatch disposition
  validates/renews the exact active fence before Item claim
  rejects dirty/recovery/expired/stale candidates without compensation release
  result-routing Worker handlers exact-release `200/1xxx` fences and move
  `3xxx` fences to negative polarity

connect/reconnect
  existing score preserves timeSlot/laneRank, converges positive, dirty=1
  first score initializes positive, dirty=0

RECOVERY_RECHECK
  must not pass either hot lease primitive
```

`observedScore` remains an opaque full-score fence for batched lease, active
renewal, release, polarity move, and recovery exhaustion. HOT query returns the
observation only to the allocation pacer sidecar; matcher sees Worker ids only.
Do not decode, trim, construct, or reinterpret observed scores outside worker
score logic.

Dirty is an assignment-continuation stale hint, not a worker-global version:

```text
dirty = 1 means cached match/admission facts may be stale
dirty is meaningful only while a real assignment plan / hot score lease
continuation can observe it
```

`validationDependencySet` is conceptual evidence for future dirty decisions.
Do not implement it by adding a global scan, reverse plan query, or dynamic
attribute owner that searches assignment state. If dirty execution is needed,
the assignment-plan owner must provide the owner-local lookup/index.

## 5. Proof Discipline

Do not jump directly to a task -> worker -> transport -> result closure proof
unless explicitly requested. Prove one owner plane first.

Worker-runtime proof should stop at worker-runtime behavior:

```text
upsert WorkerGroupDescriptor
upsert WorkerDeclaration
initialize HOT_ACQUIRE score
acquire HOT_ACQUIRE candidates
acquire unchanged candidates through exact observed-score CAS
match bounded WorkerCandidateConstraint maps
retain unmatched HOT leases until natural expiry
renew_active_hot_score_leases
release_score_holds / rewrite_current_scores
RECOVERY_RECHECK path
dirty stale behavior
```

Task-runtime proof should stop at Task score, Task Item, and result-owner behavior
unless a separate plan explicitly crosses into worker-runtime or transport.

Fast Python validation:

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
git diff --check -- kernel_design
```

Real Redis TaskResourceCatalog proof requires a reachable Redis URI:

```text
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
python -m unittest \
  kernel_design.executable_spec.tests.test_redis_task_runtime_integration
```

For focused worker-runtime checks:

```text
python -m unittest \
  kernel_design.executable_spec.tests.test_worker_runtime_contract \
  kernel_design.executable_spec.tests.test_redis_worker_score
```

## 6. Guardrails

- Do not copy historical Java engine/module shapes into clean-kernel design.
- Do not create bridges, facades, or wrappers unless they protect a real owner
  boundary or executable-spec seam.
- Do not add scan-heavy observability or reconciliation loops to hot paths.
- Do not make external events required for correctness or ordinary liveness.
- Do not let append, heartbeat, transport ack, result notification, trace, or
  read-model update become scheduling wakeups by default.
- Do not put transport identifiers into scheduling candidate truth.
- Do not make score-band a read model, storage blob, or lifecycle facade.
- Do not require a score read, lease, or rewrite before resource metadata,
  dynamic attribute, Item append, result/evidence, projection, or trace
  mutation.
- The default server/SDK ingress calls `TaskRuntime.append_items` directly;
  do not require a server backlog, outbox, broker, or periodic materializer.
- Treat append success as canonical Item acceptance only. Do not infer that
  the Task is live, schedulable, guaranteed to consume the item, or required to
  reopen. Ingress owns append eligibility; retention owns terminal residue.
- Do not make kernel append compensate for stale server intake decisions. A
  late append after terminal may be discarded and must never refresh or reopen
  task score.
- Do not let a resource mutation become a generic score refresh. Worker dirty
  may only be an additional bounded stale hint for a real continuation and must
  never gate or roll back the resource write.
- Do not create a second routine writer for an acquirable score. Scheduling owns
  cadence/budget rewrites; command handlers are limited to explicit lifecycle
  transitions.
- Do not make worker score own task demand, task backlog, or final result.
- Do not make Task score own Item score claim/retry/outcome movement or result
  finality classification.
- Do not introduce a second hot-path candidate index without naming its owner,
  lifecycle, update discipline, and deletion path.
- Do not treat `doc/runtime-redis/*shape.md` as public API unless a current
  executable spec explicitly adopts it.

## 7. Documentation Rules

- Keep mechanism design under `kernel_design/`.
- Keep phase plans and migration plans out of `kernel_design/`; use an explicit
  new-kernel executable-spec plan when execution begins.
- Update Python tests when a Python interface contract changes.
- Delete or rewrite stale design text instead of preserving parallel narratives.
- Delete superseded Redis or shape notes after current contracts and links have
  been migrated; do not retain a parallel historical design narrative.
- When executable code makes a design fact current kernel truth, migrate it to
  the owning contract and delete the superseded narrative after link cleanup.
