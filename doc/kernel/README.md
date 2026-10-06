# XA Mass Java Kernel

Status: current Kernel authority and documentation entrypoint.

Follow the root [reading path](../../README.md#reading-path) for Runtime orientation.
This index locates mechanical Owners and Pacer policy. The complete
[behavior model](scheduling-overview.md#system-behavior-model) owns cross-owner
flow and work/resource feedback. Use the affected Owner below and the mainline's
[production and proof pointers](scheduling-overview.md#production-and-proof-pointers)
to trace the affected caller, assembly and assertion.

## Trust Order

[AGENTS](../../AGENTS.md#trust-order) defines the applicable source/test authority,
change rules and version-scoped use of historical evidence.

## Owner Documents

Mechanical Owner documents:

- [Task Resource Model](../../kernel_jvm/doc/resource-model/task-resource-model.md)
- [Worker Resource Model](../../kernel_jvm/doc/resource-model/worker-resource-model.md)
- [Task Score](../../kernel_jvm/doc/score/task-score-band-scheduling.md)
- [TaskItem Score](../../kernel_jvm/doc/score/task-item-score-band-scheduling.md)
- [Worker Score](../../kernel_jvm/doc/score/worker-score-band-scheduling.md): private high-mark encoding, candidate generations, network polarity transitions, execution acquisition and scheduling controls
- [HOT Lease Protocol](../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
- [Redis Keyspace](../../kernel_jvm/doc/runtime-redis/redis-keyspace.md)
- [Task Evidence And Result Redis Shape](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
- [Worker Runtime Redis Shape](../../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md)
- [Worker Serviceability Redis Shape](../../kernel_jvm/doc/runtime-redis/worker-serviceability-runtime-redis-shape.md)
- [Worker Matching Owner](../../worker_matching_jvm/README.md)

Policy and lifecycle documents:

- [Pacer Application Assembly](../../kernel_pacer_jvm/doc/application-assembly.md)
- [Assignment and Dispatch](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md)
- [Task Initialization](../../kernel_pacer_jvm/doc/dispatch/task-initialization-policy.md)
- [Task Dispatch](../../kernel_pacer_jvm/doc/dispatch/task-dispatch-pacer.md)
- [Worker Serviceability](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
- [Result Convergence](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)

Cross-module documents:

- [Scheduling Mainline](scheduling-overview.md)
- [Worker Delivery Boundary](worker-delivery-dispatch.md)
- [Repository Architecture](../../README.md)
- [Proof Lanes](../../TESTING.md)

## Cross-Owner Reading

Use [Scheduling Mainline](scheduling-overview.md) for work/resource flow and scale,
and [Worker Delivery Boundary](worker-delivery-dispatch.md) for Command/observation
handoffs and failure windows. Local transitions, storage and lifecycle stay with
the Owners above.

## Verification

Select Owner, Redis, Runtime Boundary or end-to-end proof through
[TESTING.md](../../TESTING.md#lane-index). The Java public contract snapshot is
guarded by
[`kernel_owner_contract_manifest.json`](../../kernel_jvm/src/test/resources/kernel_owner_contract_manifest.json).

Python Kernel历史实现可在
`python-kernel-verification-final-2026-08-28` Tag中查看。
