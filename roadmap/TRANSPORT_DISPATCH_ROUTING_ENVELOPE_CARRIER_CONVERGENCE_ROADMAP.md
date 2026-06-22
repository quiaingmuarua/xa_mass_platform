# Transport Dispatch Routing Carrier Model Convergence Roadmap

Status: mainline implemented; final residue verification remains active.

## Summary

Converge assigned-task dispatch to one flat transport-runtime carrier instead
of first moving through an envelope/context tuple and then flattening again.

This roadmap intentionally merges the previous envelope-carrier draft and the
flat-dispatch-model draft into one execution path. The target is:

```text
RoutingTarget               = ownerKind + ownerRef
DispatchRoutingBatch        = target + flat dispatch items
ClaimedDispatchRoutingBatch = dispatch batch + handoff claim references
DispatchRoutingItem         = selected-worker delivery item
DispatchOutcome             = delivery attempt outcome from item facts
PulledDeliveryMessage       = transport API pull output projection only
```

Result ingress continues to use `RoutingEnvelope`. Assigned dispatch does not.
Dispatch already has a concrete selected worker and a mailbox target, so a
flat selected-worker item inside a mailbox-targeted batch is enough.

Before this convergence, dispatch moved the same facts through too many
shapes:

```text
DeliveryCommand
AdapterMailboxDeliveryOffer
DeliveryCommandBatch
DeliveryCommandReference
TransportDeliveryCommandBatchCodec
AdapterCommandExecutor.dispatch(List<DeliveryCommand>)
QueuedPulledDispatch
PulledDeliveryMessage
DispatchOutcome
```

The convergence goal is to remove the repeated Java model hops and the
remaining `DeliveryCommand` process-boundary role without adding a new wrapper
that will be deleted in the next phase.

## Owner Review

Scheduling owner:

```text
workerGroup / task policy / worker-runtime evidence -> selectedWorkerId
```

Worker-runtime delivery-evidence owner:

```text
selectedWorkerId -> adapterMailboxKey
```

Transport dispatch owner:

```text
RoutingTarget(adapter-mailbox, adapterMailboxKey)
  + DispatchRoutingItem(selectedWorkerId, opaque payload, correlation, timing)
  + mailbox handoff offer / claim / ack
  + DispatchOutcome
```

Adapter owner:

```text
selectedWorkerId -> local worker session / pull buffer
protocol final-hop send / poll delivery
```

Result owner:

```text
RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)
starter result bridge -> engine result convergence
```

Dispatch and result ingress may share `RoutingTarget` vocabulary, but they do
not need the same carrier. Result ingress is a routed opaque envelope.
Assigned dispatch is a selected-worker delivery item in a mailbox-targeted
batch.

## Relation To Current Roadmaps

This roadmap follows
`TRANSPORT_ROUTING_ENVELOPE_ADAPTER_MAILBOX_CONVERGENCE_ROADMAP.md`.

That roadmap made `adapterMailboxKey` the assigned-delivery physical target
and removed bucket-derived queue ownership from the dispatch mainline. This
roadmap keeps that mailbox target and converges the remaining dispatch carrier
models.

The previous `TRANSPORT_RUNTIME_FLAT_DISPATCH_MODEL_CONVERGENCE_ROADMAP.md`
draft is merged here. Do not keep a second flat-model roadmap in active
`roadmap/`; it would create two implementation orders for the same owner
boundary.

This roadmap must not reopen:

- worker selection
- `selectedWorkerId -> adapterMailboxKey` evidence ownership
- mailbox availability proof
- embedded adapter host lifecycle
- result ingress payload schema
- adapter process authentication, registration, or supervision

## Current Implementation Facts

- Assigned dispatch uses `DispatchRoutingBatch` targeted to
  `RoutingTarget.adapterMailbox(adapterMailboxKey)`.
- `DispatchRoutingItem` is the flat selected-worker dispatch carrier for
  handoff, adapter executors, and polling pull-buffer admission.
- `ClaimedDispatchRoutingBatch` carries handoff claim references after
  materialization; producer offers and serialized queue values do not carry
  claim references.
- `TransportDispatchHandoff` is mailbox-scoped and replaces the old
  delivery-command handoff contract.
- `AdapterCommandExecutor.dispatch(...)` consumes
  `List<DispatchRoutingItem>`.
- Polling pull stores use `DispatchRoutingItem` directly and project to
  `PulledDeliveryMessage` at the pull API boundary.
- `DeliveryCommand`, `DeliveryCommandBatch`, `DeliveryCommandReference`,
  `AdapterMailboxDeliveryOffer`, `TransportDeliveryCommandHandoff`,
  `TransportDeliveryCommandBatchCodec`, and `QueuedPulledDispatch` have no
  production assigned-dispatch carrier role.

## Before Convergence

- `DeliveryCommand` lives in `transport_api` and currently carries
  `commandId`, `deliveryBucketId`, `selectedWorkerId`, opaque `payload`,
  `correlationRef`, deadline, and creation time.
- `DeliveryCommand.deliveryBucketId` is upstream scheduling/index context. It
  is not needed by push adapters for final-hop send and should not be required
  by embedded adapter executor input.
- `AdapterMailboxDeliveryOffer` is a producer-side batch wrapper carrying
  `adapterMailboxKey + List<DeliveryCommand>`.
- `DeliveryCommandBatch` is the consumer-side handoff batch carrying
  `adapterMailboxKey`, handoff references, and `List<DeliveryCommand>`.
- `TransportDeliveryCommandBatchCodec` serializes `adapterMailboxKey` plus
  every `DeliveryCommand`, including `deliveryBucketId`.
- `TransportDeliveryCommandHandoff` exposes command-shaped offer/poll/complete
  methods and therefore freezes `DeliveryCommand` as the process-boundary
  carrier.
- `AdapterCommandExecutor.dispatch(List<DeliveryCommand>)` makes the same
  command model the embedded adapter final-hop SPI.
- `QueuedPulledDispatch.from(DeliveryCommand)` copies the same selected-worker
  delivery facts into a polling queue value, and `PulledDeliveryMessage` copies
  nearly the same facts again for the pull API output.
- `RoutingTarget` already exists with `ownerKind` and `ownerRef`, but current
  dispatch code treats `RoutingTarget.adapter(adapterMailboxKey)` as a mailbox
  target. The target kind should eventually say mailbox, not generic adapter.

## Boundary Decision

`RoutingTarget` means:

```text
ownerKind = target owner namespace
ownerRef  = that owner's mailbox / partition / inbox key
```

For assigned dispatch, the target is the adapter mailbox:

```text
RoutingTarget(ownerKind = "adapter-mailbox",
              ownerRef  = adapterMailboxKey)
```

The first implementation slice should use `adapter-mailbox` directly. Do not
extend the existing generic `adapter` owner kind for assigned dispatch, because
the target is a queue/inbox address, not adapter capability, protocol type, or
lifecycle owner.

`ownerRef` may be a worker id only when `ownerKind` explicitly names a worker
or selected-worker target. It is not valid to infer worker semantics from an
adapter-mailbox target.

Flat dispatch batch:

```java
record DispatchRoutingBatch(
    RoutingTarget target,
    List<DispatchRoutingItem> items
) {}
```

Claimed/materialized handoff batch:

```java
record ClaimedDispatchRoutingBatch(
    DispatchRoutingBatch batch,
    List<DispatchHandoffReference> references
) {}
```

Flat dispatch item:

```java
record DispatchRoutingItem(
    String deliveryId,
    String selectedWorkerId,
    String payload,
    String correlationRef,
    long deadlineEpochMillis,
    long createdAtEpochMillis
) {}
```

Rules:

- Batch target owns mailbox placement. Items do not repeat mailbox placement.
- `selectedWorkerId` is the correctness identity selected by engine. It is not
  a physical queue key and not worker lifecycle truth.
- `payload` is starter-owned worker-facing payload and remains opaque to
  transport handoff, claim, ack, and mailbox mount code.
- `deliveryBucketId` does not belong in the flat dispatch item unless a later
  owner decision proves a concrete transport-runtime consumer that cannot use
  batch target, selected worker, payload, correlation, or timing.
- `DispatchOutcome` must be creatable from `DispatchRoutingItem` without
  decoding payload or reading diagnostics. Because `DispatchOutcome` is a
  `transport_api` type and `DispatchRoutingItem` is a `transport_runtime`
  carrier, item-to-outcome helpers belong in `transport_runtime`, not on
  `DispatchOutcome`.
- Handoff references such as ready refs and inflight refs stay handoff-store
  metadata. They appear only on claimed/materialized consumer batches, not on
  producer offers or serialized queue values.
- Handoff references are not producer item fields, worker payload, adapter
  final-hop inputs, or result correlation.

## Target Serialization

Redis and other process-boundary handoff codecs should serialize one flat
batch shape:

```json
{
  "target": {
    "ownerKind": "adapter-mailbox",
    "ownerRef": "mailbox-a"
  },
  "items": [
    {
      "deliveryId": "d-1",
      "selectedWorkerId": "worker-1",
      "payload": "...opaque worker payload...",
      "correlationRef": "corr-1",
      "deadlineEpochMillis": 10000,
      "createdAtEpochMillis": 9000
    }
  ]
}
```

No nested dispatch `RoutingEnvelope`. No serialized outcome-context tuple. No
serialized handoff references in producer offers or queue values. No duplicate
`adapterMailboxKey` inside every item.

## Target Flow

```text
TaskDispatchBinding
  -> starter dispatch translation
  -> starter-owned opaque worker payload + correlationRef
  -> WorkerDeliveryTargetView.resolveDeliveryTarget(selectedWorkerId)
  -> DispatchRoutingItem(deliveryId, selectedWorkerId, payload, correlationRef, timing)
  -> DispatchRoutingBatch(target=adapter-mailbox:<adapterMailboxKey>, items)
  -> TransportDispatchHandoff.offer(batch)
  -> mailbox queue / ready / inflight / ack
  -> AdapterMailboxMount.poll(adapterMailboxKey)
  -> AdapterCommandExecutor.dispatch(List<DispatchRoutingItem>)
  -> concrete adapter selected-worker final hop
  -> DispatchOutcome
  -> retryable delivery failure evidence when needed
```

Transport remains a best-effort delivery executor. It owns bounded offer,
mailbox claim/ack hygiene, final-hop outcome collection, and observable known
delivery failure. It does not own worker selection, retry, reassign,
compensation, final task result policy, adapter process supervision, or
business payload schema.

## Observable Failure Boundary

Known delivery-attempt failures must become `DispatchOutcome` or retryable
failure evidence:

- bounded offer rejection / backpressure
- unavailable mailbox consumer
- invalid or corrupt dispatch item
- no selected-worker endpoint or pull buffer slot in the adapter
- adapter final-hop send/admission failure
- known handoff claim/ack failure attributable to an item

Accepted items that later produce no worker consumption, no process
completion, or no result are engine/task-attempt timeout concerns. Transport
does not add crash-loss prevention, durable retry ownership, reassign, or final
recovery in this roadmap.

## Non-Goals

- Do not implement external adapter process registration.
- Do not add adapter lifecycle, health, restart, failover, or migration.
- Do not change worker selection or worker-runtime evidence ownership.
- Do not change public Java worker handler APIs.
- Do not move retry, reassign, compensation, or task timeout into transport.
- Do not change result-ingress `RoutingEnvelope` behavior.
- Do not introduce content-type negotiation or task-shaped payload parsing in
  transport runtime.
- Do not add compatibility aliases for old dispatch carrier classes after
  callers are moved.

## Do Not Start With

Do not start by adding another context object such as:

```text
RoutingEnvelope + DispatchOutcomeContext + AdapterDispatchCommand
```

That keeps the same problem: delivery facts are copied between models and then
serialized as a tuple.

Start by deciding the single flat wire item and the single target placement
owner. Then move handoff, mount, adapter executors, and polling projections to
that shape.

Do not start by deleting `DeliveryCommand` before the field roles and caller
sets are inventoried. Deleting first will either break dispatch or force a new
wrapper that hides the same mixed owner problem.

Do not start by building a remote adapter process. That adds lifecycle,
security, process supervision, and deployment policy before the carrier is
stable.

Do not add diagnostics to make tests easier. If a fact is required for delivery
outcome correctness, it belongs in the flat item. If a fact is only operator
evidence, it must stay off the dispatch mainline.

## DRC-0 - Inventory And Field Classification

Scope:

- Inventory production and test usages of:
  `DeliveryCommand`, `AdapterMailboxDeliveryOffer`,
  `DeliveryCommandBatch`, `DeliveryCommandReference`,
  `TransportDeliveryCommandBatchCodec`, `TransportDeliveryCommandHandoff`,
  `TransportAssignedDeliverySubmitter`, `AdapterCommandExecutor`,
  `QueuedPulledDispatch`, `PulledDeliveryMessage`, `DispatchOutcome`,
  polling-adapter `PollingPendingDeliveryBuffer`, and concrete adapter command
  executors.
- Classify each field as one of:
  target placement, selected-worker correctness, opaque payload, correlation,
  timing/deadline, handoff claim metadata, pull API projection, diagnostics, or
  residue.
- Identify all codecs that currently serialize `DeliveryCommand` as the
  process-boundary carrier.
- Identify tests that protect old model names instead of owner invariants.
- Classify `DeliveryCommand.deliveryBucketId` explicitly.

Acceptance:

- Inventory proves which facts are required to produce `DispatchOutcome`
  without payload decode.
- Inventory separates handoff target placement from selected-worker
  correctness identity.
- Inventory identifies every serialized carrier and every model-copy hop.
- Inventory separates engine-to-adapter handoff carrier from adapter-local
  polling pull-store projection.
- No behavior change is required in this slice.

## DRC-1 - Introduce Flat Dispatch Carrier

Scope:

- Add `DispatchRoutingItem` in `transport_runtime` as the flat assigned
  delivery item.
- Add `DispatchRoutingBatch` carrying `RoutingTarget` and flat items.
- Add `ClaimedDispatchRoutingBatch` as the handoff materialization result that
  combines a dispatch batch with store-owned claim references.
- Add `adapter-mailbox` to `RoutingOwnerKinds`; do not overload generic
  `adapter` for assigned dispatch.
- Add runtime-local `DispatchOutcomeFactory` or equivalent helper methods that
  consume `DispatchRoutingItem` and call the field-level `DispatchOutcome`
  constructor/factories.
- Keep result-ingress `RoutingEnvelope` untouched.

Acceptance:

- `DispatchRoutingItem` has only delivery id, selected worker, opaque payload,
  opaque correlation, deadline, and created time.
- `DispatchRoutingItem` does not contain mailbox key, route key, adapter id,
  connection id, endpoint lease id, session handle, task shell metadata, or
  `deliveryBucketId`.
- `DispatchRoutingBatch` target validation rejects blank or unknown owner
  kinds and non-mailbox dispatch targets.
- `RoutingOwnerKinds` contains `adapter-mailbox`, and `RoutingTarget` exposes
  `adapterMailbox(adapterMailboxKey)` or an equivalent explicit factory.
- Assigned dispatch code does not call `RoutingTarget.adapter(...)`.
- Existing result-ingress `RoutingEnvelope` tests remain green after adding the
  new owner kind.
- Producer offers and serialized queue values do not contain handoff claim
  references.
- Immediate offer/backpressure/unavailable outcomes can be built from flat item
  fields.

## DRC-2 - Handoff, Mount, And Executor Carrier Pivot

Scope:

- Replace `AdapterMailboxDeliveryOffer` with a dispatch batch/offer shape based
  on `RoutingTarget + DispatchRoutingItem`.
- Replace `DeliveryCommandBatch` with `DispatchRoutingBatch` for offered and
  serialized queue values, and `ClaimedDispatchRoutingBatch` for consumer claim
  results that need ack references.
- Replace `TransportDeliveryCommandBatchCodec` with a codec that serializes
  the flat batch shape.
- Change `AdapterMailboxMount` to poll `ClaimedDispatchRoutingBatch`, complete
  claimed batches, and dispatch flat items without constructing
  `DeliveryCommand`.
- Change `AdapterCommandExecutor.dispatch(...)` to consume
  `List<DispatchRoutingItem>`.
- Update WebSocket and socket final-hop dispatch to use
  `DispatchRoutingItem.selectedWorkerId()` for local session lookup and
  `DispatchRoutingItem.payload()` as the wire payload.
- Update polling final-hop enqueue to consume `DispatchRoutingItem`.
- Delete any adapter-local command DTO that only copies fields from
  `DispatchRoutingItem`.
- Rename `TransportDeliveryCommandHandoff` and related configuration only when
  the code actually stops carrying delivery commands.
- Keep mailbox-level availability proof and mailbox-scoped poll semantics
  unchanged.

Acceptance:

- Handoff queue values no longer serialize `DeliveryCommand`.
- Redis queue values contain one flat batch shape and one opaque payload string
  per item.
- Redis queue values do not contain ready refs, inflight refs, or other ack
  metadata; those are attached only after claim/materialization.
- Handoff offer/claim/ack paths do not parse payload or diagnostics.
- Known handoff failures either return `DispatchOutcome`, publish retryable
  failure evidence, or retain normal handoff state for retryable claim/ack
  hygiene.
- Adapter executors do not receive `DeliveryCommand`, `RoutingEnvelope`,
  context tuples, mailbox target facts, route key, connection id, endpoint
  lease id, session handle, adapter id, or `deliveryBucketId`.
- Push adapters still prove selected-worker final-hop demux.
- Polling final-hop enqueue does not decode worker payload.
- Adapter final-hop failures build `DispatchOutcome` from flat item facts.
- No compatibility wrapper keeps old `DeliveryCommand` carrier path alive.

## DRC-3 - Polling Projection Collapse

Scope:

- Decide whether `QueuedPulledDispatch` remains a real store-value boundary or
  is only a duplicate of `DispatchRoutingItem`.
- If it is only a duplicate, make polling store use `DispatchRoutingItem` and
  project directly to `PulledDeliveryMessage` at the API pull boundary.
- Keep `PulledDeliveryMessage` as the transport API output projection.

Acceptance:

- Polling workers sharing a mailbox still receive only their
  `selectedWorkerId` items.
- Polling queue values do not contain mailbox, route, adapter, connection,
  endpoint, task shell, or lifecycle fields.
- Polling projection does not decode worker payload to build pull output.
- `PulledDeliveryMessage` remains API projection only, not the handoff carrier.

## DRC-4 - Remove Residue And Guards

Scope:

- Delete or narrow old models after callers move:
  `DeliveryCommand`, `AdapterMailboxDeliveryOffer`, `DeliveryCommandBatch`,
  `TransportDeliveryCommandBatchCodec`, `TransportDeliveryCommandHandoff`, and
  `QueuedPulledDispatch` if it is not a real store boundary.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and proof registry wording from delivery-command carrier to flat dispatch
  routing carrier.
- Add architecture guards against reintroducing nested dispatch carrier
  tuples, delivery-command handoff carriers, or task-shaped transport payload
  projections.

Acceptance:

- No production handoff codec serializes `DeliveryCommand`.
- No adapter executor consumes a model whose only role is copying fields from
  flat dispatch items.
- `AdapterMailboxMount`, handoff APIs/codecs, and concrete adapter executor
  paths no longer import or consume `DeliveryCommand`.
- If `DeliveryCommand` is retained by a separate owner decision, guards must
  allow it only outside assigned-dispatch handoff, mailbox mount, and adapter
  executor paths.
- No compatibility alias keeps old carrier paths alive.
- Owner docs say result ingress uses `RoutingEnvelope`; assigned dispatch uses
  flat `DispatchRoutingBatch/DispatchRoutingItem` with `RoutingTarget`.

## Guard Targets

- Assigned dispatch carrier must be flat.
- Dispatch `RoutingTarget.ownerKind` must identify the mailbox/inbox owner, not
  worker capability, protocol type, or lifecycle owner.
- Assigned dispatch must use `RoutingTarget.adapterMailbox(...)` or equivalent;
  `RoutingTarget.adapter(...)` is not an assigned-dispatch target.
- `selectedWorkerId` must stay an item-level correctness identity and must not
  become the physical queue key.
- Handoff target placement must appear once: batch/offer target, not every
  item.
- Handoff claim references must not appear in producer offers, serialized queue
  values, worker payloads, or adapter executor inputs.
- Handoff offer/claim/ack paths must not parse worker payload.
- `transport_api` must not import `transport_runtime`; `DispatchOutcome` must
  not depend on `DispatchRoutingItem`.
- Worker-facing payload must not contain mailbox key, route key, adapter id,
  connection id, session handle, endpoint lease id, delivery id, selected
  worker id, `deliveryBucketId`, or transport timing fields.
- Adapter executors must not depend on `deliveryBucketId`, route key,
  adapter id, connection id, session handle, endpoint lease id, or mailbox key
  to attempt assigned final-hop delivery.
- No statistics, list, count, snapshot, inspect, adapter lifecycle, or worker
  scheduling facts may enter the dispatch carrier mainline.

## Current Verification

Mandatory tests must be named explicitly. Do not rely on
`failIfNoSpecifiedTests=false` for transport runtime carrier proof.

Compile smoke:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

Carrier and handoff proof:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter test "-Dtest=DispatchOutcomeTest,RedisTransportNamespacesTest,DispatchRoutingItemTest,DispatchRoutingBatchTest,ClaimedDispatchRoutingBatchTest,DispatchOutcomeFactoryTest,TransportDispatchBatchCodecTest,InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,AdapterMailboxMountTest,TransportAssignedDeliverySubmitterTest,InMemoryPollingPendingDeliveryBufferTest,PollingDispatchRoutingItemCodecTest,TransportDeliveryFailureEventCodecTest,RedisTransportDeliveryFailureChannelTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest"
```

Adapter proof:

```powershell
.\mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test "-Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,TaskDispatchRoutingSubmitterTest,MassApplicationDistributedTransportTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Cross-runner compile proof:

```powershell
.\mvnw -q -pl xa-mass-testing -am -DskipTests compile
```

Residue checks:

```powershell
rg -n "DeliveryCommand|AdapterMailboxDeliveryOffer|DeliveryCommandBatch|TransportDeliveryCommandBatchCodec|TransportDeliveryCommandHandoff" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/AdapterMailboxMount.java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery --glob "*.java"
rg -n "RoutingTarget\\.adapter\\(|RoutingEnvelope|DispatchOutcomeContext|AdapterDispatchCommand" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/AdapterMailboxMount.java transport/*-adapter/src/main/java --glob "*.java"
rg -n "DeliveryCommand|AdapterMailboxDeliveryOffer|DeliveryCommandBatch|TransportDeliveryCommandBatchCodec|TransportDeliveryCommandHandoff|QueuedPulledDispatch|RedisQueuedPulledDispatchCodec" transport sdk/xa-mass-embedded-sdk/src/main xa-mass-server/src/main integrations -g "*.java"
rg -n "import com\\.xa\\.mass\\.transport\\.runtime" transport/transport_api/src/main/java --glob "*.java"
```

Expected final-state matches: no assigned-dispatch handoff, mailbox mount, or
adapter executor usage of removed model names. Broad repo-wide matches in
archived roadmaps or explicit negative architecture guards are not production
carrier residue.

## Completion Criteria

- Assigned dispatch uses one flat transport-runtime carrier:
  `DispatchRoutingBatch(target, items)` plus flat `DispatchRoutingItem`.
- Handoff claim references appear only in claim/materialization records, not in
  producer offers or serialized queue values.
- Dispatch target placement is represented once by `RoutingTarget`.
- `RoutingTarget.ownerKind/ownerRef` semantics are documented and guarded.
- Result ingress continues to use `RoutingEnvelope`; assigned dispatch does
  not.
- Handoff codecs encode the worker-facing payload once as an opaque string.
- `DispatchOutcome` can be produced from flat item facts without payload
  decode.
- Adapter executors consume flat selected-worker delivery items directly.
- Polling pull projection does not keep a duplicate internal DTO unless a real
  store/protocol seam proves it is needed.
- No old carrier path remains as a compatibility alias, wrapper, or fallback.
- Transport remains a best-effort delivery executor and does not absorb worker
  selection, task retry, compensation, adapter lifecycle, or payload schema.
