# Worker Delivery Boundary

Status: active cross-owner delivery and failure-boundary contract.

Delivery carries already-assigned work. It does not select Workers, claim
TaskItems, mutate Scores or decide Result truth. This document owns the
handoffs between Kernel, Server and Transport; the local mechanisms are linked
below rather than reproduced here.

## Owners And Contracts

| Boundary | Canonical contract |
| --- | --- |
| Command/Report structure, direction and codecs | [Worker Delivery Contract](../../transport/worker-delivery-contract/README.md) |
| Task pairing, renewal, claim and publication | [Assignment Dispatch](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md) |
| Identity, Prepare, Binding, HTTP and DIRECT_CALL | [Runtime API Server](../../server_jvm/README.md) |
| Route verification, retention, delivery queues and network lifecycle | [Netty Adapter](../../transport/netty-adapter/README.md) |
| Event execution, identity protocol and run lifecycle | [Worker Core](../../transport/worker-core/README.md) |
| Platform Event Names and payload contracts | [Event Catalog](../../transport/EVENTS.md) |
| TASK semantic parsing and publication | [Result Policy](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md) |
| Result storage and finality interruption windows | [Result Redis Shape](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md) |

## Identity And Address

Prepare resolves a Server-owned Worker identity according to its Worker kind,
persists Endpoint Binding, refreshes Matching facts and initializes minimal
Kernel resources. The ordered writes are not a cross-owner transaction.
Neither Prepare nor Binding proves that a physical connection is live.

A long-lived connection sends its identity Report first. Adapter verification
crosses the injected single-item Server port, whose owner compares persisted
Binding with the receiving endpoint. The Adapter owns pending verification
and the exact current Channel. Disconnected verification evidence may be
retained within the Adapter's TTL/capacity bounds; active routes cannot be
cache-evicted. Reconnect after evidence expiry requires verification again.
Identity and retained verification are not authentication or schedulability.

The assignment-time endpointManagerId selects the mailbox bucket; its workerId
field selects the Worker. Those addresses stay outside DeliveryCommand.
Adapter-local commands use dst for routing, and their response-map keys are
opaque. A Channel's claimed workerId is callback correlation, not copied
verification truth. Connection and Properties observations remain separate
projections without an atomic join or shared version.

## TASK Handoff

```text
Kernel exact Worker renewal -> exact Item claim -> targeted DeliveryCommand
  -> Adapter-partitioned Worker mailbox
  -> Server point or bounded Adapter consume
  -> Adapter current route -> Worker event execution
  -> DeliveryReport -> Server destination routing -> Kernel semantic events
```

The Worker Command Owner supports authoritative append, which may replace an
occupied field, and non-overwriting offer. Point and batch consume compete for
the same fields. Batch acquisition uses bounded random observation followed by
exact compare-delete; it is not FIFO or stable enumeration. A concurrent
replacement is preserved, but consumed commands have no pending/ack state.
Expired or corrupt mailbox values are removed without delivery.

The mailbox ABI and operation contract are defined by
[WorkerCommandRuntime](../../kernel_jvm/src/main/java/com/xa/mass/kernel/delivery/WorkerCommandRuntime.java)
and the [Redis Keyspace](../../kernel_jvm/doc/runtime-redis/redis-keyspace.md).
Only already-claimed Commands reach this boundary. Delivery cannot renew a
lease, choose another Worker or fabricate a new Task attempt.

Transport copies opaque payload and forward context through to its downstream
owner. Worker execution resolves the complete Event Name in its immutable
Definitions; src is evidence, not a Handler lookup key. The execution deadline
is checked before start and does not preempt an admitted synchronous Handler.
A physical disconnect may reconnect; an endpoint termination ends the current
Worker run and requires a later explicit Host start.

## DIRECT_CALL And Serviceability

DIRECT_CALL uses caller-selected targets and Server-local admission/correlation.
Worker targets use a non-overwriting offer to the shared mailbox; Adapter
commands use a Server-memory FIFO. TASK append may replace an unconsumed
Worker Direct Command. Neither authority can recall a consumed command, and
Direct Call does not provide scheduling exclusion or Handler preemption.

Server exposes the Adapter Direct FIFO prefix, consumes the shared Worker
mailbox once with remaining capacity, and only then may add a Kernel
Serviceability snapshot Command. Source priority does not reorder a Command
already consumed by Transport.

Optional Kernel Serviceability supplies bounded Adapter probe requests and
consumes Adapter Route, snapshot and delivery-expiry evidence. Transport emits
that evidence without interpreting Score policy. Expired TASK delivery offers
its TASK rejection and KERNEL evidence independently; cross-lane admission is
not atomic. Detailed semantics belong to
[Serviceability Policy](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md).

## Report Handoff

Server rejects mixed or unsupported destination batches before semantic Owner
side effects. A homogeneous batch routes to exactly one owner:

- TASK: producer/code validation selects the Kernel success or failure lane.
- SYSTEM: the Server Direct Call waiter consumes correlated evidence.
- KERNEL: the Serviceability handoff validates path-consistent Adapter evidence.

Kernel Result Policy parses and groups evidence, then invokes semantic TaskItem
and Worker event ports. It does not expose DeliveryReport or JSON to those
ports, and Transport never decodes the scheduling context. The owning Result
and Score operations decide whether consumed late/duplicate evidence applies.

## Failure Windows

| Interruption | Consequence and existing recovery |
| --- | --- |
| Worker renewal succeeds, Item claim or publication fails | No compensation release; Worker lease and Item claim expiry restore eligibility |
| Mailbox consume succeeds, response or Adapter process is lost | Command may be lost; there is no transport replay owner |
| No current route before send | Adapter defers within its bounded delivery mechanism; expiry may emit TASK rejection |
| Physical send starts and later fails | Delivery is UNKNOWN; do not infer pre-execution rejection |
| Worker Result send fails, Adapter queue is full or process exits | Evidence may be lost; no durable Worker/Adapter Result store |
| TASK Report remote submission is unavailable | Adapter returns the batch to its TASK queue tail; an ambiguous response can cause duplicates |
| SYSTEM/KERNEL Report submission fails | Batch is dropped; no retry or cross-lane atomicity |
| Server accepting a Direct Call exits or another replica receives the Result | Instance-local waiter/FIFO requires Server affinity; no distributed correlation recovery |
| Kernel consumes evidence before all Result/finality/release calls finish | Independent writes may be partially applied; no unconditional Result-to-Score repair |

This is a bounded best-effort delivery boundary that permits both loss and
duplication. Local TASK retransmission does not establish an overall
at-least-once guarantee. Lease expiry recovers eligibility, not lost evidence
or an already-stored Result's separate finality transition. ACK, durable
pending/ack, exactly-once execution, Adapter HA and endpoint migration require
separate contracts.
