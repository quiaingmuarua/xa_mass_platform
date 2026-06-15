# Transport Delivery Executor Residue Convergence Roadmap

Status: proposed direction document.

Depends on:

- `TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md`

## Summary

The transport data plane is converging toward a pure assigned-worker delivery
executor:

```text
engine/starter assignment facts
  -> deliveryBucketId + selectedWorkerId + DeliveryCommand
  -> bucket-derived delivery command queue
  -> selected-worker consumer claim
  -> adapter-local session send
```

`TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md` owns the
first boundary cut: assigned delivery must not route through
`routeKey + connectionId`, and producer-side handoff should address commands by
a bucket-derived queue key plus `selectedWorkerId`.

This roadmap owns the next residue cut after that boundary is in place. It
removes the remaining task-dispatch hot-path leaks where route-owner endpoint
facts, adapter endpoint DTOs, and polling-store vocabulary still make transport
look like a hidden routing runtime instead of a delivery executor.

Target shape:

```text
DeliveryCommand
  commandId
  deliveryBucketId
  selectedWorkerId
  TaskDispatchContent
  TaskDispatchExecutionContext

Delivery command handoff
  deliveryQueueKey
  command store / ready refs / inflight refs
  selectedWorkerId -> local queueConsumerKey evidence

Final-hop adapter request
  deliveryId
  selectedWorkerId
  TaskDispatchContent
  TaskDispatchExecutionContext
  createdAtEpochMillis

Adapter/session manager
  selectedWorkerId -> active local session
```

The assigned task delivery mainline must not carry `routeKey`,
`connectionId`, `transportNodeId`, `AdapterEndpoint`, or route-owner endpoint
records. Those facts may remain inside route-owner/raw-route/session internals,
but not in the assigned task delivery command, handoff, listener, or adapter
task request.

## Current Code Observations

These observations are from the current work tree and must be rechecked before
implementation because the DQK roadmap is still active.

- `TransportAssignedDeliverySubmitter` is already moving in the right direction:
  it groups commands by `AssignedDeliveryCommandQueueKey.queueKeyFor(...)` and
  offers `DeliveryQueueOffer(deliveryQueueKey, commands)`.
- `TransportDeliveryCommandListener` still depends on
  `WorkerDispatchRouteOwnerView` and calls
  `endpointForSelectedWorker(deliveryBucketId, selectedWorkerId)` before final
  hop dispatch.
- `TransportDeliveryCommandListener` converts route-owner endpoint evidence
  into `AdapterEndpoint(routeKey, transportNodeId, connectionId, leaseExpireAt)`
  and attaches it to `AdapterDispatchRequest`.
- `DeliveryCommandConsumerProjectingRouteOwnerStore` projects route-owner claims
  into delivery command consumer claims. This is useful as a migration bridge,
  but it keeps assigned delivery consumer registration coupled to route-owner
  heartbeat.
- `AdapterDispatchRequest` still carries `adapterId` and `AdapterEndpoint`,
  even though websocket/socket assigned task dispatch sends by
  `selectedWorkerId`.
- `TransportDeliveryService` mixes two concerns:
  direct push adapter delivery counters and polling worker inbox queueing. Its
  polling queue key is currently derived from `adapterId`, which is a different
  concept from assigned-delivery command queue keys.
- `RedisTransportDeliveryCommandHandoff` has command store, ready refs,
  selected-worker consumer evidence, and inflight refs.
- `InMemoryTransportDeliveryCommandHandoff` is still a simple blocking queue:
  `poll()` removes a batch, and there is no matching claim/ack/requeue behavior.
- `RedisTransportRouteOwnerStore` still exposes selected-worker endpoint lookup
  and writes a derived `bucket + worker -> routeKey + connectionId` pointer.
  That pointer should leave assigned delivery once delivery consumer claims own
  selected-worker delivery feasibility.

## Owner Review

### Engine / Starter Own

- assignment truth
- selected worker identity
- delivery bucket id minting and bucket granularity
- translation from `TaskDispatchBinding` to `DeliveryCommand`
- compensation decisions from transport delivery failures

Engine core must not import transport contracts. Starter/assembly may translate
neutral assignment facts into transport commands.

### Transport Handoff Owns

- delivery command queue key minting from opaque `deliveryBucketId`
- command storage, backpressure, ready references, inflight references, ack, and
  requeue/reclaim
- selected-worker consumer evidence:

```text
deliveryQueueKey + selectedWorkerId -> queueConsumerKey + endpointDriverId + leaseDeadline
```

The producer must not receive `queueConsumerKey`, endpoint driver ids, route
addresses, connection handles, or runtime node ids.

### Adapter / Session Manager Owns

- local active session index
- final-hop driver send
- selected-worker session lookup
- route/raw side-channel sends where explicitly supported
- internal session handle / connection id

Adapter/session managers may use `routeKey` and `connectionId` internally, but
assigned task delivery should only ask them to send to `selectedWorkerId`.

### Route-Owner Owns

- connection address and lease evidence
- raw/manual route dispatch feasibility
- route-level diagnostics and correlation

Route-owner must not be the selected-worker post-assignment routing engine.

### Polling Worker Inbox Owns

- pull-worker inbox storage and poll semantics
- selected-worker isolation for pull delivery
- polling queue stats

Polling worker inbox storage is not the same queue boundary as distributed
assigned-delivery command handoff. The names should make that distinction
visible.

## Non-Goals

- Do not complete or re-specify the DQK roadmap here.
- Do not rename all transport ids in one broad sweep. The wider naming
  direction remains in `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md`.
- Do not remove raw/manual route dispatch. Keep it separate from assigned task
  delivery and guard the boundary.
- Do not make engine core depend on transport.
- Do not add compatibility aliases for old internal DTOs or method names.
- Do not move worker lifecycle truth into transport.

## Do Not Start With

Do not start by deleting `RedisTransportRouteOwnerStore` selected-worker lookup
or `AdapterEndpoint` blindly. First move assigned delivery consumer registration
and listener final-hop dispatch onto direct selected-worker consumer claims.
Otherwise producer/consumer handoff may compile but have no way to choose a
local final-hop driver.

Do not collapse polling worker inbox storage into distributed command handoff
just because both use a queue key. They are different delivery mechanisms and
should be split by vocabulary before any storage merge is considered.

## Phase 0 - Baseline And Contract Freeze

Goal: record the current executable boundary before making the residue cuts.

Actions:

- Re-run source inventory for:
  - `TransportAssignedDeliverySubmitter`
  - `TransportDeliveryCommandListener`
  - `TransportDeliveryCommandHandoff`
  - `RedisTransportDeliveryCommandHandoff`
  - `InMemoryTransportDeliveryCommandHandoff`
  - `DeliveryCommandConsumerProjectingRouteOwnerStore`
  - `TransportDeliveryService`
  - `TransportDeliveryStore`
  - `AdapterDispatchRequest`
  - `AdapterEndpoint`
  - `RedisTransportRouteOwnerStore`
  - websocket/socket/polling task dispatch adapters
- Confirm DQK status honestly:
  - implemented slice
  - active residue
  - tests/guards currently passing
- Add or update architecture guards only for already agreed invariants, not for
  target state that is not implemented yet.

Acceptance:

- The roadmap and current code do not claim DQK is complete unless its own
  completion criteria are satisfied.
- Current compile proof exists for transport modules.
- Existing raw route side-channel remains explicitly classified as non-assigned
  task delivery.

Verification:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am -DskipTests test-compile
```

## Phase 1 - Make Selected-Worker Consumer Claims The Listener Source Of Truth

Goal: remove route-owner endpoint lookup from assigned command listener.

Target contract:

```java
record DeliveryCommandConsumerClaim(
    String deliveryBucketId,
    String selectedWorkerId,
    String queueConsumerKey,
    String endpointDriverId,
    long leaseExpireAtEpochMillis
) {}
```

`endpointDriverId` is transport-internal final-hop driver identity. It replaces
the listener's need to read `RouteConsumerEndpoint.adapterId()` for assigned
task delivery. It must not cross the producer boundary.

Actions:

- Extend delivery command consumer evidence to include internal final-hop driver
  id.
- Move consumer claim ownership from
  `DeliveryCommandConsumerProjectingRouteOwnerStore` into adapter/session
  registration paths.
- Retarget websocket/socket/polling session registration to claim/release
  assigned delivery consumers directly.
- Change `TransportDeliveryCommandListener` to use the consumer context attached
  to the claimed command/reference instead of calling
  `WorkerDispatchRouteOwnerView.endpointForSelectedWorker(...)`.
- Remove `WorkerDispatchRouteOwnerView` and `localTransportNodeId` from
  `TransportDeliveryCommandListener` constructor after the listener no longer
  uses them.
- Delete `DeliveryCommandConsumerProjectingRouteOwnerStore` in the same slice
  that replaces its production wiring.

Acceptance:

- Assigned delivery listener does not import or call `WorkerDispatchRouteOwnerView`.
- Missing consumer evidence is a handoff offer/poll outcome, not a listener-side
  route-owner lookup failure.
- Final-hop adapter selection comes from delivery consumer context, not
  route-owner endpoint records.
- Route-owner claim/heartbeat can change without being the assigned-delivery
  consumer registration mechanism.

Focused tests:

- `TransportDeliveryCommandListenerTest`
- `RedisTransportDeliveryCommandHandoffTest`
- `InMemoryTransportDeliveryCommandHandoffTest`
- websocket/socket session manager tests proving direct consumer claim/release
- polling session/API tests proving pull consumers are registered by
  selected-worker delivery claim

Guards:

- `TransportDeliveryCommandListener` must not contain:
  - `WorkerDispatchRouteOwnerView`
  - `RouteConsumerEndpoint`
  - `endpointForSelectedWorker(`
  - `targetForSelectedWorker(`
- `DeliveryCommandConsumerProjectingRouteOwnerStore` must not exist in main
  source.

## Phase 2 - Remove `AdapterEndpoint` From Assigned Task Dispatch Requests

Goal: make the adapter task request the smallest final-hop send intent.

Target request shape:

```java
record AdapterDispatchRequest(
    String deliveryId,
    String selectedWorkerId,
    TaskDispatchContent content,
    TaskDispatchExecutionContext executionContext,
    long createdAtEpochMillis
) {}
```

The concrete adapter/driver id belongs to grouping or consumer context, not each
item. Route address and session handle belong inside adapter/session internals.

Actions:

- Remove `AdapterEndpoint` from `AdapterDispatchRequest`.
- Remove per-item `adapterId` from `AdapterDispatchRequest` once grouping owns
  the driver id.
- Change websocket/socket task dispatch channels to log only delivery id,
  selected worker, and outcome reason for assigned task dispatch.
- Remove `TransportDeliveryService.sendDirect(...)` route-key validation.
  Direct send should validate request identity/content only.
- Keep raw/manual route APIs on `RawWorkerRouteEndpointRegistry` separate.
- Delete `AdapterEndpoint` if it no longer has a non-assigned-delivery owner.
  If raw route still needs endpoint details, keep a raw-route/session-internal
  type outside assigned task dispatch.

Acceptance:

- Assigned task dispatch request does not expose `routeKey`, `connectionId`, or
  `transportNodeId`.
- WebSocket and socket assigned delivery still call selected-worker send APIs.
- Polling adapter enqueue still isolates by selected worker and does not need
  route/session endpoint facts.

Focused tests:

- `WebSocketTaskDispatchChannelTest`
- `SocketTaskDispatchChannelTest`
- `PollingWorkerAdapterTest`
- `TransportDeliveryServiceTest`
- frame codec tests that encode canonical task dispatch without endpoint facts

Guards:

- `AdapterDispatchRequest` must not contain:
  - `adapterId`
  - `AdapterEndpoint`
  - `routeKey`
  - `connectionId`
  - `transportNodeId`
- Assigned task dispatch channels must not call `sendToAdapterRoute(`.

## Phase 3 - Align In-Memory And Redis Handoff Claim/Ack/Requeue Semantics

Goal: make local and Redis handoff implementations prove the same delivery
executor lifecycle.

Target lifecycle:

```text
offer
  -> command stored
  -> ready reference published to selected-worker consumer
poll
  -> reference claimed into inflight
listener success
  -> ack command/reference
listener emitted durable retryable failure
  -> ack command/reference
listener exception before durable failure
  -> release/requeue or visibility timeout reclaim
```

Actions:

- Replace the optional `complete(...)` default with an explicit handoff ack
  contract. The interface should make it impossible for an implementation to
  silently ignore ack semantics.
- Add a release/requeue path for listener exceptions before failure emission.
- Update `TransportDeliveryCommandHandoffPump`:
  - `poll`
  - call listener
  - ack only after listener returns outcomes and failure emission has completed
  - release/requeue on listener exception
- Make `InMemoryTransportDeliveryCommandHandoff` maintain inflight claims and
  requeue on release so it matches Redis semantics.
- Rename capacity fields from batch wording to command wording where the
  implementation counts commands.
- Review Redis Lua/script boundaries so offer, ready-ref publish, consumer
  context update, and catalog update remain atomic.

Acceptance:

- A listener exception does not lose a command in memory or Redis.
- Commands are deleted only after success or durable failure emission.
- Redis and in-memory tests prove equivalent claim/ack/requeue behavior.
- Capacity naming matches the actual counted unit.

Focused tests:

- `InMemoryTransportDeliveryCommandHandoffTest`
- `RedisTransportDeliveryCommandHandoffTest`
- `TransportDeliveryCommandHandoffPumpTest` or equivalent pump-level proof
- failure handler tests proving durable failure ack behavior

## Phase 4 - Split Polling Worker Inbox Vocabulary From Assigned Command Handoff

Goal: stop `TransportDeliveryService` / `TransportDeliveryStore` from masking
two different queue concepts behind the same names.

Actions:

- Rename or split polling worker inbox service/store concepts so they no longer
  look like distributed assigned-delivery command handoff:
  - current polling `deliveryQueueKey` -> `pollingInboxPartitionKey` or
    equivalent internal name
  - current queue stats by adapter -> polling inbox partition stats
- Remove unused `adapterId` arguments from store-level enqueue if the store only
  needs a partition key and selected worker.
- Keep worker-facing polling API as:

```java
pollTaskMessagesResult(String selectedWorkerId, int maxMessages, long timeoutMillis)
```

- Make polling adapter own the partition rule internally. Workers and engine
  should not see polling inbox partitions.

Acceptance:

- Polling worker cannot consume another worker's assigned item when sharing a
  route or inbox partition.
- Polling store API names do not imply route-owner, adapter-node, or assigned
  command handoff ownership.
- Assigned command handoff and polling worker inbox have separate keyspace
  manifests.

Focused tests:

- `PollingWorkerAdapterTest`
- `TransportDeliveryServiceTest`
- `InMemoryTransportDeliveryStoreTest`
- `RedisTransportDeliveryStoreTest`
- public worker API polling E2E tests

## Phase 5 - Downgrade Route-Owner Redis To Pure Route/Connection Evidence

Goal: remove selected-worker assigned-delivery projections from route-owner
storage after delivery consumer claims own the selected-worker delivery path.

Actions:

- Remove route-owner selected-worker lookup methods from assigned delivery
  callers.
- Remove or narrow:
  - `endpointForSelectedWorker(...)`
  - `targetForSelectedWorker(...)`
  - selected-worker route-owner tests that prove assigned delivery lookup
- Delete Redis key family:

```text
bucket:<encodedDeliveryBucketId>:worker:<encodedWorkerId>:owner
```

- Keep route-owner key families only if they serve route/connection evidence,
  raw route, or diagnostics:

```text
route:<encodedRouteKey>:consumers
deadline
```

- Update `TRANSPORT_BOUNDARY_BASELINE.md`, Redis keyspace guards, and proof
  registry to state that route-owner no longer participates in assigned task
  delivery correctness.

Acceptance:

- Transport Redis keyspace no longer contains assigned-delivery
  `bucket + worker -> route consumer` pointers.
- Assigned task delivery tests pass without route-owner selected-worker lookup.
- Raw/manual route side-channel remains covered separately.

Focused tests:

- `RedisTransportRouteOwnerStoreTest`
- `InMemoryTransportRouteOwnerStoreTest`
- `TransportRedisKeyspaceGuardTest`
- raw route endpoint registry tests
- assigned delivery command handoff/listener tests

Guards:

- Assigned delivery source paths must not call:
  - `endpointForSelectedWorker(`
  - `targetForSelectedWorker(`
  - `currentOwners(`
- Route-owner Redis production code must not contain:
  - `bucketWorkerKey`
  - `:bucket:`
  - `:worker:`
  - `:owner` for selected-worker projection keys

## Phase 6 - Docs, Proof Registry, And Residue Scan

Goal: make current docs match the reduced transport executor boundary.

Actions:

- Update `transport/AGENTS.md`.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `doc/PROOF_REGISTRY.md`.
- Update active roadmaps:
  - mark this roadmap status honestly
  - keep DQK dependency explicit
  - cross-link `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md` only for
    broader vocabulary work, not for this executor residue slice
- Run source residue scans for old endpoint/task dispatch facts.

Acceptance:

- No active doc states route-owner selected-worker lookup is assigned delivery
  correctness truth.
- No active doc states `AdapterEndpoint` is part of assigned task dispatch
  request.
- No active doc conflates polling worker inbox queue keys with distributed
  assigned command queue keys.

Verification:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
```

Focused suite:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am test -Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,TransportDeliveryServiceTest,InMemoryTransportDeliveryStoreTest,RedisTransportDeliveryStoreTest,PollingWorkerAdapterTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

Representative cross-module proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=MassApplicationDistributedTransportTest,ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Completion Criteria

This roadmap is complete only when:

- DQK assigned-delivery queue-key convergence is complete or explicitly
  superseded.
- Assigned delivery listener no longer depends on route-owner endpoint lookup.
- Assigned task dispatch request no longer exposes `AdapterEndpoint`, `routeKey`,
  `connectionId`, or `transportNodeId`.
- In-memory and Redis command handoff share explicit claim/ack/requeue
  semantics.
- Polling worker inbox vocabulary is separated from distributed assigned
  command handoff vocabulary.
- Route-owner Redis no longer stores assigned-delivery selected-worker
  projection keys.
- Current docs and proof registry match the implemented boundary.

