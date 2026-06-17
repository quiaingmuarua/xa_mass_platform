# Transport Route Key Removal Convergence Roadmap

Status: active direction; adapter-command-executor prerequisite has landed,
routeKey removal implementation has not started.

## Summary

`routeKey` used to be a convenient name while transport mixed connection
addressing, route-owner leases, raw route sends, and task dispatch delivery.
After the recent delivery convergence, assigned task delivery is no longer
route-key based:

```text
deliveryBucketId -> delivery queue address
selectedWorkerId -> correctness and demux
endpoint lease / session handle -> final hop
opaque payload / correlation -> worker-visible data
```

In that shape, keeping `routeKey` as a first-class transport concept adds cost
without owning a real decision. It sounds like a task dispatch route, so new
callers tend to reuse it for worker correctness, queue choice, lifecycle,
presence, or raw adapter routing. The target is to remove the name and the model
concept from current transport surfaces.

Target principle:

```text
assigned task delivery   = deliveryBucketId + selectedWorkerId + opaque payload
endpoint/session address = adapter-local endpointAddress, only if the adapter needs one
raw/manual output        = explicit raw endpoint side-channel or deleted
diagnostics             = bounded endpoint/session metadata, never routeKey
```

This roadmap exists to reduce concepts. It should not introduce a replacement
top-level routing id. If a concrete adapter still needs an address, that value
belongs to adapter/session internals as `endpointAddress`, not to assigned
delivery or worker lifecycle.

`deliveryBucketId` is owned by engine/starter. Transport may derive the current
delivery queue address from it, but must not add its own bucket sharding or
owner-partition policy in this roadmap.

## Relationship To Other Roadmaps

- Generic process-node identity removal is already completed and archived as
  historical context. This roadmap must not reopen node identity while removing
  routeKey from endpoint/session managers and transport owner docs.
- This roadmap owns only the `routeKey` removal slice and should not pull in
  adapter-id, gateway-id, transport-hint, or queue-key renames unless required
  to delete routeKey safely.
- `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md` already keeps
  assigned delivery free of routeKey. This roadmap removes the leftover session,
  packet, raw-route, and diagnostic vocabulary that still makes routeKey look
  alive.
- `TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md` has unblocked the
  routeKey cleanup by separating assigned-delivery command execution from
  raw/manual route channels, diagnostics, endpoint lease projection, and worker
  presence projection. RouteKey removal should now treat adapter internals as
  cleanup surfaces, not as the command-executor owner split.

## Current Code Observations

These are current-code observations from the route-key surface. They must be
rechecked before implementation because the transport tree is actively
changing.

- Assigned delivery command models already reject routeKey as a per-command
  fact: `DeliveryCommand`, `AdapterDispatchRequest`, `DispatchOutcome`, and
  pulled delivery DTOs should stay route-key free.
- `RouteEndpointIndex` still indexes sessions by `routeKey` and exposes
  `entriesForRoute(...)`, `endpointForRoute(...)`, and `routeKey` entry fields.
- WebSocket and socket session managers still accept routeKey during connect,
  use it for session indexing and raw route send, and log it as routeKey.
- Polling worker sessions still mint a routeKey from worker group via
  `CanonicalWorkerGroupRouteKeyCodec` and send it through session presence and
  endpoint lease claims.
- `WorkerSessionPresenceEvent` carries `routeKey`, and starter presence ingress
  forwards it as trace/attribute metadata.
- Worker-runtime session presence APIs still accept routeKey:
  `WorkerPresenceRuntime.sessionConnected(...)`, `sessionHeartbeat(...)`, and
  `sessionDisconnected(...)` pass it into
  `InMemoryWorkerPresenceRuntime.PresenceSessionRecord`.
- `TransportEndpointLeaseClaim`, heartbeat, and release now expose
  `endpointAddress`; however many callers still pass their routeKey value into
  that endpoint-address slot.
- `TransportPacket` still has `routeKey` and a `withTransportAddress(...)`
  method. This keeps the old transport packet shape alive even though assigned
  delivery no longer uses packet-backed commands.
- `InboundEnvelope` still carries `routeKey`.
- `WorkerEndpointSnapshot` exposes `routeKey` through diagnostics.
- `RawWorkerRouteEndpointRegistry`, `RawWorkerMessageChannel`, and
  `TransportOutboundMessage` keep explicit route-addressed raw/manual output.
- Server E2E helpers still append `routeKey` to realtime worker URLs.
- WebSocket managed worker ingress still has an adapter-local
  `routeKeyForWorkerGroup(...)` mint rule, and socket hello still treats
  `routeKey` as a required frame field through `ROUTE_KEY_FIELD` /
  `extractRouteKey(...)`.
- `MassApplication.sendRawTransportMessage(workerId, ...)` is a worker-id API
  that currently resolves a single `WorkerEndpointSnapshot.getRouteKey()` and
  sends through `RawWorkerMessageChannel.sendToAdapterRoute(...)`.
- Active docs and proof registry still describe routeKey as opaque connection
  metadata and route-only raw helpers as allowed residue.

## Owner Review

- Engine owns scheduling, selected worker choice, dispatch binding, retry, and
  compensation. Engine must not receive, mint, or interpret routeKey.
- Worker runtime owns worker lifecycle, reachability projection, candidate
  evidence, admission, and worker-report projection. It may receive bounded
  session presence evidence, but routeKey must not become worker lifecycle or
  reachability truth.
- Worker-runtime session presence contracts are in scope because transport
  session evidence enters through them. Removing `WorkerSessionPresenceEvent`
  routeKey without removing the worker-runtime routeKey parameter would only
  force starter to fabricate the old value.
- Transport owns protocol sessions, endpoint/session lease evidence, delivery
  mechanics, raw/manual output if retained, and result ingress. Transport may
  keep adapter-local endpoint addresses, but not a cross-layer routeKey concept.
- SDK/starter owns managed session assembly defaults. If an adapter needs an
  internal endpoint address, SDK/starter may create one inside assembly, but
  worker APIs should not expose a routeKey parameter or getter.
- Concrete adapters own wire parsing and adapter-local connection maps. A
  WebSocket query field or socket hello field may be renamed or removed there;
  it is not a platform delivery concept.
- Worker-facing wire is a public worker surface for this roadmap. A WebSocket
  query parameter, socket hello field, or Java SDK session field named
  `routeKey` is still a leak even if concrete adapters parse it.

## Boundary Decision

Remove `routeKey` as a first-class current transport concept.

Allowed after convergence:

- adapter-local variable names only when they describe a temporary wire value
  being decoded during migration, and only inside the concrete adapter slice
- `endpointAddress` inside endpoint/session lease and adapter session managers,
  if the adapter genuinely needs a stable address distinct from
  `deliveryBucketId`, `selectedWorkerId`, and `sessionHandle`
- endpoint/session leases keyed by `deliveryBucketId + workerId`, with
  `sessionHandle` / `endpointLeaseId` carrying stale-session protection;
  generic lease correctness must not require a group-derived route address
- raw/manual endpoint addressing only through a clearly named raw endpoint
  channel, not through route-key vocabulary
- archived docs and this roadmap

Forbidden after convergence:

- routeKey on assigned delivery commands, dispatch requests, outcomes, pulled
  delivery messages, delivery queues, handoff references, result ingress, or
  worker public APIs
- routeKey as polling worker isolation, delivery queue choice, endpoint lease
  lookup key, worker lifecycle truth, worker reachability truth, or adapter
  selection
- routeKey as a WebSocket query field, socket hello field, Java SDK worker
  session field, or managed worker-wire requirement
- `CanonicalWorkerGroupRouteKeyCodec` as a transport API model
- `RawWorkerRouteEndpointRegistry` / `sendToAdapterRoute(...)` naming on current
  production surfaces
- `TransportPacket.routeKey`, `InboundEnvelope.routeKey`,
  `WorkerSessionPresenceEvent.routeKey`, and `WorkerEndpointSnapshot.routeKey`
  as current model fields

No compatibility aliases are required inside this pre-release internal runtime.
If all in-repo callers move, delete the old names.

## Target Shape

### Assigned Delivery

No routeKey:

```text
DeliveryCommand
  commandId
  deliveryBucketId
  selectedWorkerId
  opaque payload
  opaque correlationRef
  createdAt/deadline facts

Delivery queue
  deliveryQueueKey derived from deliveryBucketId
  queued item carries selectedWorkerId

Final hop
  selectedWorkerId -> current endpoint/session lease
```

### Endpoint And Session

If the adapter needs an address:

```text
Endpoint/session lease
  deliveryBucketId
  selectedWorkerId / workerId
  endpointDriverId
  sessionHandle
  endpointLeaseId
  optional endpointAddress
```

`endpointAddress` is not a dispatch route. It is a driver-owned address or
correlation value. If a driver can address by session handle alone, it should
not invent endpointAddress just to replace routeKey. The generic endpoint lease
contract should be correct with `sessionHandle` / `endpointLeaseId`; concrete
adapters may add endpointAddress only as local metadata or protocol address.

### Session Presence

Session presence should carry only worker/session evidence needed by
worker-runtime projection and bounded diagnostics:

```text
WorkerSessionPresenceEvent
  workerId
  adapter/session mode metadata
  sessionToken or endpointLeaseId
  eventType
  observedAt/reason/traceId
  optional diagnostics map, if needed
```

No worker-runtime projection should depend on endpointAddress. If diagnostics
need an address, keep it in a bounded diagnostics bag or transport-only trace
attribute, not as a main model field.

### Raw Manual Output

Raw/manual output must be explicitly classified:

```text
Option A: delete raw route output if no required caller remains
Option B: keep worker-command raw output as selected-worker final-hop
Option C: keep a true operator raw endpoint side-channel
```

Option B target:

```text
RawWorkerCommandChannel.sendToSelectedWorker(selectedWorkerId, rawJson, traceId)
```

Option C target names:

```text
RawEndpointMessageChannel.sendToEndpoint(rawEndpointAddress, rawJson, traceId)
RawEndpointRegistry.isEndpointActive(endpointDriverId, rawEndpointAddress)
TransportOutboundMessage(endpointAddress, rawJson, traceId)
```

Option B is still worker-id based and must not expose endpoint addresses to
`MassApplication`. Option C is an operator/diagnostic endpoint side-channel and
must stay out of assigned delivery and worker command mainlines.

## Current Inventory

| Surface | Current routeKey role | Target |
| --- | --- | --- |
| `DeliveryCommand`, `AdapterDispatchRequest`, `DispatchOutcome`, pulled DTOs | already forbidden / absent | keep absent and guard |
| `RouteEndpointIndex` | route-addressed session index | rename to endpoint/session index or replace with selected-worker plus endpointAddress indexes |
| WebSocket/socket session managers | connect address, raw route send, logs, endpoint lease address | rename to endpointAddress/session address; final-hop remains selected-worker |
| Polling `PullWorkerSession` | mints group-derived routeKey and sends it as presence/lease metadata | remove public route minting; use deliveryBucketId + workerId + session token, optionally internal endpointAddress |
| `CanonicalWorkerGroupRouteKeyCodec` | SDK/starter default route mint helper in transport API | delete or move to an adapter-local endpoint-address helper if still needed |
| `WorkerSessionPresenceEvent` | session presence metadata | remove routeKey field; diagnostics only if needed |
| `WorkerPresenceRuntime` / `InMemoryWorkerPresenceRuntime` | worker-runtime session presence currently stores routeKey | remove routeKey from session APIs and session records; do not rename it to endpointAddress |
| `TransportEndpointLeaseClaim/Heartbeat/Release` callers | currently pass routeKey into `endpointAddress`; generic lease contract also requires endpointAddress | narrow the contract so endpointAddress is optional unless adapter-local addressing requires it, then rename caller variables and stop treating endpointAddress as route |
| `TransportPacket` | legacy packet address field | remove routeKey and `withTransportAddress(...)` from current packet model |
| `InboundEnvelope` | adapter ingress correlation metadata | remove field or rename to endpointAddress diagnostics |
| `WorkerEndpointSnapshot` | diagnostics route field | rename to endpointAddress or remove from default diagnostics |
| `RawWorkerRouteEndpointRegistry` | route-only raw/manual output | delete or rename to raw endpoint side-channel |
| `TransportOutboundMessage` | output queue route address | delete or rename field to endpointAddress if raw channel remains |
| `MassApplication.sendRawTransportMessage(workerId, ...)` | worker-id raw command API currently reverse-resolves routeKey | if retained, make it selected-worker final-hop; do not expose endpointAddress to starter mainline |
| WebSocket/socket wire helpers | `routeKeyForWorkerGroup`, `ROUTE_KEY_FIELD`, and `extractRouteKey` preserve routeKey on managed wire | remove managed routeKey wire requirement; endpointAddress only if Phase 0 decided the protocol needs it |
| Server E2E helpers | append routeKey query param | replace with endpointAddress only if adapter wire still needs it; otherwise remove |
| Active docs/proof registry | routeKey described as opaque metadata | update after implementation; do not keep routeKey as current truth |

## Non-Goals

- Do not change scheduling, worker selection, dispatch binding, retry, or
  compensation.
- Do not rename `deliveryBucketId`, `selectedWorkerId`, or the bucket-derived
  queue model.
- Do not add transport-owned bucket shard or owner-partition policy. If
  buckets need to be split later, that belongs to the external bucket owner and
  must enter transport as a new bucket contract.
- Do not collapse endpoint driver, endpoint address, session handle, and endpoint
  lease id into one string.
- Do not implement remote adapter registration or adapter placement strategy.
- Do not implement async dispatcher behavior in this roadmap.
- Do not preserve routeKey compatibility aliases in in-repo callers.
- Do not remove all diagnostics; remove routeKey-shaped diagnostics or rename
  them to the owning endpoint/session fact.

## Do Not Start With

Do not start with a global rename of `routeKey` to `endpointAddress`.

That would keep the same conceptual leak under a better name. First classify
each usage:

1. assigned delivery: must delete
2. endpoint/session address: may become endpointAddress
3. raw/manual side-channel: delete or rename separately
4. diagnostics: bounded metadata only
5. tests/docs: update after the runtime owner has changed

## Phase 0 - Inventory And Guard Allowlist

Goal: make the routeKey removal blast radius explicit before touching runtime
behavior.

Scope:

- classify every production `routeKey` hit into assigned delivery, endpoint /
  session address, raw/manual output, diagnostics, wire protocol, SDK/starter
  default, or test fixture
- classify every test `routeKey` hit as behavior proof, fixture residue, or
  stale vocabulary
- decide whether raw/manual output is still a product feature; if yes, choose
  raw endpoint vocabulary before implementation
- classify raw/manual callers into worker-command-by-workerId versus true
  raw-endpoint operator send. `MassApplication.sendRawTransportMessage` must
  not become a starter-facing endpointAddress API.
- decide this before Phase 2 changes `RouteEndpointIndex`; selected-worker
  session indexing and raw/manual endpoint addressing currently share that
  route-shaped index
- decide whether adapter wire protocols still need an explicit endpoint address
  field; if yes, use `endpointAddress`, not `routeKey`
- explicitly classify and forbid managed-wire routeKey mint/extract helpers:
  `routeKeyForWorkerGroup`, `ROUTE_KEY_FIELD`, and `extractRouteKey`
- decide whether the generic endpoint lease API should allow no endpointAddress
  when `sessionHandle` / `endpointLeaseId` is sufficient. Do not let every
  adapter mint an address just to satisfy the old routeKey-shaped contract.
- add temporary architecture guards that keep assigned delivery route-key-free
  while the remaining routeKey migration proceeds

Acceptance:

- inventory table in this roadmap is corrected against current code
- assigned delivery guard forbids routeKey in command/request/outcome/queue /
  handoff/result mainline
- routeKey hits allowed before Phase 1 are explicitly limited to endpoint /
  session, raw/manual, packet/inbound legacy, diagnostics, docs, and tests
- raw/manual output has a recorded delete-or-rename decision before
  `RouteEndpointIndex` is changed
- worker-command raw output and true raw endpoint side-channel have separate
  target APIs if both remain
- managed-wire routeKey mint/extract helpers are classified as removal targets,
  not adapter-local allowed residue
- endpoint lease address semantics are recorded as either optional generic
  metadata or required adapter-local metadata with a named adapter mint owner
- no phase requires routeKey to remain as worker correctness, queue, lifecycle,
  or reachability truth

## Phase 1 - Public, Worker-Wire, And Assigned-Delivery Boundary Lock

Goal: prove routeKey is not part of worker-facing APIs, managed worker wire, or
assigned delivery before moving deeper adapter internals.

Scope:

- keep or add guards proving these surfaces do not expose routeKey:
  `DeliveryCommand`, `AdapterDispatchRequest`, `DispatchOutcome`,
  `PulledDeliveryMessage`, polling pull APIs, external worker registration,
  public Java SDK worker session APIs, server worker API DTOs, WebSocket worker
  query fields, socket hello fields, and worker-pack samples
- remove any remaining public worker-session routeKey getter, setter, request
  field, or builder option
- stop SDK/starter routeKey minting from being visible to worker callers
- stop managed worker wire from requiring `routeKey`; if a protocol still needs
  an adapter-local address, expose it as `endpointAddress` only after Phase 0
  decided it is needed
- remove managed-wire fallback minting and extraction symbols from the current
  worker mainline: `routeKeyForWorkerGroup`, `ROUTE_KEY_FIELD`,
  `extractRouteKey`, and socket hello `workerId/workerGroupId/routeKey`
  required-field checks
- update tests that prove routeKey absence on public worker contracts

Acceptance:

- public worker APIs use workerGroupId, workerId, transport mode, and session
  token style facts; deliveryBucketId remains engine/starter boundary input,
  not a worker-facing routeKey replacement
- WebSocket and socket managed worker connect paths do not require `routeKey`
  on query string, hello frame, or worker-pack sample code
- no managed worker path has a routeKey fallback mint rule based on
  workerGroupId
- no managed worker frame codec exposes `ROUTE_KEY_FIELD` or
  `extractRouteKey(...)` as a current mainline API
- assigned delivery producer and consumer paths have no routeKey imports,
  parameters, model fields, or guard exceptions
- `CanonicalWorkerGroupRouteKeyCodec` is not imported by transport runtime,
  adapters, server worker API, or public Java SDK worker sessions

## Phase 2 - Endpoint And Session Address Convergence

Goal: move genuine connection-address usage to endpoint/session vocabulary.

Scope:

- rename adapter/session manager route fields and methods to endpoint address
  vocabulary where the address is still needed
- change `RouteEndpointIndex` into an endpoint/session index or replace it with
  selected-worker lookup plus endpointAddress diagnostics
- update websocket and socket connect/hello parsing:
  - if explicit address remains, accept `endpointAddress`
  - if not needed, derive adapter-local address from session handle or accepted
    connection context
- update polling session assembly so it does not mint `routeKey`; use
  deliveryBucketId, workerId, and session token / endpoint lease id, with
  optional internal endpointAddress only inside transport
- narrow endpoint lease claim/heartbeat/release contracts so
  `endpointAddress` is not required unless a concrete adapter actually uses it
  for local addressing. Stale-session protection must come from
  `endpointLeaseId` / `sessionHandle`, not from a group-derived address.
- update endpoint lease claim/heartbeat/release call sites so variables and
  tests say endpointAddress, not routeKey
- remove routeKey from `WorkerSessionPresenceEvent`; if diagnostics still need
  the address, keep it in transport-only trace attributes or a bounded
  diagnostics map
- remove routeKey from `WorkerPresenceRuntime.sessionConnected`,
  `sessionHeartbeat`, and `sessionDisconnected`, and from
  `InMemoryWorkerPresenceRuntime.PresenceSessionRecord`
- update `WorkerRuntimePresenceIngress` so it forwards only worker/session
  evidence needed by worker-runtime presence; it must not synthesize
  endpointAddress or any route replacement
- update worker-runtime presence ingress tests so worker reachability projection
  does not depend on endpointAddress

Acceptance:

- no session manager method named `*Route*` remains for current assigned delivery
  or endpoint lease operations
- endpoint lease metadata/view keeps endpointAddress only as endpoint/session
  evidence; it is not used to derive bucket, worker, lifecycle, or queue
- endpoint lease correctness and stale-release protection do not depend on
  endpointAddress when the adapter can address by session handle
- `WorkerSessionPresenceEvent` no longer has a routeKey field
- `WorkerPresenceRuntime` session methods and
  `InMemoryWorkerPresenceRuntime.PresenceSessionRecord` no longer contain
  routeKey or endpointAddress
- worker-runtime presence ingress does not write a `"routeKey"` diagnostic key
  or project endpointAddress into lifecycle/reachability truth
- polling managed session code no longer imports
  `CanonicalWorkerGroupRouteKeyCodec`

## Phase 3 - Raw Manual Output Convergence

Goal: execute the Phase 0 raw/manual decision by removing route-addressed raw
output, converting worker-command raw output to selected-worker final-hop, or
renaming a true operator endpoint side-channel so it cannot be mistaken for
assigned delivery.

Scope:

- use the Phase 0 inventory of `TransportOutboundMessage`,
  `RawWorkerMessageChannel`, and `RawWorkerRouteEndpointRegistry`; do not defer
  the delete-vs-rename decision to this phase
- if no required product caller exists, delete the raw output queue/channel
  path and tests
- if the current worker-command caller remains, change it to selected-worker
  final-hop semantics. `MassApplication.sendRawTransportMessage(workerId, ...)`
  must not resolve `WorkerEndpointSnapshot.getRouteKey()` or any endpointAddress.
- if the raw path remains:
  - rename `RawWorkerRouteEndpointRegistry` to raw endpoint vocabulary
  - rename `sendToAdapterRoute` / `isAdapterRouteOnline`
  - rename `TransportOutboundMessage.routeKey` to endpointAddress or
    rawEndpointAddress
  - keep this endpoint-addressed path operator/diagnostic only; do not wire it
    behind `MassApplication.sendRawTransportMessage(workerId, ...)`
  - keep guards forbidding assigned task delivery from calling the raw endpoint
    channel

Acceptance:

- no current production interface or model contains `RawWorkerRoute` or
  `sendToAdapterRoute`
- retained worker-command raw output is addressed by selectedWorkerId /
  workerId final-hop only, not by endpointAddress or routeKey reverse lookup
- assigned delivery callers cannot access the raw endpoint side-channel through
  `WorkerEndpointRegistry`
- `MassApplication` does not expose or internally resolve endpointAddress for
  the worker-id raw command mainline
- raw/manual proof, if retained, is named as raw endpoint proof rather than
  route-key proof

## Phase 4 - Packet, Inbound, And Wire Cleanup

Goal: remove routeKey from transport packet and inbound frame models.

Scope:

- remove `TransportPacket.routeKey` and `withTransportAddress(...)`
- update `TransportPacketFactory` and packet codecs so worker system events /
  result ingress do not carry routeKey as packet address
- remove or rename `InboundEnvelope.routeKey`
- update websocket and socket frame codecs/tests to stop using `routeKey` as a
  current field; use endpointAddress only if Phase 0/Phase 2 decided the wire
  needs it
- update server E2E helpers that append routeKey query params
- delete `CanonicalWorkerGroupRouteKeyCodec` if no current non-test caller
  remains; if an adapter-local endpoint address helper is still needed, it must
  not live as a transport API route-key codec

Acceptance:

- no current packet or inbound envelope model field is named routeKey
- no current websocket/socket/polling managed worker path requires a routeKey
  wire/query field
- all remaining address fields are endpoint/session named and adapter-local
- no routeKey codec remains in transport API main sources

## Phase 5 - Docs, Proof, And Residue Scan

Goal: make the new simpler model the only current narrative.

Scope:

- update `transport/AGENTS.md`
- update `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- update `doc/PROOF_REGISTRY.md`
- update root `README.md` and `AGENTS.md` if they still describe routeKey as
  current transport metadata
- update or remove roadmap references that say routeKey is current truth; link
  archived historical context only when needed
- add architecture guards forbidding routeKey in current production models,
  current worker-facing APIs, assigned delivery, endpoint lease mainline, and
  raw endpoint current interfaces

Acceptance:

- active docs do not describe routeKey as current transport truth
- proof registry no longer uses routeKey to describe selected-worker delivery
  or endpoint lease proof
- routeKey residue scan has hits only in this roadmap, archived docs, or
  explicitly named historical tests being removed in the same slice
- this roadmap is archived after completion criteria are met

## Suggested Implementation Order

1. Finish or stabilize the node-id / delivery queue model work so routeKey
   removal does not mix with queue-model changes.
2. Run Phase 0 inventory and update the table above with exact current
   production/test classifications, raw/manual decision, worker-wire decision,
   and endpointAddress lease semantics.
3. Lock public worker APIs, managed worker wire, and assigned-delivery routeKey
   absence with guards.
4. Move endpoint/session usage to endpointAddress or remove it where session
   handle already suffices.
5. Execute the raw/manual output decision: delete, or rename to raw endpoint
   vocabulary.
6. Remove packet/inbound/wire routeKey fields.
7. Update docs, proof registry, guards, and run residue scan.

## Verification Candidates

Compile:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,xa-mass-worker-runtime,xa-mass-server -am -DskipTests test-compile
```

Focused tests:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,TransportEndpointLeaseStoreContractTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,WebSocketInputProcessorTest,WebSocketFrameReadersTest,DispatcherInboundHandlerTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest
./mvnw -q -pl xa-mass-worker-runtime -am test -Dtest=InMemoryWorkerPresenceRuntimeTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassSdkTest,PullWorkerSessionTest,WorkerRuntimePresenceIngressTest,MassApplicationDistributedTransportTest
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest
./mvnw -q -pl xa-mass-server -am test -Dtest=ExternalWorkerApiControllerTest,ExternalWorkerRealtimeRegistrationIntegrationTest,ExternalWorkerPollingApiIntegrationTest
```

Residue scans:

```powershell
rg -n "routeKey|RouteKey|route key|route-only|RawWorkerRouteEndpointRegistry|sendToAdapterRoute|isAdapterRouteOnline|CanonicalWorkerGroupRouteKeyCodec" transport sdk xa-mass-server xa-mass-base doc README.md AGENTS.md -g "*.java" -g "*.md"
rg -n "routeKey" transport/transport_api/src/main/java/com/xa/mass/transport/model transport/transport_api/src/main/java/com/xa/mass/transport/channel transport/transport_api/src/main/java/com/xa/mass/transport/lease -g "*.java"
rg -n "routeKey" sdk/xa-mass-java-sdk/src/main/java xa-mass-server/src/main/java -g "*.java"
```

Architecture guard candidates:

```text
transport API guard:
  current main model/channel/lease classes do not contain routeKey

assigned delivery guard:
  DeliveryCommand, AdapterDispatchRequest, DispatchOutcome, PulledDeliveryMessage,
  DeliveryCommandBatch, DeliveryQueueOffer, and result ingress models do not
  contain routeKey or route address fields

worker API guard:
  server worker DTOs and public Java SDK worker/session APIs do not contain routeKey

worker wire guard:
  websocket query/handshake, socket hello, worker-pack samples, and public Java
  SDK managed sessions do not require or emit routeKey
  forbidden current mainline symbols include routeKeyForWorkerGroup,
  ROUTE_KEY_FIELD, extractRouteKey, and workerId/workerGroupId/routeKey
  required-field messages

adapter guard:
  selected-worker task dispatch channels do not call raw endpoint output helpers

presence guard:
  WorkerSessionPresenceEvent and WorkerRuntimePresenceIngress do not expose
  routeKey as a model field or worker-runtime diagnostic key
  WorkerPresenceRuntime session APIs and InMemoryWorkerPresenceRuntime session
  records do not contain routeKey or endpointAddress

raw side-channel guard:
  if raw output remains, it is named raw endpoint output and is not visible
  through WorkerEndpointRegistry
  MassApplication worker-id raw command path does not call
  WorkerEndpointSnapshot.getRouteKey() or resolve endpointAddress
```

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- no current production model, channel, public API, or worker session surface
  contains a `routeKey` field, getter, setter, builder option, or parameter
- `CanonicalWorkerGroupRouteKeyCodec` is deleted from current main sources, or
  replaced by an adapter-local endpoint-address helper whose name does not
  contain route
- `RouteEndpointIndex` is deleted or renamed to endpoint/session vocabulary
- `RawWorkerRouteEndpointRegistry`, `sendToAdapterRoute`, and
  `isAdapterRouteOnline` are deleted or renamed to raw endpoint vocabulary
- `TransportPacket`, `InboundEnvelope`, `WorkerSessionPresenceEvent`,
  `WorkerEndpointSnapshot`, and `TransportOutboundMessage` do not expose routeKey
- `WorkerPresenceRuntime` session APIs and
  `InMemoryWorkerPresenceRuntime.PresenceSessionRecord` do not expose routeKey
  or endpointAddress
- websocket/socket/polling managed worker paths do not require routeKey on wire
  or in public worker APIs
- managed worker wire has no `routeKeyForWorkerGroup`, `ROUTE_KEY_FIELD`,
  `extractRouteKey`, or `workerId/workerGroupId/routeKey` required-field
  residue
- worker-id raw command output, if retained, uses selected-worker final-hop and
  does not reverse-resolve routeKey or endpointAddress through
  `WorkerEndpointSnapshot`
- assigned delivery remains `deliveryBucketId + selectedWorkerId + opaque
  payload/correlation`; no endpointAddress or raw endpoint side-channel leaks
  back into command facts
- endpoint/session address, if retained, is endpoint/session-local and not
  interpreted as worker correctness, queue, lifecycle, reachability, or
  scheduling truth
- generic endpoint lease correctness does not require a group-derived
  endpointAddress; adapters that need an endpointAddress own it as local
  endpoint metadata
- active owner docs and proof registry no longer describe routeKey as current
  transport truth
- architecture guards fail if routeKey is reintroduced outside archived docs or
  this roadmap
- residue scan shows only archived historical docs or this roadmap, and this
  roadmap is archived after owner docs are updated
