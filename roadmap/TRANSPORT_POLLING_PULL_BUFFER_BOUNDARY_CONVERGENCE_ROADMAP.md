# Transport Adapter Mailbox And Polling Internal Buffer Boundary Convergence Roadmap

Status: proposed direction document.

## Summary

Remove the current `TransportDeliveryStore` concept from transport core and
move polling-specific buffering back behind the polling adapter boundary.
Polling workers and WebSocket/socket workers must look the same to transport:

```text
engine/worker-runtime selects selectedWorkerId
transport offers item to adapterMailboxKey
adapter consumes mailbox item
adapter uses selectedWorkerId to find its local worker endpoint
worker result enters result queue
engine consumes result queue for final/retry/reassign
```

Task identity, task result reads, retry, and worker pinning are engine/task
concerns. If a caller wants a specific worker, it belongs in task/dispatch
configuration and the engine dispatches to that `selectedWorkerId`. Results are
observed through task/result identity, not through adapter or worker network
model.

The current store name and API make it look like a general transport delivery
queue:

```java
enqueue(adapterMailboxKey, item)
drain(adapterMailboxKey, selectedWorkerId, maxItems)
poll(adapterMailboxKey, selectedWorkerId, maxItems, timeout, unit)
```

That shape is wrong for an adapter mailbox queue. An adapter mailbox consumer
must consume all accepted items for its mailbox according to queue ordering and
priority rules. It must not choose a worker id and selectively consume matching
items from the mailbox queue.

Correct model:

```text
TransportDispatchHandoff / AdapterMailboxQueue
  producer -> adapter mailbox
  offer(adapterMailboxKey, items)
  adapter runtime consumes by adapterMailboxKey only
  item.selectedWorkerId is final-hop demux input

Polling adapter internal pending-delivery buffer
  private protocol state after adapter mailbox consumption
  adapter places consumed items into selected-worker pull slots
  authenticated polling worker drains only its own adapter-internal slot
```

This roadmap is about removing the false transport-core boundary. Polling
pending delivery may exist, but only as a polling-adapter internal detail, just
like WebSocket session maps are WebSocket-adapter internal details.

This roadmap also records a dependency on a separate adapter-owned mailbox
consumption convergence: in the final model, the adapter runtime owns the
mailbox poll loop. The platform may provide an embedded runner, but the runner
must not turn the platform transport runtime into the semantic owner of adapter
command consumption.

The main mailbox handoff should stay intentionally small. For the current
best-effort transport executor, do not introduce a dispatch `RoutingTarget`,
`DispatchRoutingBatch`, claimed batch, handoff references, `ack`, or
`complete` just to make a uniform carrier. The stable target is direct:

```java
List<DispatchOutcome> offer(String adapterMailboxKey,
                            List<SelectedWorkerDeliveryItem> items);

List<SelectedWorkerDeliveryItem> poll(String adapterMailboxKey,
                                      int maxItems,
                                      long timeout,
                                      TimeUnit unit);
```

`SelectedWorkerDeliveryItem` is a placeholder name for the minimal item DTO.
The exact class name can change, but the rule cannot: mailbox placement is a
method parameter, not a nested routing object, and worker correctness remains
an item field.

## Current Code Observations

- `TransportDispatchHandoff` is the assigned-dispatch mailbox handoff for the
  engine/starter-to-adapter boundary.
- `AdapterMailboxMount` consumes dispatch batches by `adapterMailboxKey` and
  invokes the adapter command executor.
- `AdapterMailboxMount` owns the current embedded same-JVM drain loop. This is
  better than the old starter global pump, but it is still platform-hosted
  command consumption, not adapter-owned mailbox consumption.
- `AdapterCommandExecutor.dispatch(List<DispatchRoutingItem>)` is a final-hop
  callback SPI. It is not an adapter runtime interface and does not let the
  adapter independently pull mailbox work.
- `PollingDeliveryExecutor` uses `AdapterPullDeliveryBuffer` to enqueue
  dispatch items for polling workers; this is polling protocol buffering, not
  a transport-core delivery queue.
- `PollingDeliveryPullChannel` uses `AdapterPullDeliveryBuffer.poll(...)` to
  let a polling worker pull items; that endpoint is a polling-adapter protocol
  surface.
- `TransportDeliveryStore` is used behind `TransportDeliveryService` and
  `AdapterPullDeliveryBuffer`, so its actual role is polling-adapter pending
  delivery storage, not general transport delivery handoff.
- `InMemoryTransportDeliveryStore` keeps one `ArrayDeque<DispatchRoutingItem>`
  per mailbox key and scans/removes matching `selectedWorkerId` values.
- `RedisTransportDeliveryStore` stores one Redis list per mailbox key and uses
  a Lua `LRANGE` scan to find matching `selectedWorkerId`, then tombstones and
  removes those entries.

## Root Cause

This design likely passed because earlier transport models collapsed four
concerns under "delivery":

```text
adapter mailbox queue
embedded host mailbox drain loop
polling adapter internal pending-delivery buffer
selected-worker wrong-consumer prevention
```

The selected-worker filter was trying to prevent polling worker A from taking
worker B's item. That safety requirement is real, but it belongs in the
polling adapter's private pending-delivery buffer, not in the adapter mailbox
queue and not in transport core.

The interface name `TransportDeliveryStore` hid the distinction. Once an API
looked like a generic delivery store, `poll(adapterMailboxKey,
selectedWorkerId)` appeared to be a useful safety check. In reality it made the
store a selective mailbox consumer and forced inefficient scans.

The embedded adapter host naming created a second blind spot. Moving command
drain out of `MassApplication` into `AdapterMailboxMount` made ownership more
local, but it did not make the adapter an independent mailbox consumer. The
adapter still receives a callback after platform-hosted code polls the handoff.
That is acceptable as an interim embedded implementation, but it must not be
documented as adapter independence completion.

## Owner Review

Scheduling owner:

```text
workerGroup / task policy / worker-runtime evidence -> selectedWorkerId
```

Transport dispatch owner:

```text
adapterMailboxKey queue
offer / destructive poll
mailbox handoff semantics
```

Adapter owner:

```text
consume mailbox item by adapterMailboxKey
read item.selectedWorkerId
perform protocol-specific final-hop routing
emit delivery outcomes
```

Embedded adapter runner owner:

```text
start/stop same-JVM adapter runtime resources
provide mailbox consumer client to embedded adapter runtime
must not own adapter scheduling, worker selection, retry policy, or lifecycle
supervision
```

Polling adapter internal buffer owner:

```text
selectedWorkerId pull slot
worker poll session identity validation
return only that worker's already assigned items
private to polling-adapter implementation
```

Engine owner:

```text
retry / reassign / compensation / task attempt timeout
```

## Boundary Decision

`TransportDeliveryStore` must be retired from transport core. It should not be
renamed into another transport-core `PollingWorkerPullBufferStore` unless a
separate owner decision proves a cross-adapter contract. The default target is
to move the pending-by-worker buffer into `polling-adapter` as an internal
protocol component.

`AdapterCommandExecutor` must also be treated as an interim final-hop callback,
not as the final adapter runtime contract. The final adapter-facing contract
should let the adapter runtime own the mailbox consumption loop:

```java
interface AdapterMailboxConsumer {
    List<SelectedWorkerDeliveryItem> poll(int maxItems, long timeoutMillis)
            throws InterruptedException;
}

interface TransportAdapterRuntime {
    void start(AdapterRuntimeContext context);
    void stop();
}
```

For embedded adapters, platform assembly may instantiate and start the
adapter runtime in the same JVM. The important distinction is semantic
ownership: the adapter runtime loop polls the mailbox consumer; platform
transport runtime does not poll and then callback into a passive executor as
the long-term model.

Do not add `complete`/`ack` to the default mailbox consumer contract. Known
offer failures are returned by `offer`; known final-hop failures are emitted as
delivery outcomes/failure evidence by the adapter runtime. If the adapter
process crashes after a destructive poll and before outcome emission, engine
attempt timeout/reassign owns eventual recovery. A reliable broker mode would
need a separate owner decision.

Target internal polling-adapter names:

```text
PollingPendingDeliveryBuffer
InMemoryPollingPendingDeliveryBuffer
RedisPollingPendingDeliveryBuffer
```

Target internal API, if polling adapter still needs a named buffer:

```java
List<DispatchOutcome> enqueue(String adapterMailboxKey,
                              List<SelectedWorkerDeliveryItem> items);

PollingPullResult poll(PollingWorkerPullIdentity identity,
                       int maxItems,
                       long timeout,
                       TimeUnit unit);
```

or, if keeping field-level parameters:

```java
List<DispatchOutcome> enqueue(String adapterMailboxKey,
                              List<SelectedWorkerDeliveryItem> items);

PollingPullResult poll(String adapterMailboxKey,
                       String authenticatedWorkerId,
                       int maxItems,
                       long timeout,
                       TimeUnit unit);
```

This API must live under the polling adapter boundary. The worker parameter
must be documented as authenticated polling worker identity, not adapter
selected-worker query.

## Storage Shape

If the polling adapter keeps a pending-delivery buffer, it must not be a shared
mailbox FIFO scanned by worker id.

Target Redis shape:

```text
polling:<encodedAdapterMailboxKey>:worker:<encodedSelectedWorkerId>:q
  LIST of SelectedWorkerDeliveryItem payloads or item refs

polling:<encodedAdapterMailboxKey>:workers
  SET/ZSET of workers with pending pull items, optional cleanup/diagnostic
```

Target in-memory shape:

```text
mailboxKey -> workerId -> workerPullQueue
```

`adapterMailboxKey` is namespace/owner. `selectedWorkerId` is the polling pull
slot key after the adapter has accepted the item for local polling delivery.
This storage is adapter-internal protocol state, not transport runtime truth.

## Semantics

- Every item accepted into the adapter mailbox handoff must be consumed by an
  adapter mailbox consumer by mailbox key.
- Long-term, that adapter mailbox consumer is owned by the adapter runtime.
  `AdapterMailboxMount` is only an embedded-host transitional helper unless it
  is refactored into a reusable adapter-runtime mailbox client.
- Main mailbox handoff poll is destructive in the default best-effort model.
  It does not expose claim, inflight, ack, complete, visibility timeout, or
  handoff reference semantics.
- Adapter mailbox consumption must not accept `selectedWorkerId` as a poll or
  drain parameter.
- Polling adapter internal buffer may use selected worker id only as
  authenticated requester identity.
- Polling adapter internal enqueue should support batches.
- Wrong-worker prevention happens by writing each consumed item into the
  selected worker's pull slot and by validating the worker poll identity.
- Priority may affect mailbox queue order or pull-slot order, but it must not
  become worker-id selective queue consumption.
- The pull buffer must not decode worker payload.
- The pull buffer must not decide retry, reassign, compensation, worker
  lifecycle, or scheduling.
- Task result observation is task/result owned. Adapter internals must not
  expose result retrieval semantics keyed by adapter, mailbox, or worker
  network identity.

## Non-Goals

- Do not change worker selection.
- Do not change mailbox target evidence ownership.
- Do not claim full adapter independence merely because command drain moved
  from starter global pump into embedded host/mount code.
- Do not solve external adapter IPC, process auth, or deployment in this
  polling pull-buffer roadmap.
- Do not add adapter lifecycle, health, restart, failover, or migration.
- Do not move retry/reassign/compensation into transport.
- Do not redesign external worker public APIs beyond naming/shape needed to
  preserve the same poll behavior.
- Do not preserve the old `TransportDeliveryStore` name as a compatibility
  alias after production callers move.
- Do not replace `TransportDeliveryStore` with another transport-runtime
  polling buffer abstraction unless a later owner decision proves it is a
  cross-adapter contract.
- Do not implement scan-heavy diagnostics as proof of correctness.
- Do not introduce `RoutingTarget`, `DispatchRoutingBatch`,
  `ClaimedDispatchRoutingBatch`, handoff references, `ack`, or `complete` into
  the default mailbox handoff unless a separate reliable-broker roadmap proves
  the owner, cost, and recovery semantics.

## Do Not Start With

Do not start by optimizing the Redis Lua scan.

The problem is not only performance. The current selective scan encodes the
wrong owner boundary. First rename and split the model so the main mailbox
queue consumes by mailbox and the polling pull buffer consumes by authenticated
worker identity.

Do not start by replacing the shared list with per-worker keys while keeping it
under transport runtime. That keeps the conceptual bug hidden and will let the
wrong API leak into future adapter work. First move the pending-delivery buffer
behind the polling adapter boundary.

Do not start by renaming `AdapterCommandExecutor` to make it sound like an
adapter runtime. A passive final-hop callback is not adapter-owned mailbox
consumption. First decide the adapter runtime mailbox consumer contract, then
rename or delete the callback if it is still needed as an embedded adapter
implementation detail.

Do not start by adding `RoutingTarget` or a `DispatchRoutingBatch` wrapper to
the mailbox handoff. For assigned dispatch there is only one physical queue
address in this slice: `adapterMailboxKey`. Wrapping it in a generic target DTO
adds indirection without changing ownership.

## PPB-0 - Inventory And Classification

Goal: prove every current use of `TransportDeliveryStore` is polling-buffer
usage or classify it as residue.

Scope:

- `TransportDeliveryStore`, `InMemoryTransportDeliveryStore`,
  `RedisTransportDeliveryStore`.
- `TransportDeliveryService`, specifically whether it has any non-polling
  caller left.
- `AdapterPullDeliveryBuffer`.
- `PollingDeliveryExecutor`.
- `PollingDeliveryPullChannel`.
- `PollingTransportAdapterBootstrap`.
- `AdapterMailboxMount` and `AdapterCommandExecutor`, to record whether each
  caller treats them as embedded transition or final adapter runtime.
- Public polling API/session tests and server polling integration tests.
- Docs and proof registry entries that describe polling selected-worker
  delivery.

Acceptance:

- Inventory separates adapter mailbox handoff from polling adapter internal
  pending-delivery buffer.
- Inventory records every caller that passes `selectedWorkerId` into store
  drain/poll.
- Inventory records current Redis key shapes and confirms whether any shared
  mailbox FIFO scan remains.
- Inventory identifies tests that currently protect selected-worker
  cross-consumption but not the correct owner boundary.
- Inventory classifies current `AdapterMailboxMount` command drain as
  platform-hosted embedded transition, not final adapter-owned consumption.

## PPB-A - Adapter-Owned Mailbox Consumption Dependency

Goal: keep this polling pull-buffer roadmap aligned with the broader adapter
independence target without implementing external adapter process IPC here.

Scope:

- Define the target adapter runtime consumption contract at the level needed
  by this roadmap:
  - adapter runtime consumes mailbox batches by mailbox key
  - adapter runtime invokes final-hop send or polling pull-buffer enqueue
  - adapter runtime emits delivery outcomes/failure evidence for known
    final-hop results
- Define the default mailbox handoff as best-effort destructive poll:
  - `offer(adapterMailboxKey, items)`
  - `poll(adapterMailboxKey, maxItems, timeout)`
  - no default `ack`, `complete`, claim reference, or visibility timeout
- Classify `AdapterMailboxMount` as either:
  - temporary embedded runner code to be retired, or
  - a mailbox consumer client used by an embedded adapter runtime that owns the
    loop.
- Classify `AdapterCommandExecutor` as final-hop callback residue unless a
  later roadmap proves it should remain as a protocol-specific implementation
  detail.
- Cross-link or create a separate adapter-owned mailbox consumption roadmap.

Acceptance:

- Active docs do not call current `AdapterMailboxMount -> AdapterCommandExecutor`
  callback path full adapter independence.
- The polling pending-buffer target says items enter polling-adapter internal
  state after adapter runtime mailbox consumption, not after platform-owned
  selective drain.
- This roadmap does not require external adapter process IPC to proceed, but it
  does not block that future shape.
- Active docs do not require `RoutingTarget` or `DispatchRoutingBatch` for the
  default assigned-dispatch mailbox handoff.

## PPB-1 - Move Polling Buffer Behind Polling Adapter Boundary

Goal: remove the transport-core polling store boundary before changing storage
internals.

Scope:

- Move or recreate the pending-by-worker buffer under `polling-adapter`.
- Delete `TransportDeliveryStore` / `TransportDeliveryService` from transport
  runtime if no non-polling caller remains.
- Keep existing external behavior initially, but update docs/tests to say this
  is polling adapter local protocol storage.
- Change `enqueue` to batch shape if practical in this slice; otherwise mark
  single-item enqueue as temporary residue in the roadmap.
- Replace parameter names with `authenticatedWorkerId` or
  `pollingWorkerId`, not generic `selectedWorkerId`, on polling APIs where the
  caller is a worker poll request.

Acceptance:

- Production source no longer exposes a transport-core polling delivery store.
- No adapter mailbox handoff API exposes worker-id selective polling.
- The polling pending buffer is not used as an adapter mailbox consumer
  interface and does not live in transport runtime.
- Polling pull tests still prove worker A cannot consume worker B's assigned
  item.
- No compatibility alias preserves the old store name in production code.

## PPB-2 - Storage Shape Pivot To Worker Pull Slots

Goal: remove shared-mailbox scan from polling-adapter internal implementations.

Scope:

- Change in-memory storage to `mailbox -> worker -> queue`.
- Change Redis storage, if still needed for polling adapter internals, to
  per-mailbox/per-worker pull queues.
- Add batch enqueue to write items grouped by selected worker.
- Keep poll by authenticated worker identity.
- Remove Lua `LRANGE` full-list scan and tombstone removal.

Acceptance:

- Redis polling adapter internals do not use `LRANGE 0 -1` over a mailbox queue
  to find a worker's items.
- In-memory polling pull does not scan a shared mailbox deque by worker id.
- Enqueue of mixed-worker batch writes each item to that worker's pull slot.
- Polling worker drains only its pull slot.
- Backpressure is enforced per mailbox and/or per worker slot with documented
  limits.
- Existing polling E2E behavior remains unchanged.

## PPB-3 - Main Mailbox Queue Guardrail

Goal: prevent the old selective-consumption model from returning.

Scope:

- Add architecture guards for mailbox handoff and polling-adapter internal
  buffer separation.
- Add documentation guardrails separating embedded host helper code from
  adapter-owned mailbox consumption.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md`.
- Update or supersede roadmap text that still calls `selectedWorkerId` a
  mailbox queue poll selector.

Acceptance:

- Guards fail if any adapter mailbox handoff `poll/drain` method accepts
  worker id.
- Guards fail if the default mailbox handoff grows claim/ack/complete or
  handoff reference semantics without a separate reliable-broker owner
  roadmap.
- Guards fail if assigned-dispatch mailbox handoff requires generic
  `RoutingTarget`/`DispatchRoutingBatch` wrappers instead of direct
  `adapterMailboxKey + items`.
- Guards fail if polling-adapter Redis implementation scans a shared mailbox
  list to find worker items.
- Proof registry says selected-worker filtering belongs to polling adapter
  internal pull slots, not adapter mailbox consumption or transport core.
- Active docs describe `TransportDispatchHandoff` as mailbox queue and
  polling pending delivery as a polling-adapter internal capability.
- Active docs describe current embedded `AdapterMailboxMount` as transitional
  host support unless and until a separate adapter-owned consumption roadmap
  replaces that boundary.

## Verification Candidates

Commands must be corrected after PPB-0 inventory.

Compile smoke:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Polling adapter internal-buffer proof:

```powershell
.\mvnw -q -pl transport/transport_runtime,transport/polling-adapter -am test "-Dtest=InMemoryPollingPendingDeliveryBufferTest,RedisPollingPendingDeliveryBufferTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,TransportConvergenceArchitectureGuardTest"
```

SDK/server representative proof:

```powershell
.\mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=EmbeddedPullWorkerSessionTest,ExternalWorkerPollingApiIntegrationTest,ExternalWorkerApiControllerTest,MassApplicationDistributedTransportTest"
```

Residue scans:

```powershell
rg -n "TransportDeliveryStore|TransportDeliveryService|drain\\(String adapterMailboxKey, String selectedWorkerId|poll\\(String adapterMailboxKey,\\s*String selectedWorkerId" transport sdk xa-mass-server --glob "*.java" --glob "!**/target/**"
rg -n "LRANGE.*0.*-1|tombstone|selectedWorkerId.*drain" transport/polling-adapter transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery --glob "*.java"
```

## Completion Criteria

- Main adapter mailbox handoff consumes by mailbox key only.
- Main adapter mailbox handoff uses direct `adapterMailboxKey + items` inputs,
  not generic routing wrappers.
- Main adapter mailbox handoff is best-effort destructive poll by default; no
  ack/complete/claim-reference model is required for this roadmap.
- Adapter-owned mailbox consumption is documented as the target boundary, and
  current embedded host drain is not described as full adapter independence.
- Polling pending delivery is owned inside polling-adapter, not transport core.
- Polling adapter internal buffer uses worker pull slots, not shared mailbox
  FIFO scan.
- Batch enqueue is supported for polling adapter internal admission.
- `selectedWorkerId` is not a mailbox queue consumption selector.
- Authenticated polling worker identity may select only that worker's
  adapter-internal pull slot.
- Docs and proof registry no longer describe selected-worker filtering as
  adapter mailbox queue drain semantics.
- No old `TransportDeliveryStore` production alias remains, and no equivalent
  transport-core polling buffer abstraction replaces it.
