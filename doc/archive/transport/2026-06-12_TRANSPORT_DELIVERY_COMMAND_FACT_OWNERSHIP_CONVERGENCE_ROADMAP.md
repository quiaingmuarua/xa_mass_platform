# Transport Delivery Command Fact Ownership Convergence Roadmap

Status: complete. Archived after implementation, focused Maven proof, and
residue scans on 2026-06-12. Current facts were moved into
`transport/TRANSPORT_BOUNDARY_BASELINE.md`, `transport/AGENTS.md`, and
`doc/PROOF_REGISTRY.md`.

Related records:

- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `roadmap/TRANSPORT_SELECTED_WORKER_DELIVERY_AND_REACHABILITY_BOUNDARY_ROADMAP.md`
- `doc/PROOF_REGISTRY.md`

## Purpose

Converge delivery command, task-dispatch content, handoff batch, envelope,
outcome, and failure-event models so every delivery fact has exactly one
runtime owner and one canonical carrier at each process boundary.

The current problem is not only repeated strings or Redis payload size. The
larger problem is owner ambiguity: the same fact appears in multiple models,
different components can write or rewrite it, and future changes have no single
source of truth.

For high-volume dispatch, command items must be minimal delivery intents.
Lane, adapter, node, connection, queue ownership, worker-wire compatibility
fields, and generic packet envelopes must be expressed once at the group,
handoff, owner-evidence, store-key, or final-hop frame layer instead of being
copied into every task item.

## Pre-Cutover Code Observations

- Before this roadmap slice, `TaskDispatchDeliveryCommandSubmitter` set
  `deliveryQueueKey = adapterId` before constructing every `DeliveryCommand`.
  The two fields are not independent in the current producer path.
- Before this roadmap slice, `DeliveryCommand` carried `adapterId`, `selectedWorkerId`,
  `deliveryQueueKey`, `targetTransportNodeId`, `routeKey`,
  `connectionToken`, payload, correlation, and timestamps.
- Before this roadmap slice, the `DeliveryCommand` payload was a full `TransportPacket`, not a
  minimal task-dispatch content object. That packet carries generic transport
  envelope fields as well as task-dispatch data.
- `TaskDispatchItem` was a hybrid worker-facing view, runtime
  correlation holder, selected-worker metadata carrier, route metadata carrier,
  and worker-wire payload cache. It carries `attemptId`, `routeKey`,
  `workerId`, `batchId`, and `transportPayload` in addition to task execution
  content.
- `TaskDispatchItem.from(...)` also carried `retryCount`, `taskName`,
  `project`, and `userId` from `TaskDispatchContext` /
  `TaskDispatchBinding`. Some of those fields are current worker-frame or SDK
  compatibility fields, but they are not routing, lane, or endpoint truth.
- Before this roadmap slice, `DeliveryCommandBatch` already carried `deliveryQueueKey` and
  `targetTransportNodeId` at the batch level, then validates that every command
  repeats the same values. That validation is evidence that these are group
  facts, not command facts.
- Before this roadmap slice, `TransportAssignedDeliverySubmitter` resolved
  `activeOwnerForSelectedWorker(adapterId, selectedWorkerId)`, copies
  `owner.transportNodeId()` and `owner.connectionId()` into a new
  `DeliveryCommand`, then groups by `deliveryQueueKey +
  targetTransportNodeId`.
- Before this roadmap slice, `TransportDeliveryCommandBatchCodec` serialized `deliveryQueueKey` and
  `targetTransportNodeId` once at batch level and again inside every command
  record. It also serializes `connectionToken` even though dispatch consumers
  do not use it for adapter delivery.
- Before this roadmap slice, `TransportDeliveryCommandListener` resolved the local adapter from
  `command.getAdapterId()` even though batch consumption is already a
  node-local grouped delivery operation.
- Before TDCFO-2, `TransportDeliveryService` derived the delivery queue key
  from `adapterId` for poll and drain paths while
  `TransportDispatchEnvelopeFactory` copied the same value into every envelope.
- `QueueBackedTransportDeliveryStore` uses
  `deliveryQueueKey + selectedWorkerId` as the queue key while the envelope
  value also carries `deliveryQueueKey`.
- Before this roadmap slice, `DispatchOutcome` and `TransportDeliveryFailureEvent` reconstructed
  observation fields from the full `DeliveryCommand`, which encourages failure
  and outcome code to keep command-level copies of group and route-owner facts.
- Before this roadmap slice, `MassApplication#toDeliveryFailure(...)` read task id, message id,
  and attempt id from the command payload first and then from the command
  correlation map. The first cutover must replace that fallback with typed
  execution context or a compact failure snapshot, not a new generic command
  metadata bag.
- Before this roadmap slice, `TaskDispatchDeliveryCommandSubmitter` built the dispatch
  `TransportPacket` before route-owner resolution, passing producer-side
  `adapterId` and `routeKey` into each command item.
- `TransportPacket` itself carries `adapterId` and `routeKey`, so removing
  top-level command fields without moving packet assembly would preserve the
  same duplicate group/owner facts as nested per-item payload.
- `TransportPacket` also carries `attemptId` and a generic payload map that
  includes task-dispatch wire compatibility fields such as `workerId`,
  `retryCount`, and `batchId`. Those are not all delivery-intent facts.
- Before this roadmap slice, `TransportAssignedDeliverySubmitter` chose route metadata with
  `firstNonBlank(command.getRouteKey(), owner.routeKey())`, which gives a
  producer-side command field priority over the live route-owner value.
- `TransportAssignedDeliverySubmitter` resolves route-owner evidence by
  `adapterId + selectedWorkerId` for each command item. A single
  `adapterId + deliveryQueueKey + targetTransportNodeId` lane may contain
  multiple selected workers and therefore multiple route keys, connection ids,
  and endpoint leases.
- Current websocket and socket dispatch channels send by
  `selectedWorkerId`; their canonical task-dispatch frame codecs do not use
  `TransportPacket.adapterId()` or `TransportPacket.routeKey()` when building
  the worker-facing task dispatch JSON. Those packet fields are therefore
  transport metadata in the current task-dispatch path, not required worker
  wire fields.
- Public worker polling exposes `TaskDispatchItem` through
  `TaskPullResult` and `ExternalWorkerApiController#pollTasks(...)`, while
  polling SDK result submission uses `TaskDispatchItem.attemptId()` to build
  the result envelope. Handoff slimming must not silently break those public
  projections.

## Owner Review

Engine owns the selected worker decision. By the time transport sees a dispatch
item, `TaskDispatchBinding.workerId` is already the execution target and
transport must preserve it as `selectedWorkerId`.

SDK/starter assembly owns translation from engine assignment facts into the
transport submission shape. It may group assignments by adapter, but it must
not define connection, transport node, route-owner, or queue-store truth.

Transport final-hop delivery owns route-owner evidence lookup, transport-node
feasibility checks, handoff grouping, adapter dispatch, delivery outcomes, and
delivery-failure emission. Transport must fail delivery for the selected worker
when evidence is missing; it must not select another worker.

Concrete adapters own connection/session details. `connectionId` and any
session token are adapter or route-owner evidence. They must not be copied into
command items or process-boundary payloads unless a specific adapter protocol
requires that value in a local private frame.

Delivery stores own queue keys and backlog admission. `deliveryQueueKey` is a
store/lane partition. In the current implementation it is derived from
`adapterId`; it must not become a second worker identity or a per-item command
truth.

Outcomes and failure events are immutable observations. They may contain a
snapshot of facts needed for compensation and diagnosis, but those facts must be
copied from the canonical owner at the observation site, not preserved as live
parallel truth in command items.

`TransportPacket` must not be used as an escape hatch for removed command
fields. Starter-side assignment translation may create minimal task-dispatch
content only. The final-hop transport boundary owns attachment of route
metadata, adapter/lane context, and worker-wire compatibility fields when a
local dispatch packet or worker frame actually needs that metadata.

`TaskDispatchItem` must not remain the hot-path handoff payload if it keeps its
current hybrid shape. It may remain as a worker API or adapter-codec projection
after local final-hop assembly, but the delivery-command handoff should carry a
smaller task-dispatch content value.

Generic `correlation` is explicitly deferred. The roadmap must not create a
new broad `Map<String, String>` or a generic opaque correlation object as part
of the minimal delivery intent. Failure compensation still needs task/message
and attempt identity, but this roadmap treats that as a compact
failure-compensation reference or existing residue to be narrowed later, not as
the main delivery-command abstraction.

The first contract cutover may introduce a typed
`TaskDispatchExecutionContext` sidecar because the current worker frame and
result correlation path need attempt, retry, batch, and task display metadata.
That sidecar is not routing, lane, session, endpoint, lifecycle ownership, or a
generic correlation map.

## Boundary Decision

Delivery facts must be split by owner:

| Fact | Canonical owner | Target carrier | Forbidden carrier |
| --- | --- | --- | --- |
| `selectedWorkerId` | engine assignment | item command, envelope item, store sub-lane selector, outcome snapshot | routeKey, deliveryQueueKey, adapter session lookup as replacement worker identity |
| `adapterId` | adapter registration / assignment binding | command group, resolved group, local dispatch context | repeated per command after grouping; nested per-item packet unless explicitly wire-required |
| `deliveryQueueKey` | transport delivery lane/store | command group, handoff lane, store key | per command item |
| `targetTransportNodeId` | transport final-hop owner resolution | resolved command group / handoff lane | per command item |
| `routeKey` | route-owner evidence or explicit opaque connection metadata | resolved local dispatch metadata after owner lookup; packet metadata only when a worker/protocol frame requires it | assignment command field; second worker routing identity; conflicting command and owner values |
| `connectionId` / `connectionToken` | concrete adapter session / route-owner value | route-owner store and adapter-private session index | command item, batch codec, failure event command snapshot |
| task-dispatch content | task item / assignment payload owner | minimal item command content: task id, message id, event code, input, shared config | current hybrid `TaskDispatchItem`; generic `TransportPacket`; group metadata |
| task-dispatch execution context such as `attemptId`, `attemptNo`, `retryCount`, `batchId`, `taskName`, `project`, `userId` | engine assignment / worker-frame and result-correlation compatibility | typed sidecar on item command or compact failure snapshot | route/lane/session truth; generic `correlation` map; endpoint owner evidence |
| worker-wire compatibility `workerId` | selected worker assignment | derived from `selectedWorkerId` during local frame/API projection when still required | duplicated item payload field or scheduling truth |
| failure-compensation identity | engine assignment / attempt owner | compact failure snapshot or later typed attempt ref | generic command `correlation` map as delivery intent, routing truth, or open extension point |

Nested `TransportPacket` and `TaskDispatchItem` are governed by the same table.
A field is not allowed to remain per item merely because it moved from
`DeliveryCommand` into a packet or worker-facing view. Any temporary duplicate
must be named in the TDCFO-0 allowlist with a current wire requirement and a
removal owner.

The target mainline is:

```text
TaskDispatchBinding
  -> DeliveryCommandGroup(adapterId, minimal delivery intents)
  -> TransportAssignedDeliverySubmitter resolves route-owner/node evidence
  -> ResolvedDeliveryCommandGroup(adapterId, deliveryQueueKey, targetTransportNodeId, items...)
  -> TransportDeliveryCommandHandoff
  -> TransportDeliveryCommandListener
  -> per-item EndpointLease / ResolvedEndpoint lookup or revalidation
  -> local task-dispatch wire packet or worker API view assembled from resolved group context
  -> WorkerAdapter dispatch group/envelopes
  -> DispatchOutcome / TransportDeliveryFailureEvent snapshots
```

The item record is:

```text
AssignedDeliveryCommand
  commandId
  selectedWorkerId
  taskDispatchContent
  taskDispatchExecutionContext
  deadlineEpochMillis
  createdAtEpochMillis
```

The minimal task content record is:

```text
TaskDispatchContent
  taskId
  messageId
  eventCode
  input
  sharedConfig
```

The typed execution context is not generic correlation:

```text
TaskDispatchExecutionContext
  attemptId
  attemptNo
  retryCount
  batchId
  taskName
  project
  userId
```

Worker-wire compatibility fields are assembled later:

```text
TaskDispatchWirePacket
  packetId
  taskDispatchContent
  taskDispatchExecutionContext fields required by the current worker frame
  selectedWorkerId-derived workerId when the worker frame still requires it
  per-item endpoint route metadata only when the protocol frame still requires it
```

Failure-compensation identity is intentionally not modeled as generic command
correlation in this slice. The first implementation may preserve the current
minimum data needed to compensate delivery failure, but it must not become an
open map or transport routing field.

The group record is:

```text
DeliveryCommandGroup
  adapterId
  items
```

`deliveryQueueKey` may continue to be derived from `adapterId` until bucket or
lane sharding is introduced. If no independent lane policy exists, callers must
not be forced to provide both values.

Resolved group records add delivery facts after final-hop owner resolution:

```text
ResolvedDeliveryCommandGroup
  adapterId
  deliveryQueueKey
  targetTransportNodeId
  items
```

Endpoint evidence is per item, not a group fact:

```text
ResolvedDeliveryItem
  command
  endpointLease or resolvedEndpoint

EndpointLease / ResolvedEndpoint
  selectedWorkerId
  routeKey
  transportNodeId
  connectionId
  leaseExpireAtEpochMillis
```

Observation records use explicit context instead of a bloated command:

```text
DeliveryObservationGroupContext
  adapterId
  deliveryQueueKey
  targetTransportNodeId
  occurredAtEpochMillis

DeliveryObservationItemSnapshot
  commandId
  selectedWorkerId
  taskId
  messageId
  attemptId
  routeKey when endpoint evidence was already resolved at the observation site
```

## Non-Goals

- No Redis bucket or key-count compaction in this roadmap. That remains a later
  delivery-store physical-shape decision.
- No worker scheduling, worker lifecycle, admission, capacity, reservation, or
  retry ownership move into transport.
- No worker wire payload change unless a later slice explicitly proves the
  current wire contract needs it.
- No compatibility layer for the old command shape. This repo is pre-release;
  update in-repo callers and tests instead of keeping old and new paths alive.
- No routeKey semantic upgrade. `routeKey` remains opaque connection/domain
  metadata and must not become selected-worker truth.
- No broad correlation redesign in the first contract cutover. The first slice
  may preserve the minimum delivery-failure compensation identity required by
  engine retry/compensation and may introduce the narrow
  `TaskDispatchExecutionContext` sidecar, but it must not introduce a new
  generic correlation map or opaque extension object.

## Do Not Start With

Do not start by compressing Redis keys, adding hashes, adding buckets, or
renaming fields while preserving the same carriers. First remove the duplicate
fact carriers from the command model and process-boundary codec. Otherwise the
same ambiguity remains with smaller keys.

Do not preserve removed command fields through deprecated getters or adapter
wrappers. That would keep two live truth paths and make the next scale slice
harder.

Do not move removed command fields into nested `TransportPacket` or
`TaskDispatchItem` and call the command shape clean. Packet payloads and worker
views are part of the process-boundary value shape when they are serialized in
handoff queues; they must obey the same field ownership rules as top-level
command records.

Do not start by designing a broad replacement for `correlation`. Failure
compensation identity is necessary, but it is not the delivery executor's
central abstraction. Keep that work narrow and behind the minimal delivery
intent cutover.

## TDCFO-0 Inventory And Field Allowlist

Goal:

Create a field-owner inventory before editing behavior.

Scope:

- Inventory all production and test construction sites for:
  `DeliveryCommand`, `DeliveryCommandBatch`, `TransportDispatchEnvelope`,
  `DispatchOutcome`, `TransportDeliveryFailureEvent`,
  `TransportDeliveryCommandBatchCodec`,
  `TransportDeliveryFailureEventCodec`, `TaskDispatchItem`,
  `TransportPacket`, `TransportPacketFactory`, `JsonTransportPacketCodec`,
  adapter task-dispatch frame codecs, `TaskPullResult`,
  `ExternalWorkerApiController`, embedded SDK polling sessions, and public Java
  SDK worker polling sessions.
- Classify each occurrence of `adapterId`, `deliveryQueueKey`,
  `targetTransportNodeId`, `routeKey`, `connectionToken`, and
  `selectedWorkerId` as canonical owner, derived group metadata, item fact,
  observation snapshot, or residue.
- Classify nested `TransportPacket.adapterId` and `TransportPacket.routeKey`
  separately from top-level command fields. They may remain in a
  process-boundary item record only with an explicit current wire requirement
  and a removal owner.
- Classify each `TaskDispatchItem` field as one of:
  minimal task-dispatch content, worker-wire compatibility metadata,
  failure-compensation identity, route/connection metadata, or residue.
- Classify `attemptId`, `attemptNo`, `retryCount`, `batchId`, `taskName`,
  `project`, and `userId` as typed execution context candidates. They must not
  be hidden in generic command correlation or generic packet payload maps.
- Classify public worker API fields separately from handoff fields. A field may
  remain in public worker poll responses while being removed from the internal
  delivery-command handoff.
- Classify `TransportPacket` task-dispatch use separately from result and
  worker-system-event use. This roadmap converges task dispatch first; it must
  not accidentally break result or worker-system-event packet contracts.
- Decide whether to rename `DeliveryCommand` to `AssignedDeliveryCommand` in
  the same slice that removes group fields. Rename is justified if it prevents
  command from being treated as a full resolved delivery route.

Acceptance:

- A sibling inventory or an inventory section in this roadmap lists production
  construction sites and their target classification.
- The first implementation slice has an allowlist for fields that may remain on
  each model.
- Tests that only assert old field copying are identified for rewrite or
  deletion.

## TDCFO-1 Contract Cutover: Minimal Intent, Content, Packet, And Observation

Goal:

Replace the current command-as-full-route-owner and packet-as-dispatch-item
shape with one compiling contract cutover. This slice must move item facts,
minimal task-dispatch content, typed execution context, group facts, per-item
endpoint evidence, packet or worker-view assembly, outcome snapshots, and
failure events together. It must not leave a temporary fake `unknown` or
duplicated field path as a second truth.

Scope:

- Replace the current `DeliveryCommand` shape with an item-only command or
  rename it to `AssignedDeliveryCommand`. The item record may carry only:
  `commandId`, `selectedWorkerId`, minimal task-dispatch content, typed
  execution context, deadline, and created timestamp.
- Introduce `TaskDispatchContent` or an equivalent narrow value for the
  delivery-command handoff. It may carry only task id, message id, event code,
  input, and shared config.
- Introduce `TaskDispatchExecutionContext` or an equivalent narrow sidecar for
  execution/result-correlation and worker-frame compatibility fields:
  `attemptId`, `attemptNo`, `retryCount`, `batchId`, `taskName`, `project`, and
  `userId`. It must not contain `workerId`, `routeKey`, `adapterId`,
  `deliveryQueueKey`, `targetTransportNodeId`, `connectionId`, or
  `connectionToken`.
- Remove current `TaskDispatchItem` from the delivery-command handoff hot path.
  If `TaskDispatchItem` remains, it is a worker API or adapter-codec projection
  assembled after final-hop context is known, not the command payload.
- Introduce the producer group shape created by
  `TaskDispatchDeliveryCommandSubmitter`. The group owns `adapterId` and item
  commands. It does not own `deliveryQueueKey`, `targetTransportNodeId`,
  `connectionToken`, or route-owner evidence.
- Introduce the resolved group shape created by
  `TransportAssignedDeliverySubmitter`. The resolved group owns `adapterId`,
  derived `deliveryQueueKey`, `targetTransportNodeId`, and command items.
  Route key, connection id, session token, and endpoint lease evidence remain
  per-item `EndpointLease` / `ResolvedEndpoint` facts. Producer-side owner
  lookup may use per-item endpoint evidence to choose `targetTransportNodeId`,
  but only the target node is promoted to the group.
- Remove `deliveryQueueKey`, `targetTransportNodeId`, `connectionToken`, and
  producer-side `routeKey` from command construction, getters, tests, batch
  codec records, and failure-event command records.
- Move dispatch `TransportPacket` assembly out of starter-side command
  creation. Starter may build minimal task-dispatch content only. Final-hop
  transport assembles the local dispatch packet, worker API view, or equivalent
  envelope from the resolved group context when adapter delivery actually needs
  it.
- Stop using generic `TransportPacket` as the task-dispatch handoff item. It
  may remain for result and worker-system-event paths until those owners are
  separately reviewed.
- Move worker-wire compatibility fields such as `workerId`, `retryCount`,
  `batchId`, `taskName`, `project`, and `userId` out of the handoff item unless
  TDCFO-0 proves they are still required in the worker-facing frame. If still
  required, assemble them at final-hop from selected worker, assignment
  metadata, and task context.
- Derive worker-frame `workerId` from `selectedWorkerId` when the public worker
  API or worker frame still requires that field. Do not store a second
  worker-id field in task-dispatch content or generic packet payload.
- Remove `firstNonBlank(command.getRouteKey(), owner.routeKey())` style
  precedence. Assigned task delivery route metadata comes from the resolved
  per-item endpoint owner or from an explicit per-item endpoint decision; it
  does not come from a per-item assignment command field and it is not group
  metadata.
- Replace `TransportDeliveryFailureEvent(DeliveryCommand command, ...)` with a
  compact observation event that separates group snapshot, item snapshot, and
  outcome.
- Replace `DispatchOutcome.fromCommand(...)` style helpers with helpers that
  accept an item command plus explicit observation group context and item
  snapshot.
- Keep endpoint details in the item snapshot only when they were observed at
  that failure or delivery site. A group snapshot must not gain `routeKey`,
  `connectionId`, or session fields just because one item in the batch had
  those values.
- Do not introduce a new general-purpose correlation map in this slice. Keep
  only the minimum delivery-failure compensation identity needed by the engine,
  preferably in `TaskDispatchExecutionContext`, the failure snapshot, or a
  narrow sidecar, and leave broader typed-ref cleanup for a follow-up slice.
- Update `DeliveryCommandBatch`, `TransportDeliveryCommandBatchCodec`,
  `RedisTransportDeliveryCommandHandoff`,
  `TransportDeliveryCommandListener`, and
  `TransportAssignedDeliverySubmitter` in the same slice so the repo compiles
  without old command fields.
- Keep invalid-binding compensation possible by creating a compact failure
  snapshot from the assignment binding and producer group context, not by
  fabricating a full command with fake `unknown` group fields.

Acceptance:

- `DeliveryCommand` or its replacement no longer exposes
  `getDeliveryQueueKey()`, `getTargetTransportNodeId()`, or
  `getConnectionToken()`.
- `TaskDispatchDeliveryCommandSubmitter` no longer writes
  `deliveryQueueKey = adapterId` into every command.
- Command tests assert item facts only. Tests no longer assert lane, node,
  route, adapter, connection, worker-wire compatibility, or generic packet
  values on item commands.
- Delivery-command handoff tests no longer serialize `TaskDispatchItem` or
  `TransportPacket` as the item payload for task dispatch.
- `TransportDeliveryCommandBatchCodec` serializes `adapterId`,
  `deliveryQueueKey`, and `targetTransportNodeId` once per group or resolved
  group, not once per item. Route key, connection id, and endpoint lease data
  are not group fields.
- Per-command codec records do not contain `deliveryQueueKey`,
  `targetTransportNodeId`, `adapterId`, `routeKey`, or `connectionToken`,
  including inside nested serialized packet or worker-view fields unless
  TDCFO-0 names a current wire-required exception.
- Per-command codec records do not contain `TaskDispatchItem.transportPayload`
  or a generic `TransportPacket` task-dispatch record.
- Task-dispatch content tests prove `taskId`, `messageId`, `eventCode`,
  `input`, and `sharedConfig` are the only execution-content fields.
- Execution-context tests prove `attemptId`, `attemptNo`, `retryCount`,
  `batchId`, `taskName`, `project`, and `userId` are typed context fields and
  not route/lane/session fields.
- Resolved-group tests prove one
  `adapterId + deliveryQueueKey + targetTransportNodeId` group can contain
  multiple selected workers with different endpoint leases without promoting
  route key, connection id, or lease expiry to group metadata.
- Public worker poll tests prove `TaskPullResult` / server poll responses still
  expose the worker-facing projection required by SDK callers, while the
  internal delivery-command handoff no longer serializes `TaskDispatchItem`.
- `TransportDeliveryCommandListener` resolves the local adapter from the
  group, not from each item.
- `DispatchOutcome` producer-side helpers no longer read group or owner facts
  from command fields.
- Failure event codecs do not serialize full command records with group or
  session fields.
- Missing owner, node unavailable, handoff backpressure, and adapter
  unavailable each produce one retryable failure event.
- Distributed transport tests still prove batches are routed to the expected
  `targetTransportNodeId`.
- `TransportConvergenceArchitectureGuardTest` or an equivalent guard fails if
  command items regain lane/node/session fields or if packet assembly becomes
  a hidden copy of removed command fields.

## TDCFO-2 Reduce Envelope And Store-Key Duplication

Goal:

Prepare the delivery store and adapter dispatch path for high-volume queues
without repeating queue keys in every value.

Current status:

Implemented in the current working tree. The shared Redis keyed queue codec did
not need key-aware value decode because the value type changed:
`TransportDispatchEnvelope` no longer requires `deliveryQueueKey` to rebuild.
`TransportDeliveryService.enqueue(adapterId, envelopes)` derives the current
delivery lane, `TransportDeliveryStore.enqueue(deliveryQueueKey, envelope)`
passes that lane to the store, and `DeliveryQueueKey(deliveryQueueKey,
selectedWorkerId)` owns the physical queue key. Redis dispatch-envelope values
now omit `deliveryQueueKey`; only the Redis queue key part carries it.

Scope:

- Inventory showed `TransportDispatchEnvelope.deliveryQueueKey` was needed only
  as a value-field workaround for queue/store context, not by push adapter send
  logic or worker-facing projection.
- If adapter dispatch needs group metadata, introduce a dispatch group shape
  for adapter calls instead of forcing every envelope to carry
  `deliveryQueueKey`.
- Redis store values omit queue ownership by changing the envelope value type
  so the store key, not each queued item, owns the lane. No shared keyed queue
  primitive change is required for this slice.
- Keep `selectedWorkerId` item-level. Polling delivery still drains by
  selected worker and must never poll-and-discard from a shared lane.
- Keep `routeKey` as optional opaque dispatch metadata only where push adapters
  or direct-send validation require it.
- Update Redis envelope codecs so queue/store keys own queue partition facts
  when practical. Do not block this roadmap on bucket compaction.

Acceptance:

- Queue/store ownership is expressed by store key or dispatch group, not by a
  value field that must be synchronized with the key.
- Polling selected-worker tests still prove two workers sharing one routeKey
  and deliveryQueueKey cannot cross-consume assigned items.
- Push adapter tests still prove selected-worker send paths do not fall back to
  route-only delivery.

## TDCFO-3 Docs, Guards, And Proof Registry

Goal:

Make the new ownership hard to regress.

Scope:

- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` after implementation to
  describe the new command/group/envelope shapes as current truth.
- Update `doc/PROOF_REGISTRY.md` so transport delivery proof covers command
  fact ownership and process-boundary codec shape.
- Add or update architecture guards:
  - command item forbidden fields:
    `deliveryQueueKey`, `targetTransportNodeId`, `connectionToken`
  - per-command codec forbidden fields:
    `deliveryQueueKey`, `targetTransportNodeId`, `connectionToken`
  - delivery-command handoff item forbidden payload carriers:
    `TaskDispatchItem` and generic task-dispatch `TransportPacket`
  - nested packet forbidden fields in delivery-command handoff records:
    `adapterId` and `routeKey` unless listed by the TDCFO-0 allowlist as
    wire-required
  - generic `correlation` map must not become the new delivery executor
    abstraction; failure compensation identity must stay narrow and explicitly
    owned
  - `TaskDispatchExecutionContext` must not contain route, lane, adapter,
    endpoint, or session fields
  - public worker poll API may expose worker-facing projections, but those
    projections must not become the delivery-command handoff DTO
  - adapter dispatch must use selected-worker addressing, not route-only
    fallback
  - route-owner/session fields must not be command API fields
- Add a source scan that fails if production code rebuilds group facts from
  item command fields.

Acceptance:

- Baseline docs match implemented code and do not describe target state as
  current before the slice lands.
- Proof registry lists concrete tests for command item shape, group codec
  shape, minimal task-dispatch content shape, typed execution context shape,
  nested packet shape, failure event shape, selected-worker polling,
  selected-worker push, public worker poll API compatibility, public Java SDK
  polling compatibility, and Redis handoff.
- No non-archive doc keeps the old command-as-full-route-owner wording after
  the final slice.

## Suggested Implementation Order

1. Run TDCFO-0 and write the exact allowlist.
2. Implement TDCFO-1 as one contract cutover. Do not split command removal,
   `TaskDispatchItem` removal from the handoff hot path, typed execution
   context introduction, group ownership, per-item endpoint evidence, packet
   assembly, outcome helpers, and failure event shape into separate compiling
   states.
3. Implement TDCFO-2 after command, handoff, packet, and failure/outcome shape
   are stable.
4. Finish with TDCFO-3 guards, docs, proof registry updates, and residue scans.

## Verification Candidates

Focused command, handoff, and projection proof:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test "-Dtest=DeliveryCommandTest,TaskDispatchItemTest,TaskPullResultTest,TransportDispatchEnvelopeTest,JsonTransportPacketCodecTest,DispatchOutcomeTest,TransportDeliveryServiceTest,TransportAssignedDeliverySubmitterTest,TransportDeliveryCommandListenerTest,TransportDeliveryCommandBatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,TransportDeliveryFailureEventCodecTest,RedisTransportDeliveryFailureChannelTest,TransportPacketFactoryTest,TransportConvergenceArchitectureGuardTest"
```

Adapter selected-worker proof:

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test "-Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest,ServerSessionManagerShutdownTest,PollingWorkerAdapterTest"
```

SDK/starter distributed assembly proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=MassApplicationDistributedTransportTest,MassSdkTest,WorkerHeartbeatProjectionListenerTest,PullWorkerSessionTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Public worker API and Java SDK proof:

```bash
./mvnw -q -pl xa-mass-server,sdk/xa-mass-java-sdk -am test "-Dtest=ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems,WorkerClientTest,PollingWorkerSessionTest" -Dsurefire.failIfNoSpecifiedTests=false
```

The broader `ExternalWorkerPollingApiIntegrationTest#externalWorkerPollingApiCompletesTaskEndToEnd`
still contains stale `isWorkerReachable` / transport-presence wording and must
be re-baselined under a separate worker lifecycle versus transport route-owner
roadmap before it is used as proof here.

Residue scans:

```bash
rg -n "getTargetTransportNodeId|getConnectionToken|connectionToken|command deliveryQueueKey|command targetTransportNodeId|TaskDispatchItem|TransportPacket" transport sdk xa-mass-server -g "*.java"
rg -n "deliveryQueueKey|getDeliveryQueueKey" transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportDispatchEnvelope.java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeRecord.java
rg -n "\"deliveryQueueKey\"|\"targetTransportNodeId\"|\"connectionToken\"|\"adapterId\"|\"routeKey\"|\"transportPayload\"|\"workerId\"|\"batchId\"" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery -g "*.java"
```

The residue scans must be interpreted with the field allowlist from TDCFO-0:
group, store, outcome snapshot, and tests may still mention some names, but
per-command item carriers, per-command codec records, nested per-command packet
records, and `TaskDispatchItem`-backed handoff payloads must not.

## Completion Criteria

- Per-item command records carry only minimal delivery-intent facts:
  selected worker plus minimal task-dispatch content and typed execution
  context.
- `TaskDispatchExecutionContext` carries worker-frame/result-correlation
  fields only. It does not carry route, lane, adapter, endpoint, session, or
  queue ownership facts.
- `TaskDispatchItem` is no longer the delivery-command handoff payload. If it
  remains, it is a worker API / adapter-codec projection assembled after
  final-hop context is known.
- Public worker poll APIs may keep worker-facing projections, but those
  projections are not the internal handoff DTO and must not reintroduce
  command-level route, lane, node, or session truth.
- Generic task-dispatch `TransportPacket` is no longer the delivery-command
  handoff payload. If a packet remains, it is assembled at final-hop from
  minimal intent plus resolved endpoint/lane context.
- Group/lane/node facts are carried once per command group or handoff lane.
- Route key, connection id, session token, lease expiry, and resolved endpoint
  evidence are per-item endpoint facts, not group facts.
- Session/connection evidence remains in route-owner and adapter-private
  session indexes, not in command item payloads.
- Failure and outcome events are observation snapshots with explicit source
  context, not a reason to keep command items bloated. Generic `correlation`
  cleanup is deferred, but it must not remain or return as delivery routing,
  lane, session, or packet truth.
- Store and adapter dispatch paths have a documented owner for queue keys that
  does not require every queued item to duplicate lane metadata.
- Transport baseline, proof registry, and architecture guards prevent old
  command field ownership from returning.
