# Kernel Design Agent Handoff

Status: local handoff for `kernel_design/`.

This directory is the clean-kernel design workspace. It is not current Java
implementation truth, not a Java migration roadmap, and not acceptance proof
for the existing platform. Use it to align new-kernel mechanisms and the
current Python executable spec.

## 0. TL;DR

- `kernel_design/` is isolated from the current Java implementation.
- Current Java code is useful only as failure-mode evidence, invariant input,
  or anti-pattern context.
- Do not cite `kernel_design/` from current Java docs, active roadmaps, proof
  registries, or runbooks as implementation truth.
- The design target is a small strict kernel, not a simplified weak kernel.
- Owner boundaries matter more than closing a broad demo loop.
- Prefer owner-local executable proof before crossing into another runtime.

Core kernel shape:

```text
task score-band
  -> worker score-band
  -> assignment-dispatch
  -> result-routing
```

These are scheduling planes, not necessarily modules. A first Python kernel may
implement them in one package if owner truth remains explicit.

## 1. Trust Order

Use this order inside `kernel_design/`:

1. Python executable spec code and tests under `py_example/`
2. Current local design docs under `scheduling/`, `resource-model/`, and
   `runtime-redis/`
3. Superseded shape docs only when their status says they are retained as
   historical context
4. Current Java implementation only as legacy/failure-mode context

If a design doc and Python executable spec disagree, describe the gap and do
not silently bend one into the other.

This trust order describes current behavior; it does not authorize code to
override an already aligned interface contract. If code and an agreed contract
diverge, stop and identify which one is stale before editing either side.

## 2. First Read

For general kernel work:

1. [README.md](README.md)
2. [scheduling/README.md](scheduling/README.md)

For worker-runtime work:

1. [resource-model/worker-resource-model.md](resource-model/worker-resource-model.md)
2. [scheduling/worker-score-band-scheduling.md](scheduling/worker-score-band-scheduling.md)
3. [scheduling/assignment-dispatch-scheduling.md](scheduling/assignment-dispatch-scheduling.md)
4. [py_example/kernel/worker_runtime.py](py_example/kernel/worker_runtime.py)
5. [py_example/kernel/worker_score.py](py_example/kernel/worker_score.py)
6. [py_example/runtime_redis/worker_score_zset.py](py_example/runtime_redis/worker_score_zset.py)
7. [py_example/tests/test_worker_runtime_models.py](py_example/tests/test_worker_runtime_models.py)
8. [py_example/tests/test_redis_zset_worker_score.py](py_example/tests/test_redis_zset_worker_score.py)

For task runtime or task score-band work:

1. [resource-model/task-resource-model.md](resource-model/task-resource-model.md)
2. [scheduling/task-score-band-scheduling.md](scheduling/task-score-band-scheduling.md)
3. [scheduling/task-item-score-band-scheduling.md](scheduling/task-item-score-band-scheduling.md)
4. [py_example/kernel/task_runtime.py](py_example/kernel/task_runtime.py)
5. [py_example/kernel/task_score_band.py](py_example/kernel/task_score_band.py)
6. [py_example/kernel/task_item_score_band.py](py_example/kernel/task_item_score_band.py)
7. [py_example/runtime_redis/task_item_score_band_zset.py](py_example/runtime_redis/task_item_score_band_zset.py)
8. [py_example/runtime_redis/task_runtime.py](py_example/runtime_redis/task_runtime.py)
9. [py_example/runtime_redis/task_score_band_zset.py](py_example/runtime_redis/task_score_band_zset.py)
10. [py_example/tests/test_task_runtime_models.py](py_example/tests/test_task_runtime_models.py)
11. [py_example/tests/test_task_item_score_band_models.py](py_example/tests/test_task_item_score_band_models.py)
12. [py_example/tests/test_redis_zset_task_item_score_band.py](py_example/tests/test_redis_zset_task_item_score_band.py)
13. [py_example/tests/test_redis_task_item_score_band_integration.py](py_example/tests/test_redis_task_item_score_band_integration.py)
14. [py_example/tests/test_redis_task_runtime.py](py_example/tests/test_redis_task_runtime.py)
15. [py_example/tests/test_redis_task_runtime_integration.py](py_example/tests/test_redis_task_runtime_integration.py)
16. [py_example/tests/test_redis_zset_task_score_band.py](py_example/tests/test_redis_zset_task_score_band.py)

For result or TaskItem dispatch:

1. [resource-model/task-resource-model.md](resource-model/task-resource-model.md)
2. [scheduling/task-item-score-band-scheduling.md](scheduling/task-item-score-band-scheduling.md)
3. [scheduling/assignment-dispatch-scheduling.md](scheduling/assignment-dispatch-scheduling.md)
4. [scheduling/task-worker-allocation-pacer.md](scheduling/task-worker-allocation-pacer.md)
5. [scheduling/task-item-dispatch-pacer.md](scheduling/task-item-dispatch-pacer.md)
6. [py_example/kernel/task_worker_allocation.py](py_example/kernel/task_worker_allocation.py)
7. [py_example/kernel/task_dispatch_runtime.py](py_example/kernel/task_dispatch_runtime.py)
8. [scheduling/result-routing-scheduling.md](scheduling/result-routing-scheduling.md)

## 2.1 Interface Change Gate

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
```

TaskItemScoreBandCore owns:

```text
initial ACTIVE Item score creation
bounded ACTIVE Item-score acquire
same-tag claim/retry rewrite through exact observed-score fencing
strict-tag ACTIVE < FINAL_FAILED < FINAL_SUCCESS outcome promotion
```

`TaskItem` is the only runtime unit from append through finality. Claiming it
does not create a `Work` / `WorkItem` model, id, store, runtime, or owner.

Item append callers provide `TaskItem` values only. Append scheduling policy
maps Item priority to initial due milliseconds; Task config owns
`maxRetryTimes`. TaskRuntime passes those stable initialization inputs to
TaskItemScoreBandCore, which converts time and budget to internal coordinates.
Tag, timeSlot, suffix, score bounds, and initial score never cross the append
API.

Score is not a resource mutation lock. Task/worker metadata writes, dynamic
attribute writes, item append, result/evidence writes, projections, and trace
must not acquire or refresh score. Initialization establishes the first score;
the active scheduling plane is the only routine writer for acquirable scores;
explicit lifecycle commands may invoke only declared approve/reject/pause/
resume/close or availability transitions.

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
  constraints, and exclusive matched/unmatched id partition

WorkerScoreCore
  bounded HOT observation, batched exact-score lease, RECOVERY_RECHECK acquisition, and score transitions
```

Assignment-dispatch owns:

```text
one scheduling-round composition
candidate ranking after worker-runtime matching
short assignment plan evidence
Item score claim timing
deliver seed creation
```

It does not own task lifecycle truth, worker lifecycle truth, result finality,
or transport session internals.

Result-routing owns:

```text
retryable / final-failure / final-success business classification
opaque claimScore pass-through to TaskItemScoreBandCore
Task Item transition-result mapping to routing outcomes
late-success retention barrier and result projection
owner-specific handoffs after result acceptance
```

It must not interpret current Item score, reproduce same-tag/cross-tag rules,
select workers, parse transport sessions as truth, or refresh task or worker
score as a generic side effect.

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
  returns an independent result per Worker and STALE on dirty

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
register WorkerGroupDescriptor
register WorkerDescriptor
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
python -m unittest discover -s kernel_design/py_example/tests
python -m compileall -q kernel_design/py_example
git diff --check -- kernel_design
```

Real Redis TaskResourceCatalog proof requires a reachable Redis URI:

```text
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
python -m unittest \
  kernel_design.py_example.tests.test_redis_task_runtime_integration
```

For focused worker-runtime checks:

```text
python -m unittest \
  kernel_design.py_example.tests.test_worker_runtime_models \
  kernel_design.py_example.tests.test_redis_zset_worker_score
```

## 6. Guardrails

- Do not copy current Java engine/module shapes into new-kernel design.
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
- Do not treat `runtime-redis/*shape.md` as public API unless a current
  executable spec explicitly adopts it.

## 7. Documentation Rules

- Keep mechanism design under `kernel_design/`.
- Keep phase plans and migration plans out of `kernel_design/`; use an explicit
  new-kernel executable-spec plan when execution begins.
- Update Python tests when a Python interface contract changes.
- Delete or rewrite stale design text instead of preserving parallel narratives.
- Label superseded Redis or shape notes clearly when they are no longer active.
- If a future executable spec makes a design fact current kernel truth, migrate
  that fact to the owning kernel-core documentation and leave the old design
  note as context.
