# XA Mass Java Kernel

Status: current Kernel authority and documentation entrypoint.

The Java Kernel is split by rate of change, not by authority:

```text
kernel_jvm
  stable mechanical Owners, Redis providers, Scores and resources

kernel_pacer_jvm
  scheduling Policy, convergence loops and finite lifecycle

worker_matching_jvm
  Worker facts, PRECOMPUTED Candidate Rules and bounded identity evidence

server_jvm
  Spring assembly and public Runtime API
```

Only Kernel chooses resources or converges scheduling. Worker Matching
interprets Worker facts and rules but returns evidence rather than a decision.
Server validates, coordinates writes, assembles lifecycles and exposes those
owners; Transport delivers already-targeted Commands and executes
endpoint-local handlers.

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
- [Worker Matching Owner](../../worker_matching_jvm/README.md)

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

## Cross-Owner Reading

[Scheduling Mainline](scheduling-overview.md) explains the independent Score
owners, Matching handoff, Result evidence and vertical scale boundary.
[Worker Delivery Boundary](worker-delivery-dispatch.md) follows already-decided
Commands and returning evidence through Server and Transport. The Owner links
above maintain the detailed transitions, storage and policies.

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
