# Transport Polling Pull Buffer Boundary Convergence Roadmap

Status: complete; archived after polling pending pull-buffer moved behind
polling-adapter boundary, current owner docs updated, guards added, and focused
proofs passed on 2026-06-22.

## Summary

`TransportDeliveryStore` and `TransportDeliveryService` currently look like
transport-core delivery queue abstractions, but their production behavior is
polling-adapter pending-delivery buffering:

```text
adapter mailbox item
  -> polling final-hop executor
  -> pending pull buffer
  -> polling worker poll by authenticated worker id
```

This roadmap moves that pending pull buffer behind the polling adapter
boundary. It does not redesign the main dispatch handoff.

The current `TransportDispatchHandoff` model is acceptable for this roadmap:

```text
producer offers DispatchRoutingBatch to adapter mailbox
adapter mailbox host/mount polls by adapterMailboxKey
handoff may use ClaimedDispatchRoutingBatch / complete(...) internally
adapter final-hop returns DispatchOutcome
```

`TransportDispatchHandoff`, `DispatchRoutingBatch`,
`ClaimedDispatchRoutingBatch`, handoff references, and `complete(...)` belong
to the dispatch handoff/carrier owner. PPB must not change them.

## Current Facts

- `TransportDispatchHandoff` is the main assigned-dispatch handoff between
  engine/starter assembly and adapter mailbox consumers.
- `TransportDispatchHandoff` currently uses `DispatchRoutingBatch`,
  `ClaimedDispatchRoutingBatch`, handoff references, and `complete(...)`.
  That shape is accepted as current dispatch handoff truth for this roadmap.
- `AdapterMailboxMount` consumes dispatch batches by `adapterMailboxKey` and
  invokes the embedded adapter command executor.
- `PollingDeliveryExecutor` receives `DispatchRoutingItem` values from the
  adapter final-hop path and enqueues them into `AdapterPullDeliveryBuffer`.
- `PollingDeliveryPullChannel` lets polling workers pull items from
  `AdapterPullDeliveryBuffer`.
- `AdapterPullDeliveryBuffer` delegates to `TransportDeliveryService`.
- `TransportDeliveryService` delegates to `TransportDeliveryStore`.
- `TransportDeliveryStore` exposes
  `poll(adapterMailboxKey, selectedWorkerId, ...)`, which is a polling-worker
  pull-slot shape, not a main adapter mailbox handoff shape.
- WebSocket and socket adapters must not use `TransportDeliveryService` for
  push final-hop delivery.

## Owner Review

Scheduling owner:

```text
workerGroup / task policy / worker-runtime evidence -> selectedWorkerId
```

Transport dispatch handoff owner:

```text
adapterMailboxKey queue
DispatchRoutingBatch / ClaimedDispatchRoutingBatch / complete(...) as current truth
no worker-id selective mailbox consumption
```

Polling adapter owner:

```text
selectedWorkerId pull slot
worker poll session identity validation
pending items for polling workers
polling storage shape and backpressure
```

Adapter owner:

```text
adapterMailboxKey -> local adapter process / host
selectedWorkerId -> local worker session or polling pull slot
protocol send / poll / receive result
```

Engine owner:

```text
retry / reassign / compensation / task attempt timeout
```

## Boundary Decision

`TransportDeliveryStore` must stop being a transport-core abstraction. Its
real owner is the polling adapter because it exists to hold already accepted
items until a polling worker asks for them.

Target ownership:

```text
transport_runtime:
  TransportDispatchHandoff
  DispatchRoutingBatch
  ClaimedDispatchRoutingBatch
  DispatchRoutingItem
  DispatchOutcome

polling-adapter:
  PollingPendingDeliveryBuffer
  InMemoryPollingPendingDeliveryBuffer
  RedisPollingPendingDeliveryBuffer, if Redis support remains
  PollingDeliveryExecutor
  PollingDeliveryPullChannel
```

This roadmap must not:

- change `TransportDispatchHandoff` signatures
- remove `DispatchRoutingBatch`
- remove `ClaimedDispatchRoutingBatch`
- remove `complete(...)`
- decide destructive poll semantics
- decide failure-emission-failed semantics for dispatch handoff
- make transport responsible for polling worker slot ownership

The only dispatch-handoff invariant PPB needs is:

```text
main mailbox consumption is by adapterMailboxKey, not selectedWorkerId
```

Polling selected-worker filtering happens after the polling adapter has
accepted the item for polling delivery.

## Target Shape

Possible polling-adapter internal API:

```java
interface PollingPendingDeliveryBuffer {
    List<DispatchOutcome> enqueue(String adapterMailboxKey,
                                  List<DispatchRoutingItem> items);

    PollingPullResult poll(String adapterMailboxKey,
                           String authenticatedWorkerId,
                           int maxItems,
                           long timeoutMillis) throws InterruptedException;

    void shutdown();
}
```

The worker argument must be documented as authenticated polling worker
identity, not a transport dispatch selector.

Possible storage shape:

```text
adapterMailboxKey -> selectedWorkerId -> queue<DispatchRoutingItem>
```

Redis, if retained:

```text
polling:<encodedAdapterMailboxKey>:worker:<encodedSelectedWorkerId>:q
  LIST of DispatchRoutingItem records or refs
```

No transport-core API should expose a polling pull slot as a generic delivery
store.

## Non-Goals

- Do not change worker selection.
- Do not change worker-runtime delivery target evidence.
- Do not change `TransportDispatchHandoff`.
- Do not change `DispatchRoutingBatch` / `ClaimedDispatchRoutingBatch` /
  `complete(...)`.
- Do not decide claim/ack/destructive-poll semantics.
- Do not move retry/reassign/compensation into transport.
- Do not add adapter lifecycle state, health supervision, crash notification,
  restart, failover, migration, takeover, or mailbox rebalance.
- Do not treat local resource start/close as adapter lifecycle truth.
- Do not preserve `TransportDeliveryStore` as a compatibility alias after
  production callers move.
- Do not keep stats/diagnostics in the mainline just to preserve the old
  transport-core store shape.

## Do Not Start With

Do not start by changing `TransportDispatchHandoff`. That is a different
roadmap.

Do not start by optimizing Redis list scans. First move the owner boundary so
the polling buffer lives under polling-adapter.

Do not start by adding a new wrapper in `transport_runtime` with a better name.
That would preserve the wrong owner.

Do not start by adding stats or views. Diagnostics may be added later as
polling-adapter-owned side channels only.

## PPB-0 - Inventory And Classification

Goal: prove every production use of `TransportDeliveryStore` /
`TransportDeliveryService` is polling pull-buffer usage, or classify it as
configuration, diagnostics, test fixture, or residue.

Scope:

- `TransportDeliveryStore`, `InMemoryTransportDeliveryStore`,
  `RedisTransportDeliveryStore`.
- `TransportDeliveryService`.
- `AdapterPullDeliveryBuffer`.
- `PollingDeliveryExecutor`.
- `PollingDeliveryPullChannel`.
- `PollingTransportAdapterBootstrap`.
- `TransportAdapterBootstrapContext.pullDeliveryBuffer(...)`.
- `WorkerTransportRuntimeFactory` and
  `DefaultWorkerTransportRuntimeFactory`.
- `TransportRuntimeComposition.resolveTransportDeliveryStore()`,
  `MassApplication` transport assembly, and server transport assembly.
- SDK/starter/server config entries such as `deliveryStoreFactory(...)` and
  `redisDeliveryStore(...)`.
- `TransportQueueDiagnosticsMapper` and delivery store stats.
- Architecture guards and tests that protect current polling pull behavior.

Acceptance:

- Inventory separates main dispatch handoff from polling pending pull buffer.
- Inventory confirms WebSocket/socket production code does not use
  `TransportDeliveryStore` / `TransportDeliveryService` for push delivery.
- Inventory classifies SDK/server use as assembly/config/diagnostic, not as
  proof that the store is transport-core truth.
- Inventory identifies every compile-surface caller that imports
  `TransportDeliveryStore` / `TransportDeliveryService`, including
  SDK/starter/server config and runtime factory seams.
- Inventory identifies tests that should move or be renamed when the buffer
  moves to polling-adapter.
- Inventory records current Redis key shape and whether it scans a shared
  mailbox list by worker id.

## PPB-1 - Move Polling Buffer Behind Polling Adapter

Goal: remove the transport-core polling store boundary while keeping current
external polling behavior.

Scope:

- Add polling-adapter-owned `PollingPendingDeliveryBuffer`.
- Move or recreate in-memory pending delivery storage under
  `transport/polling-adapter`.
- Move or recreate Redis pending delivery storage under
  `transport/polling-adapter` if Redis polling buffer support remains.
- Update `PollingDeliveryExecutor` to enqueue into the polling-owned buffer.
- Update `PollingDeliveryPullChannel` to poll from the polling-owned buffer.
- Update `PollingTransportAdapterBootstrap` / runtime factory wiring so
  polling adapter receives the buffer without exposing a transport-core
  delivery store.
- Narrow `WorkerTransportRuntimeFactory.create(...)` so the transport-runtime
  factory seam no longer receives `TransportDeliveryService`; polling buffer
  construction belongs to polling adapter/bootstrap or polling-owned config.
- Remove `TransportDeliveryStore` / `TransportDeliveryService` from
  `TransportAdapterBootstrapContext` if no non-polling owner remains.
- Remove generic SDK/starter/server transport config entry points that expose
  polling pending delivery as transport-core truth:
  `deliveryStoreFactory(...)`, `redisDeliveryStore(...)`, and
  `TransportRuntimeComposition.resolveTransportDeliveryStore()`.
- Update `MassApplication` and `XaMassServerApplication` assembly so any
  remaining polling buffer configuration is polling-adapter-owned, not a
  generic transport delivery store factory.
- Update `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  in this same slice so current owner docs stop describing
  `TransportDeliveryStore` / `TransportDeliveryService` as transport-core
  delivery truth.
- Keep `TransportDispatchHandoff` unchanged.

Acceptance:

- Production polling delivery compiles and runs through polling-adapter-owned
  buffer types.
- `transport_runtime` production code no longer exposes
  `TransportDeliveryStore` / `TransportDeliveryService` as the generic polling
  pull-store boundary.
- `WorkerTransportRuntimeFactory` and concrete polling runtime factory
  signatures no longer carry `TransportDeliveryService`.
- SDK/starter/server public or assembly config no longer exposes
  `deliveryStoreFactory(...)` / `redisDeliveryStore(...)` as generic transport
  configuration. If Redis polling buffer remains, its configuration is named
  and owned as polling-adapter configuration.
- `MassApplication` and `XaMassServerApplication` no longer construct a
  generic `TransportDeliveryStore` / `TransportDeliveryService` as transport
  core assembly.
- `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md` match
  the new owner boundary in the same slice.
- WebSocket/socket paths remain unchanged.
- `TransportDispatchHandoff` signatures and current claim/complete model are
  unchanged.
- Polling worker A cannot consume worker B's assigned item.
- No compatibility alias preserves `TransportDeliveryStore` as a production
  transport-core API after callers move.

## PPB-2 - Polling Storage Shape

Goal: make polling pending delivery storage match polling adapter ownership
and avoid shared-mailbox worker scans.

Scope:

- Change in-memory storage to `adapterMailboxKey -> workerId -> queue`.
- Change Redis storage, if retained, to per-mailbox/per-worker pull queues.
- Batch enqueue mixed-worker items by target worker slot.
- Poll only the authenticated polling worker slot.
- Remove Lua or Java full-mailbox scans used only to find a worker's items.

Acceptance:

- In-memory polling pull does not scan a shared mailbox deque by worker id.
- Redis polling buffer does not use full-list mailbox scan to find a worker's
  items.
- Enqueue of mixed-worker batches writes each item to its selected worker's
  pull slot.
- Backpressure is documented as polling-adapter buffer behavior.
- Existing polling worker E2E behavior remains unchanged.

## PPB-3 - Diagnostics And Residue Cleanup

Goal: remove diagnostics, test, property, and documentation residue after the
compile-surface owner migration.

Scope:

- Move or delete `TransportDeliveryStoreStats`,
  `TransportDeliveryServiceStats`, and `TransportQueueDiagnosticsMapper`
  surfaces unless they become polling-adapter diagnostics.
- Remove leftover property names, test helpers, docs, and samples that still
  imply a generic transport delivery store after PPB-1.
- Keep diagnostics side-channel only; do not let queue stats drive delivery
  correctness.

Acceptance:

- Diagnostics do not keep `TransportDeliveryStore` /
  `TransportDeliveryService` alive.
- Stats are not required by mainline dispatch or polling correctness.
- Remaining docs/tests/properties use polling-adapter-owned vocabulary.

## PPB-4 - Guards And Docs

Goal: prevent polling pull slots from returning to transport core.

Scope:

- Update `doc/PROOF_REGISTRY.md` for proof entries changed by PPB.
- Add or update architecture guards that separate dispatch handoff from
  polling pending buffer.
- Update tests to use polling-adapter-owned names.

Acceptance:

- Guards fail if `transport_runtime` reintroduces a generic
  `TransportDeliveryStore` / `TransportDeliveryService` production boundary
  for polling pull slots.
- Guards fail if WebSocket/socket final-hop delivery uses the polling pending
  buffer.
- Guards fail if polling buffer poll/drain is described as main mailbox
  handoff consumption.
- Guards do not fail merely because current dispatch handoff still uses
  `DispatchRoutingBatch`, `ClaimedDispatchRoutingBatch`, handoff references,
  or `complete(...)`.
- Owner docs were already updated in PPB-1; this slice adds guard and proof
  registry coverage so polling pending delivery cannot drift back into
  transport core.

## Verification Candidates

Commands must be corrected after PPB-0 inventory.

Compile smoke:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Polling adapter proof:

```powershell
.\mvnw -q -pl transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,EmbeddedPullWorkerSessionTest,ExternalWorkerPollingApiIntegrationTest"
```

Guard/support proof:

```powershell
.\mvnw -q -pl transport/transport_runtime -am test "-Dtest=TransportConvergenceArchitectureGuardTest"
```

Residue scans:

```powershell
rg -n "TransportDeliveryStore|TransportDeliveryService|deliveryStoreFactory|redisDeliveryStore" transport sdk xa-mass-server --glob "*.java" --glob "!**/target/**"
rg -n "poll\\(String adapterMailboxKey,\\s*String selectedWorkerId|drain\\(String adapterMailboxKey,\\s*String selectedWorkerId" transport sdk xa-mass-server --glob "*.java" --glob "!**/target/**"
rg -n "LRANGE.*0.*-1|selectedWorkerId.*drain|tombstone" transport/polling-adapter transport/transport_runtime/src/main/java --glob "*.java"
```

## Completion Criteria

- `TransportDeliveryStore` / `TransportDeliveryService` no longer exist as
  production transport-core polling pull-store boundaries.
- Polling pending delivery is owned inside `polling-adapter`.
- Polling pending storage uses worker pull slots, not shared mailbox scan.
- WebSocket/socket final-hop delivery does not depend on polling buffer code.
- `TransportDispatchHandoff` remains accepted current dispatch handoff truth
  unless a separate dispatch handoff roadmap changes it.
- SDK/server config no longer exposes polling pending delivery as generic
  transport-core delivery store configuration.
- Docs and proof registry describe selected-worker polling pull as
  polling-adapter internal behavior.
- No compatibility alias preserves the old `TransportDeliveryStore` production
  API after callers move.
