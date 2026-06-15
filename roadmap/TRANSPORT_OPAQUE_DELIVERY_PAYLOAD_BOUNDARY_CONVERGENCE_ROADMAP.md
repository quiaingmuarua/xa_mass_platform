# Transport Opaque Delivery Payload Boundary Convergence Roadmap

Status: proposed direction document.

Date: 2026-06-15

Depends on:

- `doc/archive/transport/2026-06-15_TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md`

Related roadmaps:

- `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md`
- `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md`

Removed stale predecessor:

- The previous active `Transport Worker Pull Dispatch View Convergence
  Roadmap` treated `PulledTaskDispatch`, `TaskPullResult`, and
  `TaskPullChannel` as the transport/API polling contract. That target is
  obsolete: public polling DTOs belong to SDK/server public worker API
  ownership, while transport pull contracts, if retained, are opaque delivery
  executor contracts. The stale active roadmap was removed instead of kept as a
  parallel superseded file so active docs do not preserve two incompatible pull
  contracts.

## Summary

Transport should be a pure assigned-worker delivery executor. It should not
understand task item structure, event handler semantics, retry attempt shape,
worker-facing envelope layout, or public polling DTOs.

The current transport command boundary still carries task-shaped values:

```text
DeliveryCommand
  deliveryBucketId
  selectedWorkerId
  TaskDispatchContent(taskId, messageId, eventCode, input, sharedConfig)
  TaskDispatchExecutionContext(attemptId, attemptNo, retryCount, batchId)
```

That shape was useful while removing older dispatch envelopes, but it is still
too wide for the target boundary. `input` as `Map<String, Object>` is a
historical payload-projection residue: transport should not normalize or decode
worker task input just to deliver an already assigned item.

Target shape:

```text
engine/starter assignment
  -> selectedWorkerId + deliveryBucketId
  -> engine/starter-owned worker payload encoding
  -> DeliveryCommand(opaque payload + opaque correlation)
  -> transport queue / selected-worker handoff
  -> adapter sends opaque payload to selected worker
  -> DispatchOutcome(opaque correlation + delivery status)
  -> engine/starter resolves correlation and compensates
```

Transport may validate delivery mechanics such as nonblank ids, payload size,
deadline expiry, queue capacity, and selected-worker consumer availability. It
must not inspect:

- `taskId`
- `messageId`
- `eventCode`
- `input`
- `sharedConfig`
- `attemptId`
- `attemptNo`
- `retryCount`
- `batchId`

Those facts may still exist in engine/starter, public worker APIs, worker SDK
DTOs, result correlation, or external protocol projections. They should not be
owned by `transport_api` command/outcome models.

`eventCode` is deliberately included in the forbidden list for
`DeliveryCommand`. It may be part of a worker-owned payload envelope so a worker
adapter can select the handler without decoding the handler input, but that
envelope is still opaque to transport.

## Current Code Observations

These observations are from the current work tree and must be rechecked before
implementation:

- `transport_api` model `TaskDispatchContent` imports
  `TaskDispatchBinding`, `TaskDispatchContext`, `TransportPacket`, and
  `TransportJsonValueNormalizer`.
- `TaskDispatchContent` stores `taskId`, `messageId`, `eventCode`,
  `Map<String, Object> input`, and `Map<String, Object> sharedConfig`.
- `TaskDispatchExecutionContext` stores `attemptId`, `attemptNo`,
  `retryCount`, and `batchId`.
- `DeliveryCommand` and `AdapterDispatchRequest` both carry
  `TaskDispatchContent` and `TaskDispatchExecutionContext`.
- `DispatchOutcome.fromCommand(...)` and `DispatchOutcome.fromRequest(...)`
  extract task id, message id, attempt id, and attempt number from transport
  delivery request objects.
- `TaskDispatchDeliveryCommandSubmitter` in starter converts
  `TaskDispatchContext + TaskDispatchBinding` into transport-owned
  `TaskDispatchContent` and `TaskDispatchExecutionContext`.
- WebSocket and socket frame codecs build canonical worker task frames from
  `AdapterDispatchRequest.content()` and `executionContext()`.
- `QueuedPulledDispatch` stores task-shaped content/context and converts it
  into `PulledTaskDispatch`.
- Redis codecs serialize nested `TaskDispatchContentRecord` and
  `TaskDispatchExecutionContextRecord`.
- `TaskPullChannel`, `TaskPullResult`, `TaskPullStatus`, and
  `PulledTaskDispatch` live in `transport_api` while still exposing
  task-shaped polling views.

The core gap: transport is still doing worker task payload projection instead
of delivering a payload prepared by the assignment/SDK boundary.

## Owner Review

### Engine / Starter Own

- task id, item/message id, event code, shared config, attempt id/no, retry
  count, batch id, and result correlation
- translation from `TaskDispatchContext + TaskDispatchBinding` into a
  worker-facing payload
- the worker protocol payload shape for assigned task dispatch
- opaque delivery correlation format
- compensation and retry decisions after delivery failure

Engine core should still avoid importing transport-specific contracts.
Starter/assembly is the correct current owner for translating assignment facts
into transport commands unless a neutral base contract is introduced.

### Transport Owns

- `deliveryBucketId`
- `selectedWorkerId`
- opaque delivery payload storage and handoff
- selected-worker consumer resolution
- final-hop adapter invocation
- delivery status and failure observation
- queue capacity, local delivery deadline checks, ack/release mechanics, and
  bounded diagnostics

Transport must copy opaque payload and opaque correlation, but it must not
decode either to understand task semantics.

### Adapter Owns

- selected-worker local session lookup
- protocol-specific send mechanics
- raw/manual route side-channel behavior where explicitly supported

Adapters should not assemble task dispatch fields from transport model objects.
For assigned delivery, the server-side transport adapter receives an opaque
payload and writes it to the selected worker connection/session according to
that adapter's send mechanics.

### Worker Adapter / Worker Runtime Own

- parsing the worker-facing payload envelope
- reading `eventCode` if the worker protocol carries it as an envelope header
- selecting the worker-local event handler
- decoding handler input only at the handler boundary

If worker-side code needs efficient handler selection, the payload may be shaped
as a worker-owned envelope:

```json
{
  "eventCode": "example.event",
  "item": { "...": "..." }
}
```

Transport must still treat that value as one opaque payload string or byte
array. The presence of `eventCode` in the payload does not make `eventCode` a
transport command field.

### SDK / Server Public Worker Boundary Owns

- public polling response DTOs if the external worker API remains task-shaped
- backward-facing worker SDK projections where desired
- decoding a worker payload into public `PulledTaskDispatch`-like models

The public worker API may still expose a task-shaped DTO. The boundary rule is
that this projection cannot live in transport core command/store/outcome
models.

## Target Transport Models

Preferred minimal internal shape:

```java
public final class DeliveryCommand {
    private final String commandId;
    private final String deliveryBucketId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long deliverBeforeEpochMillis;
    private final long createdAtEpochMillis;
}
```

`payload` is opaque to transport. For the first implementation slice, use a
UTF-8 string because current worker protocols already exchange JSON frames.
If a later worker protocol needs binary delivery, change this field to bytes in
one slice instead of introducing content-type semantics into transport.

```java
private final byte[] payload;
```

The important rule is not byte-vs-string. The important rule is that transport
does not model the task payload as Java task fields or `Map<String, Object>`.
Do not add a `DeliveryPayload` wrapper unless it retires another live model in
the same slice and does not add content-type or schema semantics.

Final-hop adapter request:

```java
public final class AdapterDispatchRequest {
    private final String deliveryId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long createdAtEpochMillis;
}
```

Delivery outcome:

```java
public final class DispatchOutcome {
    private final String deliveryId;
    private final String selectedWorkerId;
    private final String correlationRef;
    private final DispatchOutcomeStatus status;
    private final boolean retryable;
    private final String reason;
    private final long occurredAtEpochMillis;
}
```

This roadmap migrates `DispatchOutcome` in place. Do not add a parallel
`DeliveryOutcome` track in the same repository unless every caller is migrated
and the old name is deleted in that same slice.

Polling transport result should be transport-shaped:

```java
public final class PulledDeliveryMessage {
    private final String deliveryId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long createdAtEpochMillis;
}
```

If public worker APIs still need:

```text
taskId + messageId + eventCode + input + sharedConfig + attempt context
```

then SDK/server projection owns decoding the payload into that DTO outside
transport runtime.

## Boundary Rules

- `transport_api` delivery models must not import `xa-mass-base` dispatch
  classes.
- `transport_api` delivery models must not import `TransportPacket` to assemble
  task payload fields.
- `DeliveryCommand` is a delivery intent, not a task item model.
- `payload` is opaque bytes/string, not a structured task DTO.
- `contentType` is not part of the default transport delivery command. Worker
  protocols own their envelope contract.
- `eventCode` must not be a `DeliveryCommand`, `AdapterDispatchRequest`, or
  `DispatchOutcome` field.
- `correlationRef` is copied by transport and decoded only by the owner that
  minted it.
- `DispatchOutcome` is delivery observation, not task lifecycle truth.
- Adapter task dispatch must send the payload it was given; it must not rebuild
  canonical task JSON from transport-owned task fields.
- Polling inbox storage may isolate by `selectedWorkerId`, but it must not
  require task field decoding to store or return one delivery message.
- Public worker DTO compatibility, if retained, is SDK/server projection work,
  not a reason to keep task fields in transport command models.

## Non-Goals

- Do not change engine worker selection, runtime claim, or task retry policy.
- Do not re-open DQK queue-key ownership here.
- Do not rename every transport internal id here.
- Do not remove raw/manual route side-channels.
- Do not redesign result ingest payloads in this roadmap.
- Do not preserve old `TaskDispatchContent` /
  `TaskDispatchExecutionContext` paths as compatibility aliases.
- Do not build a generic schema registry or payload inspection framework inside
  transport.

## Do Not Start With

Do not start by renaming `TaskDispatchContent.input` to `payload` while keeping
the same task fields and `Map<String, Object>` normalization. That only hides
the boundary leak.

Do not start by moving `PulledTaskDispatch` into another transport package while
keeping transport runtime responsible for task-shaped worker DTO projection.

Do not put `taskId`, `messageId`, or `attemptId` into a structured
transport-owned "correlation" object that transport can read. If transport can
read the fields, the owner boundary has not moved.

## Phase 0 - Inventory And Freeze

Goal: lock the real caller and storage surface before changing model shape.

Actions:

- Inventory production and test callers of:
  - `TaskDispatchContent`
  - `TaskDispatchExecutionContext`
  - `DeliveryCommand`
  - `AdapterDispatchRequest`
  - `DispatchOutcome`
  - `PulledTaskDispatch`
  - `TaskPullResult`
  - `TaskPullStatus`
  - `TaskPullChannel`
  - `QueuedPulledDispatch`
  - `PullWorkerSession` constructors and factories
  - `TransportBinding`
  - `ResolvedPullWorkerTransport`
  - `TransportRuntimeRegistry.resolvePullWorkerTransport(...)`
  - `WorkerTransportRuntimeFactory`
  - `TransportDeliveryCommandBatchCodec`
  - `RedisQueuedPulledDispatchCodec`
  - WebSocket/socket task frame codecs
- Classify every usage as:
  - assignment-to-transport translator
  - handoff/store codec
  - adapter final-hop send
  - polling worker public projection
  - failure/outcome compensation
  - test fixture
- Confirm whether external worker HTTP and Java SDK should keep their current
  task-shaped response DTOs in this slice.

Acceptance:

- The implementation slice knows which owner will perform task-payload
  encoding and which owner will decode `correlationRef`.
- Current compile proof exists before migration starts.
- Existing DQK delivery invariants remain unchanged.

Verification:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
rg -n "TaskDispatchContent|TaskDispatchExecutionContext|PulledTaskDispatch|TaskPullResult|TaskPullStatus|TaskPullChannel|DispatchOutcome|AdapterDispatchRequest" transport sdk xa-mass-server -g "*.java"
```

## Phase 1 - Lock Payload, Correlation, And Public Poll Decisions

Goal: make the owner decisions and public polling surface explicit before any
production transport model change.

Decisions and pre-slice changes:

- Use `String payload` for the first implementation slice. It is an opaque
  UTF-8 worker dispatch frame. Do not introduce `DeliveryPayload`,
  `contentType`, schema ids, or a binary wrapper in this roadmap.
- Starter / public worker API owns a named worker dispatch payload codec, for
  example `TaskDispatchPayloadCodec`. For the current protocols, the encoded
  payload is the adapter-ready canonical task-dispatch JSON frame: it contains
  any worker-needed `eventCode`, task/item ids, input, shared config, retry
  count, and batch id inside the opaque string. Transport copies and sends that
  string; it does not rebuild it. The same owner provides the decoder used by
  SDK/server polling projection; transport runtime/adapters must not implement
  ad hoc task JSON parsing.
- Starter owns a named correlation ref codec/resolver, for example
  `TaskDispatchDeliveryCorrelationCodec`. `correlationRef` is an opaque string
  to transport and is sufficient for starter/engine failure handling to recover
  the task id, message id, attempt id, and attempt number needed for
  compensation.
- Keep `DispatchOutcome` as the class name for this roadmap and migrate it in
  place. Do not add a parallel `DeliveryOutcome`.
- Keep external HTTP/Java SDK polling responses task-shaped for now, but move
  that projection out of transport core. Introduce SDK/public-contract owned
  polling DTOs first, then migrate:
  - `WorkerClientOperations`
  - `MassSdkApplication.pollTasks(...)`
  - `PullWorkerSession` public constructors and factories
  - `PullWorkerSession.poll(...)` / `pollResult(...)`
  - `PullWorkerSession.submitResult(...)`
  - `MassApplication.openPullWorkerSession(...)`
  - `ExternalWorkerApiController`
  - `TransportBinding`
  - `ResolvedPullWorkerTransport`
  - `TransportRuntimeRegistry.resolvePullWorkerTransport(...)`
  - `WorkerTransportRuntimeFactory` / adapter bootstrap registration paths

  After this pre-slice, no public SDK/server worker method should expose
  `transport_api` task-shaped `PulledTaskDispatch`, `TaskPullResult`,
  `TaskPullStatus`, or `TaskPullChannel`. Runtime binding objects may carry an
  internal opaque pull channel only if that type is not a public SDK/server
  constructor or method contract. Transport API/runtime can then switch to
  opaque pulled delivery messages without breaking the public worker API
  surface.

Acceptance:

- The first code slice has no production period where old task-shaped
  `TaskDispatchContent` / `TaskDispatchExecutionContext` and new payload fields
  are both live command paths.
- `TaskDispatchPayloadCodec` has equivalence tests proving its encoded output
  matches the current websocket/socket canonical worker task frame shape before
  adapters are changed to pass payload through, and its decoder is the only
  SDK/server projection path from opaque payload to public polling DTO.
- The failure inbox consumer has a starter/engine-owned resolver for
  `correlationRef` before task id/message id/attempt fields are removed from
  transport outcomes.
- SDK/server public polling DTOs and constructors no longer import or return
  transport-owned `TaskPullChannel`, `TaskPullResult`, `TaskPullStatus`, or
  `PulledTaskDispatch`.
- Temporary internal adaptation from the old transport pull DTO to the new
  public DTO is allowed only inside SDK/server until Phase 2 removes the old
  transport pull DTOs. Do not expose both DTO families as public API.

## Phase 2 - Atomic Opaque Delivery And Polling Contract Pivot

Goal: replace the task-shaped assigned-delivery and polling transport contracts
in one compile-safe behavior slice.

Actions:

- Change `TaskDispatchDeliveryCommandSubmitter` to encode
  `TaskDispatchContext + TaskDispatchBinding` into:

  ```text
  payload
  correlationRef
  deliveryBucketId
  selectedWorkerId
  ```

- Replace `DeliveryCommand` fields:

  ```text
  TaskDispatchContent + TaskDispatchExecutionContext
  ```

  with:

  ```text
  payload + correlationRef
  ```

- Replace `AdapterDispatchRequest` fields with:

  ```text
  deliveryId + selectedWorkerId + payload + correlationRef + createdAt
  ```

  Adapter selection still comes from handoff-private `DeliveryCommandReference`
  / dispatch grouping, not from the payload.
- Migrate `DispatchOutcome` in place to carry:

  ```text
  deliveryId + selectedWorkerId + correlationRef + status + retryable + reason + occurredAt
  ```

- Update `TransportDeliveryCommandBatchCodec`,
  `TransportDeliveryCommandListener`, `TransportAssignedDeliverySubmitter`,
  `TransportDeliveryFailureEventCodec`, in-memory handoff, and Redis handoff
  together so no old command/request/outcome path remains.
- Introduce transport-owned opaque pull models in the same slice, for example:

  ```text
  PulledDeliveryMessage(deliveryId, selectedWorkerId, payload, correlationRef, createdAt)
  DeliveryPullStatus
  DeliveryPullResult(status, items)
  DeliveryPullChannel
  ```

- Change `TransportDeliveryService`, transport polling store APIs,
  `QueuedPulledDispatch`, `RedisQueuedPulledDispatchCodec`, and polling adapter
  internals to store and return opaque delivery messages only. This must happen
  in the same slice as `AdapterDispatchRequest` changes because the current
  polling store path directly builds `QueuedPulledDispatch.from(request)`.
- Remove or replace transport-internal use of task-shaped `TaskPullChannel`,
  `TaskPullResult`, `TaskPullStatus`, and `PulledTaskDispatch`. If a transport
  pull interface is still needed, it must be the opaque delivery pull
  interface.
- SDK/server public polling projection decodes the opaque payload into the
  public task-shaped DTO introduced in Phase 1. The projection owner is
  SDK/server/public worker API code, not transport runtime/adapters, and it
  must use the named `TaskDispatchPayloadCodec` rather than local JSON parsing.
- Update WebSocket/socket assigned dispatch channels to send
  `AdapterDispatchRequest.payload()` as-is to the selected worker. Adapter
  final-hop code must not call `request.content()`,
  `request.executionContext()`, or rebuild canonical task JSON.
- Update polling worker pull flow so the worker-facing public DTO is produced
  outside transport core from the opaque pulled delivery payload.
- Keep adapter-local result decode unchanged unless a separate result-ingress
  roadmap approves changing it.

Acceptance:

- `transport_api` delivery models no longer import `TaskDispatchBinding`,
  `TaskDispatchContext`, `TransportPacket`, or task payload normalizers.
- Production code no longer calls `TaskDispatchContent.from(...)` or
  `TaskDispatchExecutionContext.from(...)`.
- Redis and in-memory handoff codecs round-trip opaque `payload` and
  `correlationRef` without task-field reconstruction.
- `TransportDeliveryService`, `QueuedPulledDispatch`, and
  `RedisQueuedPulledDispatchCodec` contain no task fields, attempt fields,
  `TaskDispatchContent`, or `TaskDispatchExecutionContext`.
- Transport runtime/adapters do not decode worker task payloads to produce
  public DTOs.
- Transport failure events cannot be used as task lifecycle read models, but
  starter/engine compensation still works through `correlationRef`.
- Redis failure channel carries only delivery outcome facts plus
  `correlationRef`; `MassApplication` or the starter-owned resolver converts
  the correlation back into `TaskDispatchDeliveryFailure` for engine
  compensation. Missing endpoint, backpressure, adapter unavailable, and
  failure-emission retry paths must each prove compensation still receives task
  id, message id, attempt id, and attempt number through the resolver.
- WebSocket/socket selected-worker delivery and DQK wrong-worker prevention
  tests still pass.
- Server/SDK tests prove external polling still returns the chosen public
  response shape.

## Phase 3 - Delete Residue, Guards, And Owner Docs

Goal: make the new boundary difficult to regress.

Actions:

- Delete `TaskDispatchContent` and `TaskDispatchExecutionContext` from
  `transport_api`.
- Delete task-shaped `TaskPullChannel`, `TaskPullResult`, `TaskPullStatus`, and
  `PulledTaskDispatch` from `transport_api` once SDK/server public DTOs and
  opaque transport pull contracts are in place.
- Remove task-shaped records from Redis/in-memory delivery codecs.
- Update:
  - `transport/AGENTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  - related active transport roadmaps whose target shape still lists
    `TaskDispatchContent` or `TaskDispatchExecutionContext`
- Add architecture guards:
  - transport delivery models must not import `xa-mass-base` dispatch classes
  - transport delivery models must not contain task payload fields
  - assigned adapter dispatch code must not read task fields from transport
    commands
  - transport outcome models must not expose task lifecycle fields
  - task-shaped polling DTOs must not live in transport core
  - assigned-delivery models must not add `contentType` or `DeliveryPayload`
    unless a later binary protocol decision explicitly replaces the plain
    payload field
  - guard scans for `DeliveryPayload` / `contentType` are exact-file scans over
    assigned delivery command/request/outcome/handoff/polling codec files, not
    broad scans over result ingest, public SDK/server DTOs, or unrelated
    transport model packages.

Acceptance:

- Production source has no `TaskDispatchContent` or
  `TaskDispatchExecutionContext`.
- Assigned-delivery command/request/outcome models have no `taskId`,
  `messageId`, `eventCode`, `input`, `sharedConfig`, `attemptNo`,
  `retryCount`, or `batchId` fields. Result-ingest models and public
  SDK/server worker DTOs are outside this guard.
- Assigned-delivery command/request/outcome models have no `contentType` field.
- `TaskPullChannel`, `TaskPullResult`, `TaskPullStatus`, and
  `PulledTaskDispatch` are removed from `transport_api` unless they are
  replaced with opaque delivery pull contracts.
- Owner docs describe transport as opaque delivery executor, not task payload
  projection owner.

## Verification Plan

Compile:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Focused tests:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=TaskDispatchPayloadCodecTest,TaskDispatchDeliveryCorrelationCodecTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=DeliveryCommandTest,DispatchOutcomeTest,TransportDeliveryCommandBatchCodecTest,TransportDeliveryServiceTest,TransportDeliveryPollResultTest,QueuedPulledDispatchTest,RedisQueuedPulledDispatchCodecTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,TransportDeliveryFailureEventCodecTest,TransportConvergenceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am test -Dtest=PollingWorkerAdapterTest,SocketTaskDispatchChannelTest,SocketTransportFrameCodecTest,WebSocketTaskDispatchChannelTest,WebSocketTransportFrameCodecTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=MassSdkTest,WorkerClientOperationsTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest,ExternalWorkerApiControllerTest,ExternalWorkerPollingApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Mandatory new-test guard:

```powershell
Test-Path sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/starter/TaskDispatchPayloadCodecTest.java
Test-Path sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/starter/TaskDispatchDeliveryCorrelationCodecTest.java
```

These guards must return `True` before the focused command using
`-Dsurefire.failIfNoSpecifiedTests=false` is accepted as proof. The flag is only
allowed to avoid reactor `-am` upstream-module false failures; it is not allowed
to hide missing mandatory tests in the owning module.

Residue scans:

```powershell
rg -n "TaskDispatchContent|TaskDispatchExecutionContext" transport sdk xa-mass-server -g "*.java"
rg -n "TaskPullChannel|TaskPullResult|TaskPullStatus|PulledTaskDispatch" transport/transport_api/src/main/java -g "*.java"
rg -n "taskId|messageId|eventCode|sharedConfig|attemptNo|retryCount|batchId" `
  transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java `
  transport/transport_api/src/main/java/com/xa/mass/transport/model/AdapterDispatchRequest.java `
  transport/transport_api/src/main/java/com/xa/mass/transport/model/DispatchOutcome.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchCodec.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/QueuedPulledDispatch.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisQueuedPulledDispatchCodec.java
rg -n "DeliveryPayload|contentType" `
  transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java `
  transport/transport_api/src/main/java/com/xa/mass/transport/model/AdapterDispatchRequest.java `
  transport/transport_api/src/main/java/com/xa/mass/transport/model/DispatchOutcome.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchCodec.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/QueuedPulledDispatch.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisQueuedPulledDispatchCodec.java `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureEventCodec.java
rg -n "TaskDispatchBinding|TaskDispatchContext" transport/transport_api/src/main/java -g "*.java"
rg -n "request\\.content\\(|getContent\\(|executionContext\\(|getExecutionContext\\(" transport/websocket-adapter transport/socket-adapter transport/polling-adapter -g "*.java"
```

## Completion Criteria

- Transport assigned-delivery command/request/outcome models are opaque payload
  carriers plus selected-worker delivery facts.
- Transport delivery models do not expose task item fields, attempt fields, or
  worker payload maps.
- Transport delivery models do not introduce content-type semantics or a
  `DeliveryPayload` wrapper by default.
- Worker task payload assembly is owned outside transport core.
- Transport polling store and transport pull interfaces are opaque delivery
  message carriers, not task-shaped worker DTO projection owners.
- Public polling/API shape is either intentionally preserved by SDK/server
  projection or intentionally replaced with opaque payload delivery.
- Failure compensation still works through opaque correlation.
- Owner docs and guards prevent reintroducing task-shaped delivery command
  models.
