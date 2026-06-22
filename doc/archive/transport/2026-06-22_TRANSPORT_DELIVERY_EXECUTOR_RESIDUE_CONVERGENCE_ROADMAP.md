# Transport Delivery Executor Residue Convergence Roadmap

Status: superseded by `TRANSPORT_ROUTING_ENVELOPE_ADAPTER_MAILBOX_CONVERGENCE_ROADMAP.md`.

Superseded note:

This roadmap preserves historical residue context from the bucket-derived
delivery queue direction. Do not execute it as an active implementation plan.
Current mainline dispatch is:

```text
selectedWorkerId
  -> worker-runtime delivery target evidence
  -> adapterMailboxKey
  -> adapter-mailbox delivery-command handoff
  -> adapter-local selectedWorkerId final-hop send
```

The active successor is
`TRANSPORT_ROUTING_ENVELOPE_ADAPTER_MAILBOX_CONVERGENCE_ROADMAP.md`, which
intentionally supersedes bucket-derived dispatch queues and selected-worker
consumer indexes.

Depends on:

- `doc/archive/transport/2026-06-15_TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md`

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

`doc/archive/transport/2026-06-15_TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md` owns the
first boundary cut: assigned delivery must not route through
`routeKey + connectionId`, and producer-side handoff should address commands by
a bucket-derived queue key plus `selectedWorkerId`.

This roadmap owns the next residue cut after that boundary is in place. It
removes the remaining task-dispatch hot-path leaks where endpoint lease facts,
adapter endpoint DTOs, and polling-store vocabulary still make transport look
like a hidden routing runtime instead of a delivery executor.

This roadmap originally blocked archival of
`doc/archive/transport/2026-06-15_TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md` while
route-owner still wrote the bucket-worker owner pointer and
`DeliveryCommandConsumerProjectingRouteOwnerStore` remained in production
assembly. That DQK archival blocker has been removed; this roadmap now tracks
the remaining delivery-executor DTO and service-shape residue.

Target shape:

```text
DeliveryCommand
  commandId
  deliveryBucketId
  selectedWorkerId
  opaque worker payload
  opaque delivery correlation

Delivery command handoff
  deliveryQueueKey
  command store / ready refs / inflight refs
  command.selectedWorkerId -> endpoint/session consumer evidence

Final-hop adapter dispatch
  AdapterCommandExecutor.dispatch(List<DeliveryCommand>)
  adapter-local selectedWorkerId send

Adapter/session manager
  selectedWorkerId -> active local session
```

The assigned task delivery mainline must not carry `routeKey`,
`connectionId`, `transportNodeId`, `AdapterEndpoint`, or endpoint lease
records. Those facts may remain inside endpoint-lease/raw-route/session
internals, but not in the assigned task delivery command, handoff, listener,
or final-hop adapter SPI.

Payload opacity is already completed by
`doc/archive/transport/2026-06-15_TRANSPORT_OPAQUE_DELIVERY_PAYLOAD_BOUNDARY_CONVERGENCE_ROADMAP.md`.
That roadmap removed `TaskDispatchContent` and
`TaskDispatchExecutionContext` from transport API; this residue roadmap must
not reintroduce task-shaped command payloads while keeping endpoint lease or
consumer evidence out of assigned delivery.

## Current Code Observations

These observations are from the current work tree and must be rechecked before
implementation.

- `TransportAssignedDeliverySubmitter` is already moving in the right direction:
  it groups commands by `AssignedDeliveryCommandQueueKey.queueKeyFor(...)` and
  offers `DeliveryQueueOffer(deliveryQueueKey, commands)`.
- `TransportDeliveryCommandListener` no longer depends on
  `WorkerDispatchRouteOwnerView` or route-owner endpoint records for assigned
  delivery.
- Selected-worker consumer evidence is written by adapter/session ingress into
  the delivery-command handoff registry. The projection bridge
  `DeliveryCommandConsumerProjectingRouteOwnerStore` has been deleted.
- `RedisTransportRouteOwnerStore` and old route-owner selected-worker lookup
  contracts have been deleted. Endpoint lease storage is bucket-worker scoped
  and must not re-enter assigned delivery lookup.
- The duplicate final-hop request DTO has been removed. The final-hop adapter
  SPI now consumes `DeliveryCommand` directly, so the assigned delivery item has
  one model across handoff, listener grouping, direct-send, and polling enqueue.
- `TransportDeliveryService` mixes two concerns:
  direct push adapter delivery counters and polling worker inbox queueing. Its
  polling queue key now derives from the request `deliveryBucketId`, but the
  store/service naming still carries the older generic `deliveryQueueKey` /
  `queueByAdapter` vocabulary.
- `RedisTransportDeliveryCommandHandoff` has command store, ready refs,
  selected-worker consumer evidence, and inflight refs.
- `InMemoryTransportDeliveryCommandHandoff` is still a simple blocking queue:
  `poll()` removes a batch, and there is no matching claim/ack/requeue behavior.
- Endpoint lease view is diagnostic/session-maintenance only. It must not
  re-enter assigned delivery.

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
deliveryQueueKey bucket queue entry
  command.selectedWorkerId
  -> endpoint/session lease evidence for final-hop feasibility
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
- Do not rename all transport ids in one broad sweep. Keep naming cleanup in
  narrow owner roadmaps such as route-key removal and node-id removal.
- Do not remove raw/manual route dispatch. Keep it separate from assigned task
  delivery and guard the boundary.
- Do not make engine core depend on transport.
- Do not add compatibility aliases for old internal DTOs or method names.
- Do not move worker lifecycle truth into transport.

## Do Not Start With

Do not start by reintroducing endpoint lease lookup into assigned delivery or
deleting `AdapterEndpoint` blindly. Assigned delivery consumer registration and
listener final-hop dispatch must stay on direct selected-worker consumer
claims. Otherwise producer/consumer handoff may compile but have no clear owner
for choosing a local final-hop driver.

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
  - removed final-hop request DTO residue
  - `AdapterEndpoint`
  - `TransportEndpointLeaseStore`
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

## Phase 1 - Keep Consumer Claims Handoff-Private And Remove Route-Owner Lookup

Status: completed in the current work tree; keep this phase as guard context,
not as active implementation work.

Goal: keep route-owner lookup out of the assigned command listener without
turning selected-worker consumer claims into a second listener/source-of-truth
API. The handoff owns consumer evidence only to prevent wrong consumers from
destructively claiming bucket queue entries. The listener resolves final-hop
feasibility from endpoint lease evidence for the already selected worker.

Target contract:

```java
record DeliveryCommandConsumerClaim(
    String deliveryBucketId,
    String selectedWorkerId,
    String endpointLeaseId,
    long leaseExpireAtEpochMillis
) {}
```

The claim is handoff-private selected-worker consumer evidence. It must not
carry `adapterId`, route key, connection id, queue consumer key, transport node
id, or endpoint driver id. The endpoint driver id remains inside endpoint lease
metadata and is read by the listener only after it has a
`deliveryBucketId + selectedWorkerId` command to deliver.

Actions:

- Move consumer claim ownership from
  `DeliveryCommandConsumerProjectingRouteOwnerStore` into adapter/session
  registration paths.
- Retarget websocket/socket/polling session registration to claim/release
  assigned delivery consumers directly.
- Change `TransportDeliveryCommandListener` to use
  `TransportEndpointLeaseStore.currentEndpointLease(deliveryBucketId,
  selectedWorkerId)` instead of calling
  `WorkerDispatchRouteOwnerView.endpointForSelectedWorker(...)`.
- Remove `WorkerDispatchRouteOwnerView` and `localTransportNodeId` from
  `TransportDeliveryCommandListener` constructor after the listener no longer
  uses them.
- Delete `DeliveryCommandConsumerProjectingRouteOwnerStore` in the same slice
  that replaces its production wiring.

Acceptance:

- Assigned delivery listener does not import or call `WorkerDispatchRouteOwnerView`.
- Missing endpoint lease is a listener-side `NO_ENDPOINT` delivery outcome.
- Missing or moved consumer evidence is a handoff claim/materialization concern:
  non-owning consumers must not destructively pop selected-worker commands.
- Final-hop adapter selection comes from endpoint lease records, not delivery
  command references or consumer-key metadata.
- Endpoint lease claim/heartbeat also claims/releases handoff-private consumer
  evidence so split consumers can demux safely.

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

## Phase 2 - Remove Final-Hop Request DTO From Assigned Task Dispatch

Status: completed in the current work tree; keep this phase as guard context,
not as active implementation work.

Goal: make `DeliveryCommand` the single smallest assigned delivery intent across
handoff, listener grouping, adapter final-hop dispatch, direct-send outcomes,
and polling enqueue.

The concrete adapter/driver id belongs to listener-side grouping after endpoint
lease resolution, not each item. Route address and session handle belong inside
adapter/session internals. Adapters receive the already selected worker
constraint through `DeliveryCommand.selectedWorkerId`.

Actions:

- Delete the final-hop request DTO instead of preserving a second copy of
  `DeliveryCommand` fields.
- Keep per-item `adapterId` out of `DeliveryCommand`; listener grouping owns
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

- `WorkerAdapter.dispatch(...)`, `TransportDeliveryService`, and
  `TransportDeliverySender` consume `DeliveryCommand` directly.
- The removed final-hop request DTO does not exist in main or test source.
- `DeliveryCommand` does not expose `routeKey`, `connectionId`,
  `transportNodeId`, `adapterId`, or endpoint lease facts.
- WebSocket and socket assigned delivery still call selected-worker send APIs.
- Polling adapter enqueue still isolates by selected worker and does not need
  route/session endpoint facts.

Focused tests:

- `WebSocketTaskDispatchChannelTest`
- `SocketTaskDispatchChannelTest`
- `PollingDeliveryExecutorTest`
- `PollingDeliveryPullChannelTest`
- `TransportDeliveryServiceTest`
- frame codec tests that encode canonical task dispatch without endpoint facts

Guards:

- Removed final-hop request DTO symbol must not reappear in transport or SDK
  Java source.
- `DeliveryCommand` must not contain `adapterId`, `AdapterEndpoint`, `routeKey`,
  `connectionId`, or `transportNodeId`.
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
pollDeliveryMessagesResult(String deliveryBucketId,
                           String selectedWorkerId,
                           int maxMessages,
                           long timeoutMillis)
```

- Historical target at the time of this residue roadmap kept polling queue
  placement bucket-derived. Current dispatch routing is superseded by adapter
  mailbox evidence; do not restore bucket-derived placement from this archived
  context. Workers and engine should not see polling inbox partitions or adapter
  ids as queue selectors.

Acceptance:

- Polling worker cannot consume another worker's assigned item when sharing a
  route or inbox partition.
- Polling store API names do not imply route-owner, adapter-node, or assigned
  command handoff ownership.
- Assigned command handoff and polling worker inbox have separate keyspace
  manifests.

Focused tests:

- `PollingDeliveryExecutorTest`
- `PollingDeliveryPullChannelTest`
- `PollingSessionEvidenceDriverTest`
- `TransportDeliveryServiceTest`
- `InMemoryTransportDeliveryStoreTest`
- `RedisTransportDeliveryStoreTest`
- public worker API polling E2E tests

## Phase 5 - Downgrade Route-Owner Redis To Pure Route/Connection Evidence

Status: completed by endpoint lease convergence in
`doc/archive/transport/2026-06-16_TRANSPORT_CONSUMER_LEASE_CONVERGENCE_ROADMAP.md`;
keep this phase as a regression guard only.

Goal: keep selected-worker assigned-delivery projections out of endpoint lease
storage after delivery consumer claims own the selected-worker delivery path.

Actions:

- Keep route-owner selected-worker lookup methods out of assigned delivery
  callers.
- Remove or narrow:
  - `endpointForSelectedWorker(...)`
  - `targetForSelectedWorker(...)`
  - selected-worker route-owner tests that prove assigned delivery lookup
- Delete Redis key family:

```text
bucket:<encodedDeliveryBucketId>:worker:<encodedWorkerId>:owner
```

- Keep old route-owner key families deleted unless a future raw-route
  diagnostic owner explicitly reintroduces a bounded family:

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

- `RedisTransportEndpointLeaseStoreTest`
- `RedisTransportEndpointLeaseStoreContractTest`
- `InMemoryTransportEndpointLeaseStoreContractTest`
- `TransportRedisKeyspaceGuardTest`
- raw route endpoint registry tests when a raw-route diagnostic owner exists
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
  - cross-link only narrow owner roadmaps, not broad vocabulary cleanup buckets
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
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am test -Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,TransportDeliveryServiceTest,InMemoryTransportDeliveryStoreTest,RedisTransportDeliveryStoreTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest -Dsurefire.failIfNoSpecifiedTests=false
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
