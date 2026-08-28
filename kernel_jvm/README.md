# XA Mass Kernel JVM

Status: stable JVM Kernel owner contracts and mechanical Redis providers.

`kernel_jvm` owns the production-call closure of the Java Kernel mechanical
layer. It provides durable contracts and Redis state transitions, not
scheduling policy or background loops. Unsupported provider operations fail
with `KernelOperationNotImplementedException` rather than a no-op or fallback.

## Ownership

| Package | Responsibility |
| --- | --- |
| `task` | Task record, catalog, lifecycle, bounded Task Call commands and finite TaskItem result events |
| `worker` | Worker record, catalog, canonical properties, opaque lease references and finite execution/serviceability events |
| `score` | Task, TaskItem and Worker score contracts plus exact Redis transitions |
| `assignment` | Candidate Worker Cache owner |
| `delivery` | Worker Command and Task Result runtimes plus internal ResultContext codec |
| `serviceability` | Adapter probe/evidence handoff owner |
| owner-local `redis` packages | Redis implementations for the matching owner only |

Candidate Cache stays here because it is a bounded, disposable owner mechanism
with its own Redis shape. Candidate matching,
allocation policy, result disposition, serviceability policy, Pacer loops and
thread lifecycle belong to [`kernel_pacer_jvm`](../kernel_pacer_jvm/).

The three Result event interfaces are stable semantic Mechanism ports rather
than Pacer policy or new truth owners. Their default implementations may
compose bounded mechanical owners, while `DeliveryReport`, lane identity, JSON
and Adapter Event Names remain confined to `kernel_pacer_jvm` policies.
`WorkerLeaseReference` keeps the assignment fence opaque outside the Worker
owner package.

## Production Call Closure

The Java providers implement the operations currently called by the Runtime
API and the fixed production Pacers, including:

- Task create/lifecycle/Call submission, Item record access and Result storage;
- Task, TaskItem and Worker score range reads, exact CAS transitions, leases,
  idle park/close, final promotion and completed-HOT release;
- Worker registration, canonical descriptors and bounded property reads;
- authoritative and non-overwriting Worker Command writes plus bounded
  consume;
- explicit SUCCESS/FAILURE Task Result append/consume without error-code policy;
- Candidate Cache operations;
- Serviceability probe request offer/consume and evidence append/consume.

These implementations preserve existing Redis keys, score encoding, Redis
time semantics, bounded inputs and partial-result behavior. Redis-sensitive
claims require the named real-Redis proof in [`TESTING.md`](../TESTING.md).
Operations outside the production caller closure remain explicit gaps.

## Boundaries

`kernel_jvm` has no Spring, HTTP, Pacer thread or policy configuration
dependency. It never depends on `kernel_pacer_jvm` or `server_jvm`.

The
[`kernel_owner_contract_manifest.json`](src/test/resources/kernel_owner_contract_manifest.json)
is the Java public-contract snapshot guard. It is not source generation or an
external protocol. There is no remote fallback or dual owner.

Current mechanical Owner documents live under [`doc/`](doc/); the complete
Kernel index is [`doc/kernel/README.md`](../doc/kernel/README.md).

Build:

```text
./gradlew :kernel_jvm:build
```
