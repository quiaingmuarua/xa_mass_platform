# XA Mass Java Kernel

Status: current Kernel authority and documentation entrypoint.

The Java Kernel is split by rate of change, not by authority:

```text
kernel_jvm
  stable mechanical Owners, Redis providers, Scores and resources

kernel_pacer_jvm
  scheduling Policy, convergence loops and finite lifecycle

server_jvm
  Spring assembly and public Runtime API
```

Only the first two modules decide or converge scheduling. Server validates,
routes and exposes those owners; Transport delivers already-targeted Commands
and executes endpoint-local handlers.

## Trust Order

1. Java production Owner and Pacer code.
2. Focused JVM tests and real Redis proofs.
3. Current Owner documents linked below.
4. Runtime Boundary and end-to-end acceptance proofs.
5. Historical tags only as failure-mode evidence.

When code and a current document disagree, repair the document or the
implementation in the same change. A historical snapshot is never current
runtime truth.

## Owner Documents

Mechanical Owner documents:

- [Task Resource Model](../../kernel_jvm/doc/resource-model/task-resource-model.md)
- [Worker Resource Model](../../kernel_jvm/doc/resource-model/worker-resource-model.md)
- [Task Score](../../kernel_jvm/doc/score/task-score-band-scheduling.md)
- [TaskItem Score](../../kernel_jvm/doc/score/task-item-score-band-scheduling.md)
- [Worker Score](../../kernel_jvm/doc/score/worker-score-band-scheduling.md)
- [HOT Lease Protocol](../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
- [Redis Keyspace](../../kernel_jvm/doc/runtime-redis/redis-keyspace.md)
- [Task Result Redis Shape](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
- [Worker Runtime Redis Shape](../../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md)
- [Worker Serviceability Redis Shape](../../kernel_jvm/doc/runtime-redis/worker-serviceability-runtime-redis-shape.md)

Policy and lifecycle documents:

- [Pacer Application Assembly](../../kernel_pacer_jvm/doc/application-assembly.md)
- [Assignment and Dispatch](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md)
- [Task Initialization](../../kernel_pacer_jvm/doc/dispatch/task-initialization-policy.md)
- [Worker Allocation](../../kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md)
- [Task Dispatch](../../kernel_pacer_jvm/doc/dispatch/task-dispatch-pacer.md)
- [Worker Serviceability](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
- [Result Convergence](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)

Cross-module documents:

- [Scheduling Mainline](scheduling-overview.md)
- [Worker Delivery Boundary](worker-delivery-dispatch.md)
- [Repository Architecture](../../README.md)
- [Proof Lanes](../../TESTING.md)

## Stable Boundaries

- Task, TaskItem and Worker score truth are independent.
- A Score is an opaque scheduling coordinate, not a resource write lock.
- Mechanical Owners define legal state transitions and exact fences.
- Policy owns bounded selection, priority, deficits, matching and cadence.
- Result Policy parses and groups delivery evidence, then publishes finite
  semantic events to the owning Task or Worker mechanism.
- Result Convergence and Dispatch Convergence are the only production Pacer
  applications. They are assembled through `KernelPacerRuntime`.
- Server never selects a Worker, leases a Score, claims an Item, or decides
  retry, recovery or Task finality.
- Transport never interprets scheduling policy.

The scheduling scale contract is deliberately vertical: a small bounded active
Task set may contain many TaskItems and use many Workers inside finite
WorkerGroups. The liveness target is work-conserving convergence, not per-Task
fairness. Fully utilized compatible Workers are normal backpressure; a defect
exists when persistently due work and persistently idle compatible Workers
cannot form an assignment across repeated eligible rounds.

## Verification

Deterministic owner and Pacer checks:

```text
./gradlew :kernel_jvm:test :kernel_pacer_jvm:test
```

Redis concurrency, Runtime Boundary and end-to-end claims require their named
lanes in [TESTING.md](../../TESTING.md). The Java public contract snapshot is
guarded by
[`kernel_owner_contract_manifest.json`](../../kernel_jvm/src/test/resources/kernel_owner_contract_manifest.json).

Python Kernel历史实现可在
`python-kernel-verification-final-2026-08-28` Tag中查看。
