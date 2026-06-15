# Transport Bucket Worker Delivery Queue Key Convergence Roadmap

Status: proposed direction document.

## Summary

Assigned task delivery should not route through `routeKey + connectionId`.

By the time work reaches transport, engine/starter already has the stable
assigned-delivery facts:

```text
deliveryBucketId + selectedWorkerId + DeliveryCommand
```

Transport should derive one opaque assigned-delivery handoff queue address from
the delivery bucket only:

```text
deliveryBucketId -> deliveryQueueKey
```

In this roadmap, `deliveryQueueKey` means the assigned-delivery handoff queue
key, not the existing polling-store adapter queue key. It may point to a
runtime-local queue, an adapter-consumer queue, or a bucket queue. The producer
and engine must not decode it.

First-stage mint rule:

```text
deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)
```

The queue key is a batching/storage address. It is not worker identity.
Wrong-worker prevention comes from `selectedWorkerId` carried by every
`DeliveryCommand`.

Bucket-derived queue key does not mean one destructive shared FIFO. The
first-stage Redis target is command storage plus per-consumer selected-worker
ready references.

Bucket ownership is external. Engine/starter owns the meaning of
`deliveryBucketId`, bucket granularity, bucket splitting, and which worker
belongs to which bucket. Transport must not invent `owner-shard`,
`workerShard`, or any worker-owner partitioning rule. Transport only does:

```text
deliveryBucketId -> deliveryQueueKey
selectedWorkerId -> delivery constraint
queue push / drain / final-hop execution
```

This roadmap replaces the current route-owner pointer model:

```text
bucket + worker -> routeKey + connectionId -> route consumer record
```

with a direct bucket queue rule:

```text
bucket -> deliveryQueueKey
```

The current `TransportDeliveryCommandHandoff` shape also needs to be narrowed.
It currently forces submitters to know `deliveryLaneKey` and
`targetTransportNodeId`, which leaks transport internals into a task-delivery
caller. The handoff should accept an opaque `deliveryQueueKey` instead.

This roadmap does not rename adapter identities. Existing transport-internal
`adapterId` usage may remain at final-hop adapter selection, but producer,
engine/starter, and public worker contracts must not use it as a delivery
target.

## Current Code Observations

- `RedisTransportRouteOwnerStore.endpointForSelectedWorker(...)` reads a
  per-worker top-level string key:

  ```text
  bucket:<encodedDeliveryBucketId>:worker:<encodedWorkerId>:owner
  ```

  The value is `encodedRouteKey + \u001f + encodedConnectionId`, then the store
  reads `route:<encodedRouteKey>:consumers` to load the full owner record.

- `TransportRouteOwnerClaim` currently carries route/session evidence for
  final-hop endpoint ownership. Under the bucket queue rule it should not need
  to write a delivery queue key; queue key minting is derived from
  `deliveryBucketId`.

- `TransportAssignedDeliverySubmitter` uses
  `WorkerDispatchRouteOwnerView.targetForSelectedWorker(...)` to get
  `targetTransportNodeId`, derives `deliveryLaneKey` from `deliveryBucketId`,
  and groups by `deliveryLaneKey + targetTransportNodeId`.

- `DeliveryCommandBatch` carries:

  ```text
  deliveryBucketId
  deliveryLaneKey
  targetTransportNodeId
  items
  ```

  This makes the handoff contract depend on transport-node and lane concepts
  that should be internal queue mechanics.

- `RedisTransportDeliveryCommandHandoff` builds its physical lane from:

  ```text
  deliveryLaneKey + "\n" + targetTransportNodeId
  ```

  and publishes ready lanes under:

  ```text
  node:<transportNodeId>:ready-lanes
  ```

- Before convergence, `TransportDeliveryCommandListener` re-resolved endpoint
  evidence through `endpointForSelectedWorker(...)`, copied route/connection
  facts into `AdapterEndpoint`, and read `RouteConsumerEndpoint.adapterId()` to
  choose the final-hop adapter. The target shape moves adapter selection into
  handoff-private selected-worker consumer evidence; WebSocket and socket
  final-hop delivery dispatch by selected worker.

- `TransportDeliveryService` and `TransportDeliveryStore` already have a
  polling-store `deliveryQueueKey`, currently derived from `adapterId`. That is
  a separate adapter-local polling queue owner and must not be silently merged
  with the new assigned-delivery handoff queue key.

## Owner Review

`deliveryBucketId` belongs to the assigned-delivery boundary between
engine/starter and transport. Engine/starter owns its meaning, granularity,
split policy, and worker membership. It is opaque to transport.

`selectedWorkerId` is the correctness identity selected by scheduling. Transport
uses it as a delivery constraint only.

`deliveryQueueKey` belongs to transport. In the first stage it is minted only
from `deliveryBucketId`:

```text
AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)
```

The outer producer passes the opaque bucket id selected by engine/starter.
Transport does not validate the bucket mint rule or import a bucket codec. It
may only apply transport-safe guards before minting the queue key: nonblank,
bounded length, encodable, and safe after Redis/key encoding. For clarity this
is the handoff queue key; existing polling-store queue keys remain a separate
adapter-local concept until a later polling-store cleanup.

Transport must not add a second owner partition such as:

```text
bucket:<encodedBucketId>:owner-shard:<workerShard>
```

or any equivalent `workerShard` rule. If a bucket needs to be split, that is a
bucket-id ownership decision outside transport's assigned-delivery handoff
contract.

`queueConsumerKey` belongs to the delivery-command handoff implementation. It
is the local/remote consumer address used for ready-reference notification. It
is not a bucket owner, worker owner, or routing shard. The producer must not
receive it.

`adapterId` is the existing transport-internal final-hop adapter selector. This
roadmap does not rename it. It may appear in adapter registration, runtime
adapter lookup, and queue-consumer context, but it must not cross the
engine/starter assigned-delivery producer boundary or public worker contracts.

`routeKey` is endpoint address/correlation metadata. It may remain for
raw/manual route side-channels or diagnostics, but it must not be the assigned
task delivery routing identity.

`connectionId` is a network connection instance concept. It is not part of
worker delivery binding identity. A disconnect/reconnect for the same
`deliveryBucketId + selectedWorkerId` should update only adapter-private
session evidence and lease generation; it should not remap the selected-worker
  delivery binding unless external binding inputs changed, such as worker
  bucket or capability attributes.
Transport-wide CAS should use an opaque binding/session generation token, not
expose connection id as a routing fact.

`sessionToken`, route-owner lease token, and binding/session generation belong
to route/session mutation. They prevent stale heartbeat/offline/release from
revoking newer sessions, but they must not be delivery-facing fields.

`targetTransportNodeId` is queue-consumer locality. Handoff implementations may
own it internally, but assigned-delivery submitters should not group or branch
on it.

## Boundary Decision

Assigned delivery hot path:

```text
DeliveryCommand.deliveryBucketId
  -> AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)
  -> deliveryQueueKey
  -> TransportDeliveryCommandHandoff.offer(deliveryQueueKey, commands)
  -> handoff resolves eligible queue consumer(s) internally
  -> queue consumer drains commands with handoff-private queue consumer context
  -> local adapter/session manager dispatches by selectedWorkerId
```

Forbidden hot path:

```text
bucket + worker -> routeKey + connectionId -> route consumer record
```

There is no delivery-facing selected-worker queue directory. Route/session
evidence may still maintain private lease/CAS metadata for final-hop endpoint
correctness, but producer-side queue selection does not read it.

Reconnect rule: if the same worker reconnects with the same bucket and binding
inputs, the producer-visible queue key is unchanged because it is derived only
from the bucket. A changed connection id alone is not a reason to mint a new
delivery queue address or expose a new delivery address to producers.

The local queue consumer context is the only place assigned delivery may
resolve an adapter selector. Producer-side code must not branch on adapter id,
route key, connection id, or transport-node locality.

## Target Redis Shape

There is no assigned-delivery Redis index that maps a selected worker to a
delivery queue key in the first stage.
`deliveryQueueKey` is derived from the bucket id in process:

```text
AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)
  = "bucket:" + encode(deliveryBucketId)
```

Route-owner/session records may still exist as final-hop endpoint evidence for
consumers, but producer-side delivery queue selection must not read route-owner
Redis.

Delivery command handoff:

```text
xa:mass:transport:delivery-command:v1:q:<encodedDeliveryQueueKey>:commands
  HASH commandId -> encoded DeliveryCommand

xa:mass:transport:delivery-command:v1:q:<encodedDeliveryQueueKey>:command-deadlines
  ZSET commandId -> cleanup deadline

xa:mass:transport:delivery-command:v1:consumer:<encodedQueueConsumerKey>:ready-commands
  LIST of command refs owned by that consumer

xa:mass:transport:delivery-command:v1:consumer:<encodedQueueConsumerKey>:inflight-commands
  ZSET command ref -> visibility timeout deadline

xa:mass:transport:delivery-command:v1:queue-consumers:<encodedDeliveryQueueKey>
  SET of queueConsumerKey

xa:mass:transport:delivery-command:v1:queue-consumer:<encodedQueueConsumerKey>
  HASH
    fields = opaque queue-consumer context

xa:mass:transport:delivery-command:v1:selected-worker-consumers:<encodedDeliveryQueueKey>
  HASH selectedWorkerId -> queueConsumerKey + adapterId + leaseDeadline

xa:mass:transport:delivery-command:v1:selected-worker-consumer-deadlines:<encodedDeliveryQueueKey>
  ZSET selectedWorkerId -> lease deadline
```

The queue-consumer context contains the private consumer address, local adapter
selector, and optional locality/lease metadata:

```text
queueConsumerKey -> deliveryQueueKey + adapterId + optional locality metadata
```

First slice decision: use command storage plus per-consumer selected-worker
ready references. The producer calls only `offer(deliveryQueueKey, commands)`.
The handoff resolves consumer evidence for each command through:

```text
deliveryQueueKey + selectedWorkerId -> queueConsumerKey
```

Then it writes the command once into `q:<deliveryQueueKey>:commands` and pushes
a command reference into `consumer:<queueConsumerKey>:ready-commands`. Missing
selected-worker consumer ownership is a retryable transport delivery failure;
it must not fall back to route scan, node scan, shared bucket FIFO scan, or
producer-visible targetTransportNodeId.

The selected-worker consumer evidence is endpoint/wakeup evidence. It is not a
producer-visible queue-key directory, worker owner index, owner shard, or
selected-worker-to-queue-key mapping.

Command lifecycle is part of the handoff contract:

- `offer` atomically writes the command payload and the consumer ready
  reference.
- `claim` atomically moves one ready reference into the consumer inflight set
  with a visibility timeout before the listener materializes the command.
- the listener acknowledges the inflight reference and deletes the command
  payload only after final-hop success is observed or a retryable
  delivery-failure event has been durably accepted.
- durable failure means `TransportDeliveryFailureHandler.handle(...)` returned
  true, or an equivalent failure-channel write was acknowledged by the concrete
  implementation.
- if final-hop dispatch fails before durable failure emission, the reference
  remains inflight until the visibility timeout and is then requeued or marked
  retryable according to the same failure path.
- stale ready references do not acknowledge or delete the command payload until
  a retryable failure outcome is recorded.
- command deadlines bound orphan cleanup if a consumer crashes between command
  storage and final observation.

The first slice explicitly forbids implementing `q:<deliveryQueueKey>` as a
single shared FIFO list. Bucket-level command storage is allowed only when the
ready path is selected-worker safe. A consumer must not destructively pop or
acknowledge a command it cannot deliver for that command's `selectedWorkerId`.

Wrong-worker prevention must be proved from `selectedWorkerId`, not from
`deliveryQueueKey`.

Route-key records:

```text
xa:mass:transport:route-owner:v1:route:<encodedRouteKey>:consumers
```

This family may remain temporarily for raw/manual route side-channel and
diagnostics, but assigned delivery must not depend on it.

## Target Contracts

Replace producer-facing selected-worker target lookup:

```java
Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(
    String deliveryBucketId,
    String selectedWorkerId
);
```

with deterministic bucket queue-key derivation:

```java
final class AssignedDeliveryCommandQueueKey {
    static String queueKeyFor(String deliveryBucketId);
}
```

`AssignedDeliveryCommandQueueKey.queueKeyFor(...)` treats `deliveryBucketId` as
opaque. It must not validate bucket semantics, bucket provenance, generation
rule, worker membership, or split policy. It may only reject values that are
unsafe for transport storage, such as blank, oversized, unencodable, or
otherwise unsafe after Redis/key encoding. It returns a storage-safe opaque
queue key such as:

```text
bucket:<encodedDeliveryBucketId>
```

Producer/starter code passes `deliveryBucketId + selectedWorkerId + payload`.
It does not call a worker queue lookup, does not receive `deliveryQueueKey`
from route-owner state, and must not include `queueConsumerKey`, route key,
connection/session facts, adapter id, or transport node id.

`DeliveryCommand` keeps both correctness facts:

```java
record DeliveryCommand(
    String deliveryBucketId,
    String selectedWorkerId,
    TaskDispatchPayload payload,
    TaskDispatchExecutionContext executionContext
) {}
```

`deliveryBucketId` chooses the handoff queue. `selectedWorkerId` prevents
wrong-worker delivery inside that queue. These two facts must not be collapsed
into one minted worker queue id.

Add a handoff-internal consumer context:

```java
record DeliveryQueueConsumerContext(
    String deliveryQueueKey,
    String queueConsumerKey,
    String adapterId
) {}
```

`adapterId` remains the existing transport-internal final-hop adapter selector.
It is allowed only in the handoff-private consumer context and final-hop
adapter lookup, not in producer-facing assigned delivery contracts.

Add a handoff-internal selected-worker consumer evidence record:

```java
record SelectedWorkerQueueConsumerEvidence(
    String deliveryQueueKey,
    String selectedWorkerId,
    String queueConsumerKey,
    String adapterId,
    long leaseDeadlineEpochMillis
) {}
```

Adapter/session managers must register, refresh, and release this evidence
record when selected-worker endpoint evidence changes. Release and heartbeat
must be current-consumer checked so a stale disconnect cannot remove newer
endpoint evidence.

Replace handoff offer shape:

```java
List<DispatchOutcome> offer(DeliveryQueueOffer offer);
```

Where `DeliveryQueueOffer` is the minimal producer-facing object:

```java
record DeliveryQueueOffer(
    String deliveryQueueKey,
    List<DeliveryCommand> commands
) {}
```

The ready path should use command references, not a shared bucket FIFO:

```java
record DeliveryCommandReference(
    String deliveryQueueKey,
    String commandId,
    String queueConsumerKey
) {}
```

The listener obtains `DeliveryQueueConsumerContext` for its local
`queueConsumerKey` before resolving the local adapter, drains only that
consumer's ready references, and fetches the referenced commands from the
bucket command store. The reference's `deliveryQueueKey` identifies storage
scope, not a unique endpoint owner.

`DeliveryCommandBatch` should not carry `deliveryBucketId`,
`deliveryLaneKey`, or `targetTransportNodeId`. If a batch object remains at the
consumer boundary, it should be local-consumer scoped:

```text
queueConsumerKey + command references
```

or just materialized commands after the handoff has already selected the local
consumer and fetched references safely.

## Non-Goals

- Do not change engine worker selection, claim, admission, or
  `TaskDispatchBinding` semantics.
- Do not make `deliveryQueueKey` a worker identity. Correctness still comes from
  `selectedWorkerId` in each command.
- Do not preserve compatibility for the old internal Redis key shape unless an
  external production migration decision is explicitly made.
- Do not remove raw/manual route side-channels until their callers and proofs
  are classified.
- Do not expose `connectionId`, `sessionToken`, route-owner lease token, or
  runtime-node ids through delivery-facing APIs.
- Do not make reconnect mint a new selected-worker delivery binding merely
  because the network connection id changed. Rebinding requires changed binding
  inputs; migration policy is a later concern.
- Do not add a `bucket + worker -> deliveryQueueKey` Redis/index fallback.
  Producer-side queue selection is bucket-derived; endpoint infeasibility is
  handled by consumer/final-hop delivery outcome.
- Do not rename transport ids globally. `adapterId` naming cleanup is owned by
  a later internal-id roadmap. This roadmap only ensures producer-facing
  assigned delivery does not depend on adapter id.
- Do not merge the existing polling-store `deliveryQueueKey` with the new
  assigned-delivery handoff queue key in the first slice. Treat the name
  collision as explicit residue until DQK-3.

## Do Not Start With

Do not start by sharding the existing `bucket + worker -> routeKey +
connectionId` pointer. That preserves the wrong owner boundary.

Do not introduce a `bucket + worker -> deliveryQueueKey` index as an
intermediate step. The first-stage queue key is derived from bucket only; the
worker dimension belongs to command correctness and final-hop delivery proof.

Do not introduce `owner-shard`, `workerShard`, or
`bucket:<encodedBucketId>:owner-shard:<workerShard>` as a transport runtime
partition. Bucket split and worker bucket membership are external assignment
facts, not transport Redis routing facts.

Do not start by deleting `route:<routeKey>:consumers`. First remove assigned
delivery's dependency on it, then classify raw/manual side-channel and
diagnostic usage.

Do not start by renaming `routeKey` or `connectionId` globally. First narrow the
assigned-delivery contracts so those fields stop crossing the hot path.

Do not start by globally renaming every `adapterId`. Final-hop adapter lookup
may keep the current name until the internal-id roadmap owns that rename.

Do not implement DQK-1 alone if it removes `targetTransportNodeId` from the
producer lookup while the handoff still requires `DeliveryCommandBatch` lane
and node fields. The first behavior-changing slice must include the queue-key
handoff contract or keep the old target lookup behind an internal bridge until
the same commit compiles.

## DQK-0 Inventory And Decision Lock

Goal: classify every current use of route-owner endpoint lookup, delivery
command handoff lane/node fields, bucket queue-key mint/validation, selected-
worker-safe drain requirements, and producer-facing adapter-id exposure.

Scope:

- `WorkerDispatchRouteOwnerView`
- `RedisTransportRouteOwnerStore`
- `InMemoryTransportRouteOwnerStore`
- `TransportAssignedDeliverySubmitter`
- `TransportDeliveryCommandHandoff`
- `DeliveryCommandBatch`
- `RedisTransportDeliveryCommandHandoff`
- `InMemoryTransportDeliveryCommandHandoff`
- `TransportDeliveryCommandListener`
- `TransportDeliveryService` / `TransportDeliveryStore` polling queue-key use
- WebSocket/socket/polling adapter dispatch channels
- adapter/session registration call sites that will register queue consumers
  or final-hop endpoint evidence
- public/engine/starter usages that must not receive adapter id or queue
  consumer id
- Redis key manifest and architecture guards

Acceptance:

- Inventory separates assigned-delivery hot path from raw/manual route
  side-channel and diagnostics.
- Inventory separates assigned-delivery handoff `deliveryQueueKey` from the
  existing polling-store queue key currently derived from adapter id.
- Decision records the first-slice `deliveryQueueKey` mint rule:
  `AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)`.
- Decision records that bucket canonical/mint validation is external to
  transport. Transport only applies storage-safety guards before queue-key
  derivation.
- Decision records the `DeliveryQueueConsumerRegistry` registration,
  heartbeat/lease, missing-owner outcome, and cleanup rules.
- Decision records that bucket meaning, bucket split policy, worker membership,
  and bucket ownership are external engine/starter decisions, not transport
  Redis owner-shard decisions.
- Decision records the selected-worker consumer evidence write contract:
  adapter/session managers register, refresh, and release
  `deliveryQueueKey + selectedWorkerId -> queueConsumerKey + adapterId +
  leaseDeadline`.
- Decision records the selected-worker-safe storage model: command store plus
  per-consumer ready references. A single shared bucket FIFO is explicitly
  rejected for DQK-1.
- Decision records command claim/inflight/visibility-timeout/ack/requeue rules
  and orphan cleanup deadlines.
- Decision records the queue-consumer context shape and confirms it is opaque
  to engine/starter.
- Inventory names tests that currently assert old `routeKey + connectionId`
  pointer behavior or producer-facing adapter-id behavior.

## DQK-1 Bucket Queue-Key Mainline Pivot Milestone

Goal: make the first behavior-changing slice compile and run by changing the
bucket-derived queue-key derivation and handoff contract together.

Do not implement this milestone as one undifferentiated change. It has two
compile-safe sub-slices:

- DQK-1A contract + in-memory proof:
  - replace producer-side route-owner target lookup with
    `AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)`;
  - replace handoff offer shape with `DeliveryQueueOffer`;
  - prove in-memory handoff preserves selected-worker correctness for two
    workers sharing one bucket queue;
  - prove producer-side assigned delivery no longer imports
    `WorkerDispatchRouteOwnerView`, `SelectedWorkerDeliveryTarget`,
    `deliveryLaneKey`, or `targetTransportNodeId`.
- DQK-1B Redis command-store state machine:
  - implement Redis command store, ready refs, inflight refs, visibility
    timeout, ack, requeue, failure-durable ack, and cleanup;
  - prove Redis key shape and no lane/node/owner-shard residue.

Scope:

- Add `AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)`.
- Treat `deliveryBucketId` as opaque and apply only transport-safe guards
  before deriving the queue key.
- Retarget `TransportAssignedDeliverySubmitter` to compute:

  ```text
  deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(command.deliveryBucketId)
  ```

- Remove producer-side `WorkerDispatchRouteOwnerView` /
  `SelectedWorkerDeliveryTarget` lookup from assigned-delivery submission.
- Replace or narrow `TransportDeliveryCommandHandoff.offer(...)` to accept
  `DeliveryQueueOffer(deliveryQueueKey, commands)`.
- Replace `DeliveryCommandBatch(deliveryBucketId, deliveryLaneKey,
  targetTransportNodeId, items)` with command-store writes plus
  `DeliveryCommandReference` ready refs, or an equivalent local-consumer-scoped
  batch after references have already been safely resolved.
- Move queue consumer readiness and runtime-node locality inside the handoff
  implementation.
- Add handoff-private `DeliveryQueueConsumerRegistry` with register/heartbeat,
  lookup, expire, and unregister semantics for `deliveryQueueKey ->
  queueConsumerKey set` plus `queueConsumerKey -> adapterId/context`.
- Add a selected-worker consumer evidence write path from adapter/session managers:
  claim, heartbeat, release, and prune
  `deliveryQueueKey + selectedWorkerId -> queueConsumerKey + adapterId +
  leaseDeadline`.
- Implement selected-worker-safe queue consumption with command storage plus
  per-consumer ready references. Do not implement DQK-1 as a single shared
  bucket FIFO, poll-and-discard queue, or scan queue.
- Implement bounded command lifecycle cleanup: command payloads are removed
  after final-hop success or durable retryable failure emission. Redis
  implementation uses inflight refs with visibility timeout; orphan command
  payloads are pruned by deadline.
- Keep route-owner/session evidence only for consumer/final-hop endpoint
  feasibility and stale-session protection.

Acceptance:

- Assigned-delivery producer queue selection is:

  ```text
  command.deliveryBucketId -> AssignedDeliveryCommandQueueKey.queueKeyFor(...)
  ```

  It does not read routeKey, connectionId, endpoint record, adapter id, queue
  consumer key, selected-worker queue index, or transport node id.
- `TransportAssignedDeliverySubmitter` no longer imports or mentions
  `WorkerDispatchRouteOwnerView`, `SelectedWorkerDeliveryTarget`,
  `targetTransportNodeId`, or `deliveryLaneKey`.
- `DeliveryCommandBatch` no longer carries `deliveryBucketId`,
  `deliveryLaneKey`, or `targetTransportNodeId`; if the type remains, it is
  local-consumer scoped and does not expose lane/node facts.
- Redis handoff offers atomically write command payloads to
  `q:<encodedDeliveryQueueKey>:commands` and ready refs to
  `consumer:<encodedQueueConsumerKey>:ready-commands` for the selected worker's
  current consumer evidence.
- Redis listener claims refs by atomically moving ready refs to
  `consumer:<encodedQueueConsumerKey>:inflight-commands` with a visibility
  timeout before materializing the command payload.
- Redis handoff returns retryable `NO_ENDPOINT` or `UNAVAILABLE` when
  `selected-worker-consumers:<encodedDeliveryQueueKey>` has no live consumer
  evidence for the command's `selectedWorkerId`; it must not ask the producer
  for node/consumer facts.
- Stale ready refs are checked against current selected-worker consumer
  evidence before final-hop dispatch. If ownership moved, the ref is forwarded
  to the current consumer; if no live evidence remains, it is reported through
  the retryable endpoint-unavailable path, not delivered to another worker.
- Command payload is deleted only after final-hop success or after retryable
  delivery failure has been durably accepted by the failure channel; if failure
  emission fails, the command stays visible for retry/cleanup.
- Durable failure acceptance is explicit: for the current
  `TransportDeliveryFailureHandler` shape, `handle(...) == true` is the durable
  acceptance signal. `false` keeps the ref inflight until timeout/requeue.
- Redis cleanup uses `q:<deliveryQueueKey>:command-deadlines` and
  `selected-worker-consumer-deadlines:<deliveryQueueKey>` plus inflight
  visibility deadlines; no cleanup path scans all workers in a bucket hash.
- Handoff tests prove two workers in one bucket share the same derived
  delivery queue key but cannot cross-consume commands.
- Reconnect for the same bucket/worker does not change the derived queue key.
  Stale heartbeat/release/prune protection remains endpoint/session evidence,
  not a producer-visible queue binding update.
- Transport-unsafe `deliveryBucketId` values, such as blank, oversized, or
  unencodable values, produce invalid delivery command outcome before any
  route-owner lookup or queue write. Transport does not validate bucket
  canonical semantics.
- Redis keys do not include owner shard / worker shard families such as
  `bucket:<encodedBucketId>:owner-shard:<workerShard>`.

## DQK-2 Queue Consumer Context And Final-Hop Adapter Selection

Goal: move adapter selection out of route-owner endpoint records and into the
local queue-consumer context without renaming adapter ids.

Scope:

- Add handoff-internal `DeliveryQueueConsumerContext(deliveryQueueKey,
  queueConsumerKey, adapterId)`.
- Teach the queue consumer/listener to resolve adapter selection from its local
  `DeliveryQueueConsumerContext`, not from the bucket queue key alone, before
  local final-hop dispatch.
- Keep module-wide adapter registration/configuration `adapterId` names out of
  scope unless they cross assigned delivery.
- Ensure producer-side code and `DeliveryCommand` never carry adapter id.
- Ensure adapter/session manager writes update selected-worker consumer evidence
  under the assigned-delivery handoff registry, not only legacy route-owner
  records.

Acceptance:

- `TransportDeliveryCommandListener` no longer obtains adapter selection from
  `RouteConsumerEndpoint.adapterId()`.
- Assigned-delivery `AdapterDispatchRequest` or its successor uses
  adapter id only at the final-hop adapter-private boundary.
- Engine/starter, public worker registration, and handoff producer APIs do not
  expose adapter id.
- Logs/outcomes for assigned delivery may include adapter id as bounded
  transport diagnostics, but failure/outcome payloads must not make it a caller
  compensation key.

## DQK-3 Assigned-Delivery Listener And Polling Queue Boundary

Goal: dispatch queue-key batches locally without route-owner endpoint
re-resolution, while keeping the existing polling-store queue key from becoming
the assigned-delivery handoff key by accident.

Scope:

- `TransportDeliveryCommandListener` consumes command references from the
  local queue consumer's ready list.
- Listener resolves local adapter/session context from
  `DeliveryQueueConsumerContext`, not from route-owner endpoint records.
- The handoff validates command references against current selected-worker
  consumer evidence before materializing a listener batch.
- WebSocket/socket final-hop remains `sendToSelectedWorker(...)`.
- Polling final-hop remains selected-worker sub-lane delivery.
- Existing `TransportDeliveryService` / `TransportDeliveryStore`
  `deliveryQueueKey` usage is classified as adapter-local polling queue owner.
  It may be renamed later, but DQK-3 must prevent it from being treated as the
  new assigned-delivery handoff key.

Acceptance:

- Listener no longer calls
  `WorkerDispatchRouteOwnerView.endpointForSelectedWorker(...)` for assigned
  delivery.
- Assigned delivery to an old queue after a worker reconnect does not reselect
  another worker; it fails as no endpoint/unavailable and feeds compensation.
- A stale command reference for a previous consumer is not dispatched by the
  wrong consumer and is not acknowledged as successful delivery.
- `AdapterDispatchRequest` does not expose connection/session/lease token.
- Push and polling selected-worker tests still prove wrong-worker prevention.
- Tests or guards distinguish handoff `deliveryQueueKey` from polling-store
  queue key, especially while the polling store still derives its key from
  adapter identity.

## DQK-4 Route/Connection Residue Removal

Goal: remove route-owner endpoint leakage from assigned delivery models and
keep adapter id out of producer-facing delivery contracts.

Scope:

- Remove or narrow `RouteConsumerEndpoint` from the assigned-delivery path.
- Remove `connectionId` from generic assigned-delivery DTOs such as
  `AdapterEndpoint`. If a concrete adapter needs a network handle, it must keep
  that handle inside adapter-private session state keyed by selected worker or
  local endpoint context.
- Remove assigned-delivery producer-side `adapterId` fields/log labels. Final-hop
  adapter-private code may keep the current adapter id terminology until the
  internal-id roadmap owns a rename.
- Rename write-path `connectionId` semantics to `sessionGeneration`,
  `sessionEvidenceId`, or another opaque lease token only where the semantics
  are route/session mutation, not delivery binding.
- Keep raw/manual route-side APIs separate if they still need route address
  evidence.

Acceptance:

- `DeliveryCommand`, `DeliveryCommandBatch`, `AdapterDispatchRequest`,
  `DispatchOutcome`, and assigned-delivery submitter/listener APIs do not expose
  routeKey, connectionId, sessionToken, lease token, transport node id, or
  adapter internals except where the object is explicitly final-hop adapter
  private.
- Architecture guard fails if assigned-delivery code calls route-only
  `currentOwner(routeKey)` or decodes routeKey/connectionId pointer values.
- Architecture guard fails if assigned-delivery code treats connection id as
  part of `bucket + selectedWorker` binding identity or as a producer-visible
  delivery address.
- Architecture guard fails if producer-facing assigned-delivery code imports or
  reads `adapterId`; only queue-consumer/final-hop allowlisted code may use it.
- Raw/manual route side-channel tests, if retained, prove they use separate
  APIs and do not become assigned task delivery.

## DQK-5 Redis Key Manifest And Residue Cleanup

Goal: make Redis keyspace match the new owner model.

Scope:

- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `doc/PROOF_REGISTRY.md`.
- Update `transport/AGENTS.md`.
- Update Redis keyspace guards.
- Remove tests that preserve old pointer vocabulary as mainline truth.

Acceptance:

- Transport delivery-command key manifest lists:

  ```text
  delivery-command:q:<encodedDeliveryQueueKey>:commands
  delivery-command:q:<encodedDeliveryQueueKey>:command-deadlines
  delivery-command:consumer:<encodedQueueConsumerKey>:ready-commands
  delivery-command:consumer:<encodedQueueConsumerKey>:inflight-commands
  delivery-command:queue-consumers:<encodedDeliveryQueueKey>
  delivery-command:queue-consumer:<encodedQueueConsumerKey>
  delivery-command:selected-worker-consumers:<encodedDeliveryQueueKey>
  delivery-command:selected-worker-consumer-deadlines:<encodedDeliveryQueueKey>
  ```

- Retained diagnostic/raw route family, if still needed, is listed separately
  from assigned delivery:

  ```text
  route-owner:route:<encodedRouteKey>:consumers
  route-owner:deadline
  ```

  It is not part of assigned-delivery queue selection, command storage, or
  consumer wakeup.

- Manifest no longer lists:

  ```text
  bucket:<encodedDeliveryBucketId>:workers
  bucket:<encodedDeliveryBucketId>:worker-leases
  bucket:<encodedDeliveryBucketId>:worker:<encodedWorkerId>:owner
  bucket-worker-owner:<shard>
  bucket:<encodedBucketId>:owner-shard:<workerShard>
  adapter:<adapterId>:worker:<workerId>:owner
  worker-route
  worker-routes
  routes
  route-presence
  ```

- Guard scans prohibit assigned-delivery code from depending on
  `routeKey + connectionId` pointer values.
- Guard scans prohibit producer-facing assigned-delivery code from depending on
  `adapterId`, `queueConsumerKey`, `deliveryLaneKey`, or
  `targetTransportNodeId`.
- Guard allowlists final-hop consumer code for adapter id only; remaining
  module-wide `adapterId` configuration residue is tracked outside
  assigned-delivery producer contracts.

## Verification Candidates

Focused compile:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am -DskipTests compile
```

Focused transport tests:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=RedisTransportRouteOwnerStoreTest,InMemoryTransportRouteOwnerStoreTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,TransportDeliveryCommandBatchCodecTest,TransportDeliveryServiceTest,TransportRedisKeyspaceGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

DQK-1 direct Redis proof after the new test lands:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=RedisAssignedDeliveryCommandHandoffTest
```

This test must assert that assigned delivery writes `q:*:commands`,
`q:*:command-deadlines`, `consumer:*:ready-commands`,
`consumer:*:inflight-commands`, `selected-worker-consumers:*`, and
`selected-worker-consumer-deadlines:*`; it must also assert that assigned
delivery does not write `lane:*`, `ready-lanes`,
`bucket:<bucket>:owner-shard:*`, or `bucket + worker -> deliveryQueueKey`
indexes. It must cover success ack, durable failure ack, failure handler
returning false, inflight visibility timeout, and stale consumer rejection.

Adapter selected-worker tests:

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingWorkerAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

SDK/server representative proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=MassApplicationDistributedTransportTest,ExternalWorkerPollingApiIntegrationTest,ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Roadmap Completion Criteria

- Assigned delivery producer queue selection is
  `AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)`.
- Bucket meaning, bucket granularity, bucket split policy, and worker
  membership remain external engine/starter ownership. Transport does not add
  owner-shard or worker-shard routing semantics.
- Transport does not validate bucket canonical generation rules; it only
  applies storage-safety guards to opaque bucket ids.
- `TransportDeliveryCommandHandoff` accepts an opaque queue key and does not
  expose lane/node grouping to submitters.
- Queue-consumer registry tracks `deliveryQueueKey -> queueConsumerKey set` and
  `queueConsumerKey -> adapterId/context`; producers do not see either value.
- Selected-worker consumer evidence tracks
  `deliveryQueueKey + selectedWorkerId -> queueConsumerKey + adapterId +
  leaseDeadline`; producers do not see it.
- Assigned delivery listener does not re-route through
  `routeKey + connectionId`.
- Assigned-delivery producer-facing code and public APIs do not expose
  `adapterId`; final-hop adapter-private code may still use the current
  adapter id terminology.
- Bucket-scoped queue consumption is selected-worker safe: two workers sharing
  one bucket queue cannot cross-consume commands.
- Redis handoff does not use a single shared bucket FIFO list, poll-and-discard
  queue, or scan queue for assigned delivery.
- Command payload lifecycle is explicit: ready claim, inflight visibility
  timeout, success ack, durable failure ack, false failure-handler requeue, and
  deadline-based orphan cleanup.
- Disconnect/reconnect with unchanged binding inputs advances private session
  generation but does not change the bucket-derived producer-visible queue key.
- No `bucket + worker -> deliveryQueueKey` Redis/index mapping exists.
- No `owner-shard` / `workerShard` Redis family exists under transport
  assigned-delivery keys.
- The existing polling-store queue key is either explicitly renamed or guarded
  as separate from assigned-delivery handoff `deliveryQueueKey`.
- Route/session lease tokens remain private to route-owner mutation.
- Redis key manifest, proof registry, and guards match the implemented key
  model.
- Focused compile and route-owner/handoff/adapter selected-worker proof pass.
