# Transport Result Ingress Convergence Roadmap

Status: proposed direction document.

## Summary

Transport delivery has converged toward a pure delivery executor. Result ingress
still carries older task-shaped transport models.

The target is not to move result lifecycle into transport. The target is to make
transport result ingress a narrow callback carrier:

- worker-facing result protocol belongs to SDK/server/worker API surfaces
- result correlation and lifecycle validation belong to starter/engine result
  owners
- transport owns only adapter receive, inbox buffering, queueing, codec, and
  runtime handoff mechanics
- transport result ingress is one logical channel, not necessarily one physical
  queue; optional opaque partition keys may drive inbox sharding

## Concrete Convergence Goals

This roadmap is successful when the result-ingress boundary has this concrete
shape:

1. `transport_api` no longer owns a task-shaped result callback model.
   `TaskResultReport` and task-shaped `TransportResultEnvelope` are removed
   from transport mainline or moved to the owner that actually exposes that
   worker-facing contract.
2. The final result ingress channel is `TransportResultIngressChannel` or an
   equivalent single-method channel that accepts a transport-owned opaque
   result ingress item. The old `TaskResultIngestChannel` contract is not
   extended during staging and is removed or replaced during the mainline pivot.
3. Adapters and worker submit paths may parse worker result protocol frames,
   but they hand transport only opaque payload, opaque correlation, optional
   opaque partition key, and bounded diagnostics.
4. Starter/assembly owns task-shaped callback decoding through a named
   `TaskResultCallbackCommand` and `TaskResultCallbackCodec` or an equivalent
   starter-owned contract.
5. Engine result owners remain the only result correctness authority. Attempt
   identity, lease identity, duplicate handling, late/stale result behavior,
   retry, finality, and task mutation continue through
   `TaskResultIngestFacade`, `TaskManager`, and `TaskResultService`.
6. Transport result inboxes provide only minimum handoff consistency: accepted
   items are buffered, queued, claimed, and acknowledged without being
   destructively lost before the starter/engine delegate handles them.
   Transport does not implement result retry policy, task timeout,
   compensation, or final recovery.
7. Result ingress handling returns a typed outcome, not a bare boolean. Durable
   inbox ack is driven by `HANDLED_APPLIED`, `HANDLED_NOOP`, or
   `PERMANENT_REJECT`; retryable infrastructure failure remains unacked or is
   requeued by transport inbox mechanics.
8. `adapterId`, `routeKey`, connection/session facts, and optional
   `partitionKey` are not result correctness facts. They may be diagnostics or
   queue partitioning hints only.

Current result ingress already routes into engine-owned result convergence, but
the transport API still exposes `TaskResultReport`, `TransportResultEnvelope`,
and a dual `TaskResultIngestChannel` entry point. This roadmap removes that
task-shaped result payload projection from transport mainline.

The first behavior-changing implementation slice must be compile-safe. It must
not remove the old dual result channel before every production implementer and
caller has been retargeted. After the inventory slice, the next executable cut
is an atomic mainline pivot: introduce the new opaque transport ingress
envelope, introduce the owner-owned result callback codec/command, retarget
runtime channels, adapters, SDK/server submit paths, Redis inbox, pump, and
tests, then remove the old overloads in the same compile point.

## Current Code Observations

These are current implementation facts, not target state:

- `TaskResultIngestChannel` exposes two paths:
  `ingest(TaskResultReport)` and `ingest(TransportResultEnvelope)`.
- `RedisTaskResultIngestChannel#ingest(TaskResultReport)` returns `false`, so
  the channel interface already has a half-live path.
- `TaskResultReport` lives in `transport_api` and carries task-shaped fields:
  `taskId`, `messageId`, `success`, `detail`, `errorCode`, and `output`.
- `TaskResultReport` also builds and decodes `TransportPacket` payload
  projections, so transport owns a worker result schema instead of only an
  ingress carrier.
- `TransportResultEnvelope` lives in `transport_api` and wraps
  `TaskResultReport` with `adapterId`, `routeKey`, `attemptId`, `leaseToken`,
  and `traceId`.
- WebSocket and socket adapters decode canonical task-result frames into
  `TaskResultReport`, then wrap it in `TransportResultEnvelope`.
- Polling worker submit paths build `TaskResultReport` in `PullWorkerSession`
  and submit a `TransportResultEnvelope`.
- Server external worker submit-result API imports transport model
  `TaskResultReport`.
- Split runtime uses `RedisTaskResultIngestChannel` as a result inbox producer
  on transport-consumer nodes and `TaskResultIngestInboxPump` to drain into a
  local runtime result channel on engine-producer nodes.
- `RuntimeTaskResultIngestChannel` in SDK/starter validates attempt or lease
  identity by calling engine-owned `TaskResultIngestFacade#getResultCorrelation`
  before calling `ingestTaskResult(...)`.
- Engine result lifecycle is owned by `TaskManager` and `TaskResultService`.
  Active lease, duplicate, late, stale, retry, and final result decisions do
  not live in transport runtime.

## Owner Review

Worker result protocol belongs to the worker-facing boundary.

SDK/server worker APIs may expose task-shaped callback fields such as
`taskId`, `messageId`, `success`, `detail`, `errorCode`, and result output
when that is the public worker contract. That shape must not be owned by
`transport_api`.

Transport owns result ingress mechanics:

- adapter receive of worker callback frames or requests
- local async buffering
- Redis result inbox enqueue and drain mechanics
- process-boundary result ingress codec
- transport logging and bounded backpressure

Transport does not own:

- task result schema
- active lease truth
- attempt identity truth
- result retry or finality
- task progress mutation
- worker scheduling or worker lifecycle

Starter owns translation from the transport result ingress carrier into
engine-owned result callback commands. Engine owns correlation lookup and
result mutation through `TaskResultIngestFacade`, `TaskManager`, and
`TaskResultService`.

There is no requirement for one global task-shaped worker result DTO. Public
worker DTOs may remain owned by their caller boundary:

- server HTTP request/response models stay in `xa-mass-server`,
- public Java SDK request DTOs stay in `sdk/xa-mass-java-sdk`,
- embedded SDK worker/session DTOs stay in `sdk/xa-mass-embedded-sdk` or its
  API package when intentionally public,
- starter-internal `TaskResultCallbackCommand` and
  `TaskResultCallbackCodec` live in starter/SDK assembly code and translate
  into `TaskResultIngestFacade`.

Do not move public worker submit-result DTOs into `xa-mass-base` merely to get
them out of transport. `xa-mass-base` currently owns the engine-facing
`TaskResultIngestFacade` and `TaskResultCorrelation`; broadening it into public
worker API ownership would conflict with the server dependency boundary unless
that boundary is explicitly redesigned.

`adapterId` and `routeKey` are ingress diagnostics or protocol context only.
They must not be required to decide whether a task result is valid or final.

`partitionKey` is an optional queue-partition hint. It may be derived from a
task id, tenant id, trace id, or another owner-owned correlation fact before
transport receives it, but transport treats it as opaque and only uses it for
inbox partition selection.

## Boundary Decision

Apply a minimum result-ingress rule:

1. Transport result inboxes carry a single ingress envelope shape.
2. That ingress envelope carries opaque worker result payload, opaque result
   correlation, optional opaque partition key, plus optional ingress
   diagnostics.
3. Transport does not expose task-shaped result DTOs from `transport_api`.
4. Transport result channels do not have a direct task-shaped overload.
5. Adapter code may identify a frame or request as a worker result, but must
   not project it into engine task-result fields inside transport-owned APIs.
6. Result identity validation is engine/starter-owned. Transport may carry
   attempt or lease tokens as opaque correlation, but must not own their
   validation semantics.
7. Result handling outcome is typed. `HANDLED_APPLIED`, `HANDLED_NOOP`, and
   `PERMANENT_REJECT` mean the transport inbox may ack the item because the
   owner has handled it, even when no task state mutation occurred.
   `RETRYABLE_FAILURE` means the item must remain retryable through transport
   inbox mechanics.
8. Redis result inbox Lua stays simple: capacity check plus enqueue. It must
   not apply result lifecycle, retry, lease, or finality decisions.
9. Accepted result inbox items must not be destructively lost before the
   starter/engine delegate explicitly handles them. Redis may use a small
   transport-owned claim/inflight/ack mechanism or an equivalent visibility
   timeout/requeue mechanism. That mechanism protects transport inbox
   consistency only; it must not decide result retry, finality, task timeout,
   or compensation policy.
10. A single result ingress channel is a logical contract. Physical result inbox
   storage may be one queue, shard queues, node-local queues, or another
   transport-owned partitioning strategy.
11. `partitionKey` may select or hash to a result inbox queue, but it must never
   be interpreted as task lifecycle truth, worker routing truth, adapter route
   truth, or result validity truth.
12. Public worker API compatibility is an SDK/server decision. In-repo transport
   models are not compatibility anchors.

## Target Shape

The final shape should be conceptually:

```text
worker callback
  -> adapter receives protocol frame/request
  -> transport result ingress envelope
       opaqueResultPayload
       opaqueResultCorrelation
       optionalOpaquePartitionKey
       optional ingressDiagnostics
  -> local buffer or Redis result inbox
  -> starter/assembly result translator
  -> starter-owned TaskResultCallbackCommand
  -> TaskResultIngestFacade
  -> engine result convergence
  -> ResultIngressHandleOutcome
  -> transport inbox ack or retryable release
```

Transport-owned result ingress envelope should be minimal:

```text
ingressId or delivery callback id
opaque payload bytes/string
opaque correlation bytes/string
opaque partition key only for inbox sharding, optional
receivedAt / trace id only if needed for transport diagnostics
```

It should not contain task-shaped fields:

```text
taskId
messageId
success
detail
errorCode
output
project
taskName
workerGroupId
selectedWorkerId
adapterNodeId
deliveryQueueKey
```

`partitionKey` is allowed only when it is named and treated as an opaque
partitioning hint. It must not be renamed to `taskId`, `messageId`,
`workerId`, `routeKey`, `adapterId`, or another owner-specific identity inside
transport APIs.

Owner-owned task result callback commands may still contain task-shaped fields,
but they must live outside transport. The default target for this roadmap is a
starter-owned internal `TaskResultCallbackCommand` plus
`TaskResultCallbackCodec`; public server and Java SDK DTOs remain boundary
local and map into that command before transport sees only opaque ingress
values.

The result handling outcome is also owner-owned outside transport API. Transport
may observe only whether the outcome is ackable or retryable for inbox
mechanics. It must not infer result lifecycle meaning from task-shaped rejection
reasons.

## Non-Goals

- Do not redesign engine result convergence.
- Do not change `TaskResultService` retry, duplicate, late, stale, or finality
  semantics.
- Do not introduce a new result security model in this roadmap.
- Do not require `leaseToken` enforcement until generation, storage, expiry,
  retry compatibility, and rejection semantics are explicitly designed.
- Do not redesign public worker result JSON in the same slice unless the slice
  explicitly targets server/SDK public contract cleanup.
- Do not merge this roadmap into delivery executor work. Result ingress has a
  different direction, caller set, and proof surface.
- Do not move engine result facade imports into transport runtime.
- Do not move public worker submit-result DTOs into `xa-mass-base` unless a
  separate server dependency-boundary decision approves that change.

## Do Not Start With

Do not start by globally deleting `TaskResultReport` or
`TransportResultEnvelope`.

First classify all call sites and create the owner-owned replacement result
callback shape. Then retarget adapters, SDK/server entry points, Redis inbox
codec, and tests to the new envelope. Only after callers are moved should the
old transport result models and overloads be removed.

Do not remove `TaskResultIngestChannel#ingest(TaskResultReport)` or
`TaskResultIngestChannel#ingest(TransportResultEnvelope)` in a slice that leaves
production implementers or callers on the old methods. The channel-method
removal belongs to the atomic mainline pivot after the replacement contracts and
all call sites are ready.

Do not add another abstract method to `TaskResultIngestChannel` in a staging
slice. It is currently used as a single-abstract-method interface by production
wiring. Also do not add a default `false` method to preserve compilation; that
would recreate the half-live result path this roadmap is removing. Staging must
introduce an independent `TransportResultIngressChannel` plus
`TransportResultIngressEnvelope` without changing the old production contract.

Also do not replace task-shaped transport DTOs with a wrapper that still
contains the same task fields in `transport_api`. That preserves the old owner
problem under a new name.

## TRI-0 - Inventory And Owner Classification

Goal: produce a code-grounded inventory before changing behavior.

Scope:

- Inventory all production and test usages of:
  - `TaskResultReport`
  - `TransportResultEnvelope`
  - `TaskResultIngestChannel`
  - `RedisTaskResultIngestChannel`
  - `BufferedTaskResultIngestChannel`
  - `TaskResultIngestInboxPump`
  - `TransportResultEnvelopeCodec`
  - adapter result frame codecs
  - `RuntimeTaskResultIngestChannel`
  - `TaskResultIngestFacade`
  - public worker submit-result APIs and Java SDK DTOs
- Classify each usage as:
  - worker public protocol
  - adapter protocol parsing
  - transport inbox mechanics
  - starter/assembly translator
  - engine result correlation
  - engine result mutation
  - test fixture
  - stale documentation
- Decide the owner package for the task-shaped result callback command.
  Default decision: starter owns internal `TaskResultCallbackCommand` and
  `TaskResultCallbackCodec`; server and Java SDK keep their public DTOs in
  their own modules; `xa-mass-base` remains engine-facing result facade and
  correlation only.
- Decide the transport result ingress envelope shape and whether the opaque
  payload is stored as JSON string, bytes, or another explicit runtime value.
- Decide how `partitionKey` is generated, whether it is required or optional,
  and which owner supplies it. Transport may consume it only as an opaque
  sharding hint.

Acceptance:

- A sibling inventory file records current usages, owner category, and target.
- The inventory separates production usage from tests and fixtures.
- The inventory names the replacement owner for task-shaped result callback
  fields.
- The inventory confirms no plan requires `xa-mass-server` main sources to
  depend directly on `xa-mass-base`.
- The inventory names the `partitionKey` source and confirms it is not a
  transport-owned task identity field.
- No code behavior changes in this slice.

Suggested verification:

```powershell
rg -n "TaskResultReport|TransportResultEnvelope|TaskResultIngestChannel|RedisTaskResultIngestChannel|BufferedTaskResultIngestChannel|TaskResultIngestInboxPump|TransportResultEnvelopeCodec" `
  transport `
  sdk/xa-mass-embedded-sdk/src/main/java `
  sdk/xa-mass-java-sdk/src/main/java `
  xa-mass-server/src/main/java `
  xa-mass-engine/src/main/java `
  xa-mass-base/src/main/java `
  --glob '!**/target/**'
```

## TRI-1 - Stage Parallel Result Ingress Contracts

Goal: introduce the replacement result ingress contracts without changing the
old production `TaskResultIngestChannel` shape.

Scope:

- Introduce `TransportResultIngressEnvelope` or equivalent transport-owned
  ingress item with opaque payload, opaque correlation, and optional opaque
  `partitionKey`.
- Introduce `TransportResultIngressChannel` or equivalent single-method
  transport ingress channel.
- Keep the old `TaskResultIngestChannel` production contract unchanged in this
  slice. Do not add abstract methods and do not add default `false` methods.
- Introduce the starter-owned `TaskResultCallbackCommand` and
  `TaskResultCallbackCodec` outside `transport_api`.
- Introduce `ResultIngressHandleOutcome` or equivalent starter/assembly outcome
  with at least:
  - `HANDLED_APPLIED`
  - `HANDLED_NOOP`
  - `PERMANENT_REJECT`
  - `RETRYABLE_FAILURE`
- Ensure the new transport envelope does not expose task-shaped fields.
- Keep trace id or received-at facts only if they are diagnostics, not result
  correctness facts.
- Define that `partitionKey` is used only by transport inbox partitioning and
  is not decoded by transport.
- Do not retarget production call sites in this slice unless the slice also
  keeps the repo compiling and tests passing.

Acceptance:

- New transport result ingress contract has one main ingestion method available
  for the next slice.
- `TaskResultIngestChannel` remains source-compatible with current production
  implementers and lambda wiring during this staging slice.
- New transport result ingress model does not contain task-shaped callback
  fields.
- New transport result ingress model may contain `partitionKey`, but no
  transport API field may expose it under a task, worker, route, or adapter
  identity name.
- Starter-owned result callback command and codec live outside `transport_api`.
- Typed result handling outcome exists and documents which outcomes are
  inbox-ackable versus retryable.
- Engine-facing result callback still carries the data `TaskResultService`
  needs to apply current result behavior.
- No transport runtime class imports `TaskResultIngestFacade` or
  engine result mutation classes.
- Existing production result ingest behavior still compiles unchanged after
  this staging slice.

Suggested verification:

```powershell
rg -n "class .*Result.*Envelope|record .*Result.*Envelope|interface .*Result.*Ingest" `
  transport/transport_api/src/main/java `
  xa-mass-base/src/main/java `
  sdk/xa-mass-embedded-sdk/src/main/java `
  --glob '!**/target/**'

rg -n "taskId|messageId|success|errorCode|output" `
  transport/transport_api/src/main/java/com/xa/mass/transport `
  --glob '!**/target/**'
```

Remaining hits in the second command must be unrelated packet or legacy code
called out by the inventory before TRI-2 starts.

## TRI-2 - Atomic Mainline Pivot Out Of Transport Result DTOs

Goal: make the task-shaped callback projection owned by SDK/starter/server
boundaries, not by transport, and retire the old transport result DTO mainline
in one compile-safe cut.

Scope:

- Retarget `RuntimeTaskResultIngestChannel` to translate the opaque transport
  ingress envelope into the starter-owned result callback command.
- Move or replace `TaskResultReport` in this slice so worker-facing task
  result fields are not imported from `transport_api`.
- Replace `TransportResultEnvelope` with the opaque transport ingress envelope
  or narrow it until it no longer carries a transport-owned task result model.
- Replace old `TaskResultIngestChannel` production wiring with
  `TransportResultIngressChannel` or the chosen equivalent single-method
  contract, then delete the old interface or remove it from production
  mainline.
- Preserve current engine result semantics through `TaskResultIngestFacade`.
- Keep attempt or lease identity validation in starter/engine result ingress,
  backed by `TaskResultIngestFacade#getResultCorrelation`.
- Map engine/starter result handling into typed `ResultIngressHandleOutcome`
  values. Permanent invalid results such as task-not-found, no active lease, or
  stale/duplicate no-op must be ackable handled outcomes, not unbounded
  transport requeue.
- Remove route-key or adapter-id dependency from result correctness checks.
  Those values may remain only as diagnostics if the target envelope supports
  them.
- Retarget WebSocket result frame processing, socket result frame processing,
  polling `PullWorkerSession#submitResult(...)`, `WorkerClientOperations`, and
  server external worker submit-result mapping in the same slice.
- Replace `WorkerClientOperations#submitResult(String, TaskResultReport)` with
  an embedded-SDK-owned worker result submit command, such as
  `WorkerResultSubmitRequest` or `WorkerResultSubmitCommand`. Server external
  worker DTOs map to that SDK-owned command; they must not depend on
  starter-internal `TaskResultCallbackCommand`.
- Retarget `BufferedTaskResultIngestChannel`, `RedisTaskResultIngestChannel`,
  `TaskResultIngestInboxPump`, and the process-boundary codec in the same
  slice.
- Delete or fully remove from production mainline the old transport result DTOs
  after all production callers in this scope are retargeted.

Acceptance:

- `RuntimeTaskResultIngestChannel` no longer consumes a transport-owned
  task-shaped result DTO.
- WebSocket and socket adapters no longer instantiate `TaskResultReport`.
- Polling worker result submit no longer constructs `TransportResultEnvelope`
  around a task-shaped transport DTO.
- Server external worker result API does not import
  `com.xa.mass.transport.model.TaskResultReport`.
- `WorkerClientOperations#submitResult(...)` no longer accepts a transport
  result DTO or starter-internal callback command.
- `WorkerClientOperations#submitResult(...)` accepts an embedded-SDK-owned
  worker result submit command or request model.
- `TaskResultCallbackCodecTest` proves the starter-owned callback command can
  round-trip the fields needed by current engine result ingest without making
  transport own those fields.
- Typed result handling outcome distinguishes ackable handled results from
  retryable infrastructure failures.
- Result correlation validation still rejects or accepts no-op for stale
  attempt or lease identity using engine-owned correlation.
- Engine result mutation still happens only through `TaskResultIngestFacade`.
- `transport_api` no longer owns `TaskResultReport`.
- Old `TaskResultIngestChannel` is deleted or absent from production mainline.
- `TransportResultIngressChannel` or the chosen replacement exposes one opaque
  result ingress method.
- Buffer and Redis result inbox use one typed opaque envelope.
- Process-boundary codec serializes the transport ingress envelope once.

Suggested verification:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests install
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -Dtest=TaskResultCallbackCodecTest,RuntimeTaskResultIngestChannelTest,PullWorkerSessionTest,MassSdkTest test
.\mvnw.cmd -pl xa-mass-server -Dtest=ExternalWorkerApiControllerTest test

rg -n "TaskResultIngestFacade|TaskResultCorrelation|TaskResultService|TaskManager" `
  transport `
  --glob '!**/target/**'
```

The search should not find transport main-source imports of engine/base result
mutation owners except intentionally neutral transport-facing channel types.

## TRI-3 - Adapter And Worker Submit Path Proof

Goal: prove adapters and worker submit paths produce the new transport ingress
envelope without projecting into transport task-result models after TRI-2.

Scope:

- Tighten WebSocket result frame processing tests.
- Tighten socket result frame processing tests.
- Tighten polling `PullWorkerSession#submitResult(...)` tests.
- Tighten external worker HTTP submit-result and Java SDK public worker DTO
  tests so they do not import transport result models.
- Keep public worker API behavior stable unless explicitly changed by the
  slice.
- Keep adapter protocol parsing local to the adapter. The adapter may identify
  a result frame, but transport API must receive opaque ingress values.

Acceptance:

- Java SDK worker result submit DTOs remain SDK-owned or server-owned, not
  transport-owned.
- Server external worker result DTOs map to an embedded-SDK-owned submit
  command, not to transport result DTOs or starter-internal callback commands.
- Current worker result submit E2E path still reaches engine result ingest.
- Source scan proves no production adapter, SDK worker session, or server
  external worker API imports old transport result DTOs.

Suggested verification:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests install
.\mvnw.cmd -pl transport/websocket-adapter -Dtest=WebSocketInputProcessorTest test
.\mvnw.cmd -pl transport/socket-adapter -Dtest=SocketTransportServerTest test
.\mvnw.cmd -pl transport/polling-adapter -Dtest=PollingWorkerAdapterTest test
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -Dtest=TaskResultCallbackCodecTest,PullWorkerSessionTest,MassSdkTest,RuntimeTaskResultIngestChannelTest test
.\mvnw.cmd -pl xa-mass-server -Dtest=ExternalWorkerApiControllerTest test
```

## TRI-4 - Result Inbox Reliability And Partitioning

Goal: make transport result inbox mechanics minimally reliable without turning
transport into the result lifecycle owner.

Scope:

- Keep `BufferedTaskResultIngestChannel` typed to one result ingress item.
- Keep `RedisTaskResultIngestChannel` on the new single envelope ingestion
  method.
- Keep the process-boundary codec on the new opaque ingress envelope.
- Add a transport-owned inbox partition resolver if the first implementation
  needs more than one physical result queue. The resolver may hash
  `partitionKey`; it must not decode or validate it.
- Keep Redis offer script simple: capacity check plus enqueue.
- Add Redis claim/inflight/ack or equivalent visibility/requeue semantics for
  result inbox drain. A pump crash, delegate exception, or
  `RETRYABLE_FAILURE` outcome must not silently discard an accepted result
  inbox item.
- Preserve split runtime behavior:
  - transport-consumer nodes enqueue result ingress envelopes
  - engine-producer nodes drain and translate into engine result ingest
- Define what `ingest(...)` means: accepted by transport inbox, not necessarily
  applied to engine result state.
- Define what `handle(...)` means: an owner outcome, not a boolean. Ack only
  `HANDLED_APPLIED`, `HANDLED_NOOP`, and `PERMANENT_REJECT`; do not ack
  thrown exceptions or `RETRYABLE_FAILURE`.

Acceptance:

- Redis result inbox does not decode or validate task result fields.
- Redis result inbox partitioning, if present, is driven only by opaque
  `partitionKey` or an internal fallback shard rule.
- Queue partition selection does not affect result correctness. Engine result
  correlation remains the only correctness authority.
- Inbox pump acknowledges only after the delegate returns an ackable typed
  outcome.
- Delegate exception or `RETRYABLE_FAILURE` leaves the item retryable through
  transport-owned visibility/requeue mechanics.
- `PERMANENT_REJECT` and no-op stale/duplicate handled outcomes are acked and
  do not create unbounded Redis requeue loops.
- Transport result inbox does not implement retry policy, finality, stale
  attempt decisions, task timeout, or compensation.

Suggested verification:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime -am -DskipTests install
.\mvnw.cmd -pl transport/transport_runtime -Dtest=BufferedTaskResultIngestChannelTest,RedisTaskResultIngestChannelTest test

rg -n "LinkedBlockingQueue<Object>|instanceof TaskResultReport|ingest\\(TaskResultReport|TaskResultReportRecord" `
  transport/transport_runtime/src/main/java `
  --glob '!**/target/**'
```

## TRI-5 - Residue, Docs, And Guard Cleanup

Goal: remove stale docs, tests, and guard gaps after TRI-2 has removed old
task-shaped transport result models from production mainline.

Scope:

- Remove any old tests that preserve transport ownership of task result fields.
- Update adapter codec tests to assert opaque ingress behavior.
- Update SDK/server tests to assert owner-owned result callback behavior.
- Update stale docs and inventories that still describe `TaskResultReport`,
  task-shaped `TransportResultEnvelope`, or old `TaskResultIngestChannel` as
  active transport contracts.
- Tighten architecture guards so the old DTOs cannot return to transport API
  or transport runtime.

Acceptance:

- No production or test source preserves `TaskResultReport` or task-shaped
  `TransportResultEnvelope` as transport-owned contracts.
- `TransportResultEnvelopeTest` and `TaskResultReportTest` are removed or
  rewritten under the owner that now owns the corresponding behavior.
- No active roadmap or owner baseline describes the old DTOs as current
  transport mainline.
- Guards fail if production transport API/runtime reintroduces task-shaped
  result callback DTOs.

Suggested verification:

```powershell
rg -n "TaskResultReport|TransportResultEnvelope" `
  transport `
  sdk `
  xa-mass-server `
  xa-mass-engine `
  xa-mass-base `
  --glob '!**/target/**'
```

Remaining hits must be the owner-owned replacement, migration notes, or tests
for the explicit owner.

## TRI-6 - Guards And Owner Docs

Goal: prevent task-shaped result ownership from returning to transport.

Scope:

- Add architecture guards for transport API and runtime.
- Guard that transport result ingress contracts do not expose task-shaped
  callback fields.
- Guard that transport runtime/adapters do not import the owner-owned engine
  result mutation APIs.
- Guard that server/SDK worker result APIs do not import transport result DTOs.
- Update `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  once the implemented code shape changes.
- Update SDK/server docs if public result submit model ownership changes.

Acceptance:

- Guard fails if `transport_api` reintroduces a task-shaped result DTO.
- Guard fails if `TaskResultIngestChannel` regains multiple overloads with
  different result ownership semantics.
- Guard fails if transport runtime imports `TaskResultIngestFacade`,
  `TaskResultCorrelation`, `TaskManager`, or `TaskResultService`.
- Guard allows adapter-local protocol constants only when they do not become
  transport API model fields.
- Owner docs describe implemented behavior, not target state.

Suggested verification:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests install
.\mvnw.cmd -pl transport/transport_api test
.\mvnw.cmd -pl transport/transport_runtime -Dtest=TransportConvergenceArchitectureGuardTest,RedisTaskResultIngestChannelTest,BufferedTaskResultIngestChannelTest test

rg -n "TaskResultReport|TransportResultEnvelope|TaskResultIngestFacade|TaskResultCorrelation|TaskManager|TaskResultService" `
  transport/transport_api/src/main/java `
  transport/transport_runtime/src/main/java `
  transport/websocket-adapter/src/main/java `
  transport/socket-adapter/src/main/java `
  transport/polling-adapter/src/main/java `
  --glob '!**/target/**'
```

Remaining hits must be explicitly allowlisted adapter-local protocol parsing or
the new neutral transport result ingress contract.

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- Transport result ingress has one canonical opaque envelope/channel path.
- `transport_api` no longer owns task-shaped result callback DTOs such as
  `TaskResultReport` or task-shaped `TransportResultEnvelope`.
- Old `TaskResultIngestChannel` is removed or absent from production mainline;
  the replacement ingress channel exposes one opaque result ingress method and
  no direct task-shaped overloads.
- Adapters and worker submit paths hand transport opaque result ingress values
  instead of constructing transport-owned task result models.
- `WorkerClientOperations#submitResult(...)` accepts an SDK-owned worker result
  submit command/request, not a transport DTO or starter-internal callback
  command.
- Starter/assembly owns the result callback command and codec that translate
  opaque transport ingress into engine result ingest.
- Starter/assembly owns typed result handling outcomes, and durable inbox ack
  is driven by ackable versus retryable outcome, not by a bare boolean.
- Redis and in-memory/buffered result inboxes carry opaque result ingress
  envelopes and do not parse task result fields.
- Result inboxes do not destructively lose accepted items before the
  starter/engine delegate handles them; claim/inflight/ack or equivalent
  visibility/requeue semantics are proven where durable inboxes are used.
- Result inbox storage may use one physical queue or partitioned queues, but
  partitioning is transport-owned infrastructure and does not encode result
  validity or task lifecycle semantics.
- Any `partitionKey` exposed through transport result ingress is opaque and is
  used only for queue partitioning.
- WebSocket, socket, polling, server, and Java SDK result submit paths still
  reach engine result convergence.
- Engine result lifecycle behavior is unchanged unless an explicit engine
  result roadmap changes it.
- Attempt and lease validation, when present, remains starter/engine-owned and
  backed by engine result correlation.
- `adapterId` and `routeKey` are not result correctness facts.
- Transport does not implement result retry policy, task timeout,
  compensation, or final recovery.
- Architecture guards prevent transport from re-owning result schema or result
  lifecycle APIs.
- `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md` reflect
  the implemented result ingress boundary.
- Residue scan has been run and the completed roadmap is archived after current
  facts are moved to owning docs.

## Verification Candidates

Focused compile:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,xa-mass-server -am -DskipTests compile
```

Focused tests:

```powershell
.\mvnw.cmd -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,xa-mass-server -am -DskipTests install
.\mvnw.cmd -pl transport/transport_api test
.\mvnw.cmd -pl transport/transport_runtime -Dtest=BufferedTaskResultIngestChannelTest,RedisTaskResultIngestChannelTest,TransportConvergenceArchitectureGuardTest test
.\mvnw.cmd -pl transport/websocket-adapter -Dtest=WebSocketInputProcessorTest test
.\mvnw.cmd -pl transport/socket-adapter -Dtest=SocketTransportServerTest test
.\mvnw.cmd -pl transport/polling-adapter -Dtest=PollingWorkerAdapterTest test
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -Dtest=TaskResultCallbackCodecTest,RuntimeTaskResultIngestChannelTest,PullWorkerSessionTest,MassSdkTest test
.\mvnw.cmd -pl xa-mass-server -Dtest=ExternalWorkerApiControllerTest test
```

Mandatory new tests such as `TaskResultCallbackCodecTest` and the opaque
transport ingress envelope test must be created before their owning slice is
marked done; do not rely on `-Dsurefire.failIfNoSpecifiedTests=false` for
completion proof.

The dependency-materialization commands use `-am` so clean workspaces do not
consume stale local Maven artifacts. The subsequent focused `-Dtest` commands
run on the module that owns the named tests; do not combine `-am` with
module-specific `-Dtest` unless the reactor is known not to propagate the test
selection to upstream modules that do not contain those tests.

Residue scans:

```powershell
rg -n "TaskResultReport|TransportResultEnvelope|TaskResultIngestChannel" `
  transport `
  sdk `
  xa-mass-server `
  xa-mass-engine `
  xa-mass-base `
  --glob '!**/target/**'

rg -n "taskId|messageId|success|errorCode|output" `
  transport/transport_api/src/main/java/com/xa/mass/transport `
  transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime `
  --glob '!**/target/**'
```

Remaining hits must be owner-owned replacement contracts, adapter-local wire
parsing, migration notes, or tests that explicitly prove the new boundary.
If `partitionKey` appears in transport result ingress code, it must appear only
as an opaque partition hint and not as a decoded task, worker, route, or adapter
identity.
