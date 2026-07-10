# Kernel Design Agent Handoff

Status: local handoff for `kernel_design/`.

This directory is the clean-kernel design workspace. It is not current Java
implementation truth, not a Java migration roadmap, and not acceptance proof
for the existing platform. Use it to align new-kernel mechanisms and future
Python executable specs.

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

For task score-band work:

1. [scheduling/task-score-band-scheduling.md](scheduling/task-score-band-scheduling.md)
2. [runtime-redis/score-band-task-runtime-redis-shape.md](runtime-redis/score-band-task-runtime-redis-shape.md)
3. [py_example/kernel/task_score_band.py](py_example/kernel/task_score_band.py)
4. [py_example/runtime_redis/task_score_band_zset.py](py_example/runtime_redis/task_score_band_zset.py)
5. [py_example/tests/test_redis_zset_task_score_band.py](py_example/tests/test_redis_zset_task_score_band.py)

For result or dispatch work:

1. [scheduling/assignment-dispatch-scheduling.md](scheduling/assignment-dispatch-scheduling.md)
2. [scheduling/result-routing-scheduling.md](scheduling/result-routing-scheduling.md)

## 3. Owner Map

Task score-band owns:

```text
task scheduling visibility
task score-state interpretation
bounded task acquire / recheck primitives
```

It does not own item append, work hash claim, worker selection, transport
delivery, or result finality.

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
  bounded dynamic attribute update ingress only

WorkerCandidateMatcher
  one worker group, bounded worker id batch, ordered candidate constraints

WorkerScoreCore
  HOT_ACQUIRE / RECOVERY_RECHECK score acquisition and transitions
```

Assignment-dispatch owns:

```text
one scheduling-round composition
candidate ranking after worker-runtime matching
short assignment plan evidence
work hash claim timing
deliver seed creation
```

It does not own task lifecycle truth, worker lifecycle truth, result finality,
or transport session internals.

Result-routing owns:

```text
current work hash compare
finality / retry / duplicate / stale / unresolved classification
owner-specific handoffs after result acceptance
```

It must not select workers, parse transport sessions as truth, or refresh task
or worker score as a generic side effect.

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
acquire_due_hot_score_lease(...)
  requires observed score to be due HOT_ACQUIRE
  may clear dirty only after current validation

renew_active_hot_score_lease(...)
  requires clean active HOT_ACQUIRE observed score
  returns STALE on dirty

RECOVERY_RECHECK
  must not pass either hot lease primitive
```

`observedScore` is an opaque full-score fence. Do not decode, trim, construct,
or reinterpret it outside worker score logic.

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
match bounded WorkerCandidateConstraint maps
acquire_due_hot_score_lease
renew_active_hot_score_lease
release_score_hold / rewrite_current_score
RECOVERY_RECHECK path
dirty stale behavior
```

Task-runtime proof should stop at task score/backlog/result owner behavior
unless a separate plan explicitly crosses into worker-runtime or transport.

Fast Python validation:

```text
python -m unittest discover -s kernel_design/py_example/tests
python -m compileall -q kernel_design/py_example
git diff --check -- kernel_design
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
- Do not make worker score own task demand, task backlog, or final result.
- Do not make task score own work hash claim, retry frames, or result finality.
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
