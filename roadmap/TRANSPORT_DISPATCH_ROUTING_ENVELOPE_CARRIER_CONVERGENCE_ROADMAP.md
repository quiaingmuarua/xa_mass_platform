# Transport Dispatch Routing Envelope Carrier Convergence Roadmap

Status: proposed direction document.

## Summary

Converge assigned-task dispatch so its routed payload carrier matches result
ingress:

```text
RoutingEnvelope(target = adapter:<adapterMailboxKey>,
                payload = opaque worker-facing payload)
```

The goal is not to start an external adapter process yet. The goal is to make
the transport queue/process-boundary item independent from embedded Java
`DeliveryCommand` object wiring, while preserving an explicit transport-owned
outcome context. A future embedded or external adapter host should be able to
consume the same mailbox-targeted envelope without another dispatch model
rewrite.

Current dispatch is already mailbox-addressed, but the carrier is still split
across:

```text
AdapterMailboxDeliveryCommand
AdapterMailboxDeliveryOffer
DeliveryCommandBatch
DeliveryCommand
DeliveryCommandReference
```

This roadmap narrows that shape:

```text
DispatchRoutingItem        = transport-owned queue/process-boundary item
RoutingEnvelope            = routed opaque payload carrier
RoutingTarget.adapter(...) = physical mailbox target
worker-facing payload      = starter-owned opaque worker invocation payload
DispatchOutcomeContext     = transport-owned failure/outcome identity
```

`RoutingEnvelope` is not a task model, worker model, lifecycle model,
diagnostics model, or statistics surface. Payload parsing belongs to the
target owner that created the payload. Transport handoff may route, claim,
ack, and emit delivery outcomes from `DispatchOutcomeContext` without decoding
payload. The embedded adapter bridge may build the current Java executor input
from `DispatchOutcomeContext` plus the opaque payload string, but it must not
become a task-payload decoder.

## Relation To Current Roadmaps

This roadmap follows the mailbox dispatch work in
`TRANSPORT_ROUTING_ENVELOPE_ADAPTER_MAILBOX_CONVERGENCE_ROADMAP.md`.

That roadmap made `adapterMailboxKey` the assigned-delivery physical target and
removed bucket-derived queue ownership from the dispatch mainline. This
roadmap is the next carrier convergence step: keep the mailbox target, but move
the queue/process-boundary item from transport-specific delivery-command
batches to a transport-owned `DispatchRoutingItem` that pairs
`RoutingEnvelope` with explicit outcome context.

This roadmap must not reopen:

- worker selection
- `selectedWorkerId -> adapterMailboxKey` evidence ownership
- mailbox availability proof
- embedded adapter host lifecycle
- result ingress payload schema
- adapter process authentication, registration, or supervision

## Current Code Observations

- `RoutingEnvelope` and `RoutingTarget` already exist in `transport_api` and
  are used by result ingress.
- `RoutingTarget.adapter(adapterMailboxKey)` already names the adapter mailbox
  target owner.
- `TaskDispatchDeliveryCommandSubmitter` currently builds `DeliveryCommand`,
  resolves the selected worker through `WorkerDeliveryTargetView`, and submits
  `AdapterMailboxDeliveryCommand(adapterMailboxKey, command)`.
- `TransportAssignedDeliverySubmitter` groups commands by mailbox and offers
  `AdapterMailboxDeliveryOffer(adapterMailboxKey, List<DeliveryCommand>)`.
- `TransportDeliveryCommandHandoff` currently accepts
  `AdapterMailboxDeliveryOffer`, exposes mailbox-scoped
  `poll(adapterMailboxKey, timeout)`, returns `DeliveryCommandBatch`, and
  completes batches after local outcomes.
- `DeliveryCommandBatch` stores `adapterMailboxKey`, handoff references, and
  `List<DeliveryCommand>`.
- `TransportDeliveryCommandBatchCodec` serializes `DeliveryCommand` fields
  directly inside a batch JSON record.
- `AdapterMailboxMount` polls by mailbox key, dispatches the batch's
  `DeliveryCommand` items into the embedded Java `AdapterCommandExecutor`, and
  emits delivery-failure evidence from `DispatchOutcome`.
- `DispatchOutcome` is currently built from `DeliveryCommand`, so immediate
  offer/backpressure/unavailable failures rely on command fields for delivery
  id, selected worker id, and correlation.
- The remaining convergence problem is not mailbox-scoped drain. It is that
  `DeliveryCommand` still acts as queue carrier, embedded executor input, and
  outcome identity source at the same time.

## Owner Review

Scheduling owner:

```text
workerGroup / task policy / worker runtime evidence -> selectedWorkerId
```

Worker-runtime evidence owner:

```text
selectedWorkerId -> adapterMailboxKey
```

Transport dispatch carrier owner:

```text
DispatchRoutingItem + RoutingEnvelope target + queue claim/ack + outcome context
```

Adapter owner:

```text
selectedWorkerId -> local worker session or pull buffer
protocol final-hop send / poll delivery
wire frame construction from context + opaque worker-facing payload
```

Result owner:

```text
RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)
starter result bridge -> engine result convergence
```

`DeliveryCommand` currently straddles two roles:

- assigned-worker delivery intent
- embedded Java executor input
- queue carrier item and outcome-context source

That is acceptable as an intermediate implementation, but it is not the final
process-boundary shape. The queue item should be a transport-owned
`DispatchRoutingItem` that contains a `RoutingEnvelope` and explicit outcome
context. Delivery outcome identity must not be recovered by parsing
target-owned payload or by reading diagnostics.

## Boundary Decision

V1 dispatch queue item:

```text
DispatchRoutingItem
  envelope: RoutingEnvelope
  outcomeContext: DispatchOutcomeContext
```

`DispatchRoutingItem` is the serialized queue/process-boundary record when
dispatch crosses a handoff store. `RoutingEnvelope` is the only routed opaque
payload carrier inside that record; it is not the only serialized queue record.
Handoff references such as command ids, ready refs, and inflight refs remain
handoff-owned claim metadata and are not adapter payload.

V1 routed payload carrier:

```java
RoutingEnvelope(
    envelopeId = deliveryId,
    target = RoutingTarget.adapter(adapterMailboxKey),
    payload = opaque worker-facing payload,
    diagnostics = bounded diagnostics only,
    createdAtEpochMillis = carrier creation time
)
```

V1 outcome context:

```text
DispatchOutcomeContext
  deliveryId
  selectedWorkerId
  correlationRef
  createdAtEpochMillis / deadline if needed for delivery observation
```

The exact class name can change, but the rule cannot: immediate handoff
failures must be able to produce `DispatchOutcome` without decoding
`RoutingEnvelope.payload` and without reading `diagnostics`.

V1 worker-facing payload:

```text
eventCode
input
sharedConfig
resultCorrelationRef
```

The worker-facing payload is the existing starter-owned dispatch frame encoded
by `TaskDispatchPayloadEncoder` or its successor. It is opaque to
`transport_runtime` handoff and mount code. It must not carry delivery
identity, selected-worker identity, task shell metadata, route, endpoint,
connection, session, queue, created/deadline, or adapter facts.

The embedded executor or a future external adapter frame may still need
`selectedWorkerId`, `deliveryId`, timing, and correlation for final-hop
addressing and outcome creation. Those facts come from `DispatchOutcomeContext`
and the mailbox target, not by duplicating them inside the opaque
worker-facing payload.

V1 embedded Java executor input:

```text
AdapterDispatchCommand
  deliveryId
  selectedWorkerId
  payload
  correlationRef
  createdAtEpochMillis / deadline if needed by final-hop outcome observation
```

The exact class name can change. The rule cannot: embedded Java adapters must
not require `deliveryBucketId` or any bucket/queue owner fact to attempt a
final-hop send. During transition, `DeliveryCommand` may remain as an old
producer intent or old handoff carrier, but `AdapterMailboxMount` must build a
bucket-free executor input before invoking `AdapterCommandExecutor`.

Identity consistency rule:

- `DispatchOutcomeContext.deliveryId` must match
  `RoutingEnvelope.envelopeId`.
- `RoutingEnvelope.target.ownerRef` must match the mailbox being offered,
  stored, claimed, and mounted.
- Starter delivery integration must build the worker-facing payload and
  `DispatchOutcomeContext` from the same correlation source. If it can decode
  or inspect the worker-facing payload during construction, it must validate
  payload `resultCorrelationRef` against `DispatchOutcomeContext.correlationRef`
  before submitting the item.
- `transport_runtime` bridge code does not re-parse the worker-facing payload
  to validate identity. If a future target-owned adapter protocol deliberately
  adds delivery identity to its own wire frame, that target bridge owns the
  consistency check before final-hop send.
- Handoff offer/claim/ack code must not perform this payload validation; it
  still treats payload as opaque.

Owner-kind scope:

- dispatch V1 uses `RoutingTarget.adapter(...)`;
- result mainline uses `RoutingTarget.resultIngress(...)`;
- the existing `engine` owner kind in `RoutingOwnerKinds` is out of scope for
  dispatch carrier proof and must not be used as a dispatch target.

Forbidden top-level `RoutingEnvelope` fields:

- `selectedWorkerId`
- `deliveryBucketId`
- `adapterId`
- `routeKey`
- `connectionId`
- `sessionHandle`
- endpoint lease id
- task id / message id / attempt id
- result success/failure fields
- queue stats/list/count fields

Forbidden dispatch carrier behavior:

- deriving adapter mailbox from `deliveryBucketId`
- storing selected worker as a physical queue key
- parsing target-owned payload in handoff offer/claim/ack paths
- using diagnostics to build `DispatchOutcome`
- adding adapter health or lifecycle state to the dispatch carrier

## Target Flow

```text
TaskDispatchBinding
  -> starter dispatch translation
  -> starter-owned worker-facing payload + result correlation encoding
  -> WorkerDeliveryTargetView.resolveDeliveryTarget(selectedWorkerId)
  -> DispatchOutcomeContext(deliveryId, selectedWorkerId, correlationRef)
  -> RoutingEnvelope(target=adapter:<adapterMailboxKey>,
                     payload=<opaque worker-facing payload>)
  -> DispatchRoutingItem(envelope, outcomeContext)
  -> TransportDeliveryCommandHandoff.offer(adapterMailboxKey, items)
  -> mailbox queue / ready / inflight / ack
  -> AdapterMailboxMount.poll(adapterMailboxKey)
  -> adapter-target bridge validates envelope target and context
  -> bridge builds embedded executor input from context + opaque payload
  -> AdapterCommandExecutor.dispatch(...)
  -> DispatchOutcome
  -> delivery failure evidence when retryable
```

Transport remains the delivery executor. It owns queue admission, claim, ack,
mailbox availability, and delivery outcomes. It does not own worker selection,
retry, reassign, compensation, final result policy, adapter process lifecycle,
or business payload schema.

## Observable Failure Boundary

Transport owns best-effort delivery attempts, not guaranteed worker execution.
Its invariant is:

```text
known delivery-attempt failures must become DispatchOutcome or failure evidence
accepted items without later completion are engine attempt-timeout concerns
```

Transport must return or publish observable delivery evidence for:

- offer rejected by bounded admission or backpressure
- mailbox unavailable or no active mailbox consumer
- invalid dispatch item or corrupt routing item
- selected worker not found in adapter-local session/pull state
- adapter final-hop send/admit failure
- handoff claim/ack failure when the failure is known and attributable to a
  dispatch item

Transport must not turn these into silent drops, diagnostics-only logs, or
payload-dependent guesses.

Transport does not own:

- worker eventually polling an accepted item
- worker process completing execution
- result eventually arriving
- retry/reassign/compensation after attempt timeout
- recovery after a process crash where no durable delivery outcome exists

Those are engine/task-attempt concerns. The dispatch carrier work in this
roadmap exists partly to make this boundary executable: `DispatchOutcomeContext`
must live beside the opaque envelope so offer, mailbox, claim, and final-hop
failure evidence can be produced without parsing adapter payload or diagnostics.

## Non-Goals

- Do not implement external adapter process registration or lifecycle.
- Do not add adapter process auth, permissions, tenant routing, encryption, or
  remote deployment packaging.
- Do not change worker selection, worker scheduling, or task lifecycle.
- Do not move retry/reassign/compensation into transport.
- Do not change public Java worker handler APIs.
- Do not turn `RoutingEnvelope` into a generic business event model.
- Do not add `source`, `ingressCode`, success/failure fields, or content-type
  negotiation in this roadmap.
- Do not add statistics, list, count, snapshot, inspect, or dashboard APIs to
  the dispatch carrier.
- Do not keep old delivery-command carrier classes as compatibility aliases
  after production callers move.

## Do Not Start With

Do not start by deleting `DeliveryCommand`.

First separate its current roles:

```text
routed payload       -> RoutingEnvelope
payload role         -> starter-owned worker-facing payload
outcome identity     -> DispatchOutcomeContext
executor input       -> AdapterDispatchCommand or equivalent bucket-free input
queue record         -> DispatchRoutingItem(envelope, outcomeContext)
```

Deleting or renaming `DeliveryCommand` before the carrier and outcome context
are explicit will either break dispatch or force another wrapper that hides the
same mixed owner problem.

Do not start by building a remote adapter process. That adds lifecycle,
security, process supervision, and deployment policy before the queue carrier
is stable.

Do not start by adding diagnostics to make tests easier. If a fact is required
for delivery outcome correctness, it belongs in outcome context, not
diagnostics. If a fact is only operator evidence, it must stay off the
dispatch mainline.

## DREC-0 - Inventory And Role Classification

Scope:

- Inventory every production and test use of:
  `DeliveryCommand`, `AdapterMailboxDeliveryCommand`,
  `AdapterMailboxDeliveryOffer`, `DeliveryCommandBatch`,
  `DeliveryCommandReference`, `TransportDeliveryCommandBatchCodec`,
  `TransportDeliveryCommandHandoff`, `TransportAssignedDeliverySubmitter`, and
  `AdapterMailboxMount`.
- Inventory polling pull projection path:
  `AdapterPullDeliveryBuffer`, `TransportDeliveryService`,
  `QueuedPulledDispatch`, `PulledDeliveryMessage`,
  `PollingDeliveryExecutor`, and `PollingDeliveryPullChannel`.
- Classify each field of `DeliveryCommand` as:
  carrier identity, routing target, adapter payload, outcome context, timing
  observation, or residue.
- Classify polling pull-store values as adapter-local pull projection, not
  engine-to-adapter handoff carrier. The roadmap must not accidentally delete
  or guard against legal pull-store projections while removing handoff carrier
  residue.
- Inventory `DispatchOutcome.fromCommand(...)` call sites and determine which
  should move to explicit outcome context.
- Inventory `AdapterCommandExecutor.dispatch(...)` and concrete adapter
  executor inputs. Classify `DeliveryCommand.deliveryBucketId` as carrier
  residue, not executor input.
- Inventory Redis and in-memory handoff value shapes, ready references,
  inflight references, and completion semantics.
- Inventory tests/guards that currently protect `DeliveryCommand` as the queue
  item shape.

Acceptance:

- Inventory proves which facts must remain outside opaque payload so handoff
  can produce offer/backpressure/unavailable outcomes without payload decode.
- Inventory separates production carrier shape from embedded Java executor
  input.
- Inventory proves the embedded executor can receive final-hop input without
  `deliveryBucketId` or bucket/queue owner facts.
- Inventory separates engine-to-adapter handoff carrier from adapter-local
  polling pull-store projection.
- Inventory identifies any broad source scans that would block this carrier
  migration and proposes narrower guards.
- No code behavior changes are required in this slice.

## DREC-1 - Carrier Codec, Outcome Context, And Executor Input

Scope:

- Keep worker-facing payload encoding owned by starter integration
  (`TaskDispatchPayloadEncoder` or successor). This codec remains outside
  `transport_runtime`.
- Add a narrow transport-owned carrier codec for
  `DispatchRoutingItem(envelope, outcomeContext)`. It may serialize the opaque
  payload string but must not parse task payload fields.
- Add explicit dispatch outcome context for transport handoff failures.
- Add or rename the embedded Java final-hop input to a bucket-free shape such
  as `AdapterDispatchCommand(deliveryId, selectedWorkerId, payload,
  correlationRef, createdAt/deadline...)`.
- Change `AdapterCommandExecutor` and concrete embedded adapters to consume
  that bucket-free input instead of `DeliveryCommand`.
- Change producer-side translation so it can build:

```text
RoutingEnvelope(target=adapter:<adapterMailboxKey>, payload=<encoded payload>)
DispatchOutcomeContext(deliveryId, selectedWorkerId, correlationRef, ...)
DispatchRoutingItem(envelope, outcomeContext)
```

- Keep the embedded Java final-hop executor working through a bridge that
  constructs the current adapter execution input from `DispatchOutcomeContext`,
  the mounted mailbox target, and the opaque worker-facing payload.
- Define and test the identity consistency rule between
  `DispatchOutcomeContext`, `RoutingEnvelope.envelopeId`, and
  `RoutingEnvelope.target`.

Acceptance:

- Immediate producer/handoff failures can create `DispatchOutcome` without
  reading `RoutingEnvelope.payload`.
- Offer rejection, mailbox unavailable, corrupt routing item, no endpoint, and
  adapter final-hop failure all have an observable `DispatchOutcome` or
  retryable delivery-failure event.
- Adapter final-hop failures can still create `DispatchOutcome` from
  `DispatchOutcomeContext`.
- The adapter-target bridge rejects mismatched envelope target/context before
  invoking an adapter executor.
- `AdapterCommandExecutor` no longer accepts `DeliveryCommand` and no executor
  input requires `deliveryBucketId`.
- No bridge fabricates, copies, or reintroduces `deliveryBucketId` to satisfy
  adapter executor construction.
- The starter-owned payload encoder round-trips the current worker-facing
  dispatch payload
  without adding task shell metadata, route, endpoint, connection, session, or
  adapter id fields. `transport_runtime` tests may assert payload opacity but
  must not depend on starter internals.
- `RoutingEnvelope.diagnostics` is not used for outcome correctness.
- This slice may keep `DeliveryCommand` as transitional producer intent or old
  handoff carrier until DREC-2, but it must not remain embedded Java executor
  input and must no longer be the only source of outcome identity.

## DREC-2 - Handoff Carrier Pivot

Scope:

- Replace `AdapterMailboxDeliveryOffer(List<DeliveryCommand>)` with a
  mailbox-targeted `DispatchRoutingItem` offer shape.
- Replace `DeliveryCommandBatch(List<DeliveryCommand>)` with a batch that
  carries `DispatchRoutingItem` values plus handoff references.
- Decide the offer mailbox source explicitly:
  either group offers from each item's `RoutingEnvelope.target.ownerRef`, or
  keep a batch/offer-level `adapterMailboxKey` and validate every item target
  matches it before admission.
- Update in-memory and Redis handoff implementations and codecs.
- Keep mailbox-level availability and mailbox-scoped poll semantics unchanged.
- Preserve local store hygiene for claim/ack/requeue. This protects
  wrong-consumer prevention and observable known failures only; it is not a
  durable message reliability, process-crash recovery, retry policy, or final
  recovery guarantee.

Acceptance:

- Handoff queue values serialize `DispatchRoutingItem`.
- Each serialized `DispatchRoutingItem` contains one `RoutingEnvelope` and one
  `DispatchOutcomeContext`.
- The physical queue address remains `adapterMailboxKey`.
- If a handoff API accepts a batch/offer-level mailbox key, every item
  `RoutingEnvelope.target.ownerKind/ownerRef` must be `adapter:<same key>`.
  Mismatches are invalid/corrupt dispatch input and must not be enqueued.
- `selectedWorkerId` is not a queue key and is not a top-level
  `RoutingEnvelope` field.
- Redis ready/inflight references remain handoff-owned and do not duplicate
  payload schema facts.
- Offer/backpressure/unavailable outcomes use explicit outcome context.
- Known handoff offer/claim failures either return `DispatchOutcome`, publish
  retryable failure evidence, or retain handoff state for normal claim/ack
  retry; they must not be logged and silently dropped.
- Existing dispatch E2E behavior remains unchanged for embedded adapters.

## DREC-3 - Adapter Mailbox Mount And Embedded Executor Bridge

Scope:

- Update `AdapterMailboxMount` to poll routing-envelope batches.
- Validate each claimed envelope target is `adapter:<mountedMailboxKey>`.
- Do not decode task payload in `transport_runtime`. The adapter-target bridge
  treats `RoutingEnvelope.payload` as an opaque worker-facing string.
- Construct embedded executor input from `DispatchOutcomeContext`, the mounted
  mailbox target, and the opaque worker-facing payload.
- Treat envelope/context mismatch as corrupt or invalid delivery input and do
  not call the adapter executor.
- Keep `AdapterCommandExecutor` as the embedded Java final-hop SPI unless a
  separate roadmap replaces it.
- Ensure concrete adapters still receive only the minimal assigned-worker
  execution input they need.
- Keep polling pull-store values (`QueuedPulledDispatch` and
  `PulledDeliveryMessage`) as adapter-local projections. They may be assembled
  after the adapter-target bridge builds pull-buffer input, but they are not
  engine-to-adapter handoff
  carriers.

Acceptance:

- A mismatched envelope target is treated as handoff corruption/invalid
  delivery and does not call the adapter executor.
- `AdapterMailboxMount` does not parse the worker-facing payload to recover
  delivery identity, selected worker, correlation, timing, or outcome facts.
- Adapter modules do not parse `RoutingEnvelope` for non-adapter target kinds.
- WebSocket/socket/polling final-hop tests still prove selected-worker demux.
- `AdapterMailboxMount` does not inspect task shell metadata, result payload
  schema, diagnostics, stats, endpoint leases, or worker-runtime stores.

## DREC-4 - Remove Old Carrier Residue

Scope:

- Delete or narrow old carrier-only classes:
  `AdapterMailboxDeliveryCommand`, `AdapterMailboxDeliveryOffer`,
  `DeliveryCommandBatch`, and `TransportDeliveryCommandBatchCodec`, unless
  their names and fields are still accurate after the carrier pivot.
- Rename handoff/config/API surfaces whose names still imply
  `DeliveryCommand` is the handoff carrier, including
  `TransportDeliveryCommandHandoff`, `deliveryCommandHandoffFactory`,
  `redisDeliveryCommandHandoff(...)`, and related tests/guards. Target names
  should describe dispatch handoff, not delivery-command carrier shape.
- Narrow or remove `DispatchOutcome.fromCommand(...)` if outcome context
  replaces command-derived outcome identity.
- Keep or rename polling pull-store projection classes only according to their
  actual adapter-local role; do not delete them merely because they contain
  delivery identity fields.
- Update transport docs, proof registry, and architecture guards.
- Archive or update roadmaps that still describe `DeliveryCommand` as the
  process-boundary carrier.

Acceptance:

- Production handoff APIs no longer accept or return `DeliveryCommand` as the
  queue carrier.
- Public/starter configuration no longer exposes delivery-command handoff names
  after the carrier pivot; no compatibility alias keeps old names alive.
- No compatibility wrapper keeps the old carrier path alive.
- Architecture guards fail if handoff codecs serialize `DeliveryCommand`
  records as top-level queue items after the pivot.
- Architecture guards fail if handoff code parses worker-facing payload to
  build offer/claim failure outcomes.
- Owner docs say dispatch and result ingress both use `RoutingEnvelope` at the
  transport queue/process-boundary layer.

## Guard Targets

- `DispatchRoutingItem` is the only dispatch handoff queue item after DREC-2.
- `RoutingEnvelope` is the only routed opaque payload carrier inside a dispatch
  handoff item.
- `RoutingEnvelope.target.ownerKind` for dispatch is `adapter`.
- `RoutingEnvelope.target.ownerRef` for dispatch is `adapterMailboxKey`.
- `DispatchOutcomeContext` is the only handoff-owned source for immediate
  offer/claim/ack failure outcomes.
- Handoff offer/claim/ack code must not parse payload.
- Handoff offer/claim/ack code must not use diagnostics for routing,
  compensation, lifecycle, or outcome identity.
- Adapter-target bridge code must validate envelope target/context and must not
  recover delivery identity from the worker-facing payload before calling
  embedded executors.
- `AdapterCommandExecutor` must not accept `DeliveryCommand`; embedded adapter
  executor input must not contain `deliveryBucketId`.
- Worker-facing dispatch payload must not contain route key, endpoint address,
  connection id, session handle, endpoint lease id, adapter id, task shell
  metadata, selected-worker identity, delivery id, timing/deadline, or
  queue-owner facts.
- Producer-side dispatch must keep resolving mailbox through
  `WorkerDeliveryTargetView`; no endpoint lease, route-owner, adapter
  registry, or route-key lookup may return.
- Stats/list/count/snapshot/inspect APIs must stay off the dispatch carrier
  mainline.

## Verification Candidates

These commands are candidates and must be corrected after DREC-0 inventory.
Mandatory new test classes should be created before using them as completion
proof; do not rely on `failIfNoSpecifiedTests=false` for mandatory proof.

Baseline compile smoke:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

Carrier and handoff proof:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime -am test "-Dtest=RoutingEnvelopeTest,DispatchRoutingItemTest,DispatchOutcomeContextTest,AdapterDispatchCommandTest,DispatchRoutingItemCodecTest,TransportDispatchRoutingEnvelopeCarrierTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,AdapterMailboxMountTest,TransportAssignedDeliverySubmitterTest,TransportConvergenceArchitectureGuardTest"
```

Adapter behavior proof:

```powershell
.\mvnw -q -pl transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am test "-Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,WebSocketSessionControllerTest,SocketSessionManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Starter/integration proof:

```powershell
.\mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=TaskDispatchPayloadEncoderTest,TaskDispatchDeliveryCommandSubmitterTest,MassApplicationDistributedTransportTest,EmbeddedPullWorkerSessionTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Cross-module compile proof:

```powershell
.\mvnw -q -pl xa-mass-testing,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
```

## Completion Criteria

- Dispatch and result ingress both use `RoutingEnvelope` at the transport
  routed opaque payload layer.
- Dispatch handoff queue records use `DispatchRoutingItem` or an equivalent
  transport-owned record pairing `RoutingEnvelope` with
  `DispatchOutcomeContext`.
- Dispatch target routing is `RoutingTarget.adapter(adapterMailboxKey)`.
- Transport handoff does not use `DeliveryCommand` as the carrier item.
- Embedded Java `AdapterCommandExecutor` does not use `DeliveryCommand` as
  executor input, and no executor input requires `deliveryBucketId`.
- Worker-facing dispatch payload remains opaque to handoff offer/claim/ack and
  `transport_runtime` mount paths.
- Immediate handoff failures produce `DispatchOutcome` from explicit
  transport-owned outcome context, not payload decode or diagnostics.
- Adapter-target bridge validates envelope target/context and builds executor
  input from context plus opaque payload before invoking embedded executors.
- Known offer, mailbox, no-endpoint, invalid/corrupt item, and adapter final-hop
  failures are observable through `DispatchOutcome` or delivery failure
  evidence.
- Accepted items with no later worker consumption or result are handled by
  engine-owned attempt timeout/retry rather than transport retry loops.
- Adapter final-hop outcomes still carry delivery id, selected worker,
  correlation, status, retryability, reason, and time.
- Embedded Java adapters continue to work without becoming the remote adapter
  protocol.
- No old carrier path remains as a compatibility alias, wrapper, or fallback.
- No statistics, list, count, snapshot, inspect, adapter lifecycle, or worker
  scheduling facts enter the dispatch carrier mainline.
