# Transport Route Key Removal Convergence Roadmap

Status: proposed direction document.

## Summary

`routeKey` used to be a convenient name while transport mixed connection
addressing, route-owner leases, raw route sends, and task dispatch delivery.
After the recent delivery convergence, assigned task delivery is no longer
route-key based:

```text
deliveryBucketId -> bucket queue / shard
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

## Relationship To Other Roadmaps

- `TRANSPORT_NODE_ID_REMOVAL_CONVERGENCE_ROADMAP.md` removes generic process
  node identity. This roadmap should run after, or at least not conflict with,
  that work because both touch endpoint/session managers and transport owner
  docs.
- `TRANSPORT_INTERNAL_ID_BOUNDARY_CONVERGENCE_ROADMAP.md` is broader internal id
  vocabulary work. This roadmap owns only the `routeKey` removal slice and
  should not pull in adapter-id, gateway-id, transport-hint, or queue-key
  renames unless required to delete routeKey safely.
- `TRANSPORT_DELIVERY_EXECUTOR_RESIDUE_CONVERGENCE_ROADMAP.md` already keeps
  assigned delivery free of routeKey. This roadmap removes the leftover session,
  packet, raw-route, and diagnostic vocabulary that still makes routeKey look
  alive.

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
- Active docs and proof registry still describe routeKey as opaque connection
  metadata and route-only raw helpers as allowed residue.

## Owner Review

- Engine owns scheduling, selected worker choice, dispatch binding, retry, and
  compensation. Engine must not receive, mint, or interpret routeKey.
- Worker runtime owns worker lifecycle, reachability projection, candidate
  evidence, admission, and worker-report projection. It may receive bounded
  session presence evidence, but routeKey must not become worker lifecycle or
  reachability truth.
- Transport owns protocol sessions, endpoint/session lease evidence, delivery
  mechanics, raw/manual output if retained, and result ingress. Transport may
  keep adapter-local endpoint addresses, but not a cross-layer routeKey concept.
- SDK/starter owns managed session assembly defaults. If an adapter needs an
  internal endpoint address, SDK/starter may create one inside assembly, but
  worker APIs should not expose a routeKey parameter or getter.
- Concrete adapters own wire parsing and adapter-local connection maps. A
  WebSocket query field or socket hello field may be renamed or removed there;
  it is not a platform delivery concept.

## Boundary Decision

Remove `routeKey` as a first-class current transport concept.

Allowed after convergence:

- adapter-local variable names only when they describe a temporary wire value
  being decoded during migration, and only inside the concrete adapter slice
- `endpointAddress` inside endpoint/session lease and adapter session managers,
  if the adapter genuinely needs a stable address distinct from
  `deliveryBucketId`, `selectedWorkerId`, and `sessionHandle`
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
  endpointAddress
  sessionHandle
  endpointLeaseId
```

`endpointAddress` is not a dispatch route. It is a driver-owned address or
correlation value. If a driver can address by session handle alone, it should
not invent endpointAddress just to replace routeKey.

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
Option A: delete raw route output if no product caller remains
Option B: rename to raw endpoint output
```

Option B target names:

```text
RawEndpointMessageChannel.sendToEndpoint(rawEndpointAddress, rawJson, traceId)
RawEndpointRegistry.isEndpointActive(endpointDriverId, rawEndpointAddress)
TransportOutboundMessage(endpointAddress, rawJson, traceId)
```

These names are still raw/manual side-channel vocabulary. Assigned delivery
must not call them.

## Current Inventory

| Surface | Current routeKey role | Target |
| --- | --- | --- |
| `DeliveryCommand`, `AdapterDispatchRequest`, `DispatchOutcome`, pulled DTOs | already forbidden / absent | keep absent and guard |
| `RouteEndpointIndex` | route-addressed session index | rename to endpoint/session index or replace with selected-worker plus endpointAddress indexes |
| WebSocket/socket session managers | connect address, raw route send, logs, endpoint lease address | rename to endpointAddress/session address; final-hop remains selected-worker |
| Polling `PullWorkerSession` | mints group-derived routeKey and sends it as presence/lease metadata | remove public route minting; use deliveryBucketId + workerId + session token, optionally internal endpointAddress |
| `CanonicalWorkerGroupRouteKeyCodec` | SDK/starter default route mint helper in transport API | delete or move to an adapter-local endpoint-address helper if still needed |
| `WorkerSessionPresenceEvent` | session presence metadata | remove routeKey field; diagnostics only if needed |
| `TransportEndpointLeaseClaim/Heartbeat/Release` callers | currently pass routeKey into `endpointAddress` | rename caller variables and stop treating endpointAddress as route |
| `TransportPacket` | legacy packet address field | remove routeKey and `withTransportAddress(...)` from current packet model |
| `InboundEnvelope` | adapter ingress correlation metadata | remove field or rename to endpointAddress diagnostics |
| `WorkerEndpointSnapshot` | diagnostics route field | rename to endpointAddress or remove from default diagnostics |
| `RawWorkerRouteEndpointRegistry` | route-only raw/manual output | delete or rename to raw endpoint side-channel |
| `TransportOutboundMessage` | output queue route address | delete or rename field to endpointAddress if raw channel remains |
| Server E2E helpers | append routeKey query param | replace with endpointAddress only if adapter wire still needs it; otherwise remove |
| Active docs/proof registry | routeKey described as opaque metadata | update after implementation; do not keep routeKey as current truth |

## Non-Goals

- Do not change scheduling, worker selection, dispatch binding, retry, or
  compensation.
- Do not rename `deliveryBucketId`, `selectedWorkerId`, or the bucket-derived
  queue model.
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
- decide whether adapter wire protocols still need an explicit endpoint address
  field; if yes, use `endpointAddress`, not `routeKey`
- add temporary architecture guards that keep assigned delivery route-key-free
  while the remaining routeKey migration proceeds

Acceptance:

- inventory table in this roadmap is corrected against current code
- assigned delivery guard forbids routeKey in command/request/outcome/queue /
  handoff/result mainline
- routeKey hits allowed before Phase 1 are explicitly limited to endpoint /
  session, raw/manual, packet/inbound legacy, diagnostics, docs, and tests
- no phase requires routeKey to remain as worker correctness, queue, lifecycle,
  or reachability truth

## Phase 1 - Public And Assigned-Delivery Boundary Lock

Goal: prove routeKey is not part of worker-facing APIs or assigned delivery
before moving adapter internals.

Scope:

- keep or add guards proving these surfaces do not expose routeKey:
  `DeliveryCommand`, `AdapterDispatchRequest`, `DispatchOutcome`,
  `PulledDeliveryMessage`, polling pull APIs, external worker registration,
  public Java SDK worker session APIs, and server worker API DTOs
- remove any remaining public worker-session routeKey getter, setter, request
  field, or builder option
- stop SDK/starter routeKey minting from being visible to worker callers
- update tests that prove routeKey absence on public worker contracts

Acceptance:

- public worker APIs use workerGroup/deliveryBucket, workerId, transport mode,
  and session token style facts, not routeKey
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
- update endpoint lease claim/heartbeat/release call sites so variables and
  tests say endpointAddress, not routeKey
- remove routeKey from `WorkerSessionPresenceEvent`; if diagnostics still need
  the address, keep it in transport-only trace attributes or a bounded
  diagnostics map
- update worker-runtime presence ingress tests so worker reachability projection
  does not depend on endpointAddress

Acceptance:

- no session manager method named `*Route*` remains for current assigned delivery
  or endpoint lease operations
- endpoint lease metadata/view keeps endpointAddress only as endpoint/session
  evidence; it is not used to derive bucket, worker, lifecycle, or queue
- `WorkerSessionPresenceEvent` no longer has a routeKey field
- polling managed session code no longer imports
  `CanonicalWorkerGroupRouteKeyCodec`

## Phase 3 - Raw Manual Output Decision

Goal: either remove route-addressed raw output or rename it so it cannot be
mistaken for assigned delivery.

Scope:

- inventory current users of `TransportOutboundMessage`, `RawWorkerMessageChannel`,
  and `RawWorkerRouteEndpointRegistry`
- if no required product caller exists, delete the raw output queue/channel
  path and tests
- if the raw path remains:
  - rename `RawWorkerRouteEndpointRegistry` to raw endpoint vocabulary
  - rename `sendToAdapterRoute` / `isAdapterRouteOnline`
  - rename `TransportOutboundMessage.routeKey` to endpointAddress or
    rawEndpointAddress
  - keep guards forbidding assigned task delivery from calling the raw endpoint
    channel

Acceptance:

- no current production interface or model contains `RawWorkerRoute` or
  `sendToAdapterRoute`
- assigned delivery callers cannot access the raw endpoint side-channel through
  `WorkerEndpointRegistry`
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
  current field; use endpointAddress only if Phase 2 decided the wire needs it
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

1. Finish or stabilize the node-id / bucket-queue work so routeKey removal does
   not mix with queue-model changes.
2. Run Phase 0 inventory and update the table above with exact current
   production/test classifications.
3. Lock public and assigned-delivery routeKey absence with guards.
4. Move endpoint/session usage to endpointAddress or remove it where session
   handle already suffices.
5. Decide raw/manual output: delete, or rename to raw endpoint vocabulary.
6. Remove packet/inbound/wire routeKey fields.
7. Update docs, proof registry, guards, and run residue scan.

## Verification Candidates

Compile:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,xa-mass-server -am -DskipTests test-compile
```

Focused tests:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,TransportEndpointLeaseStoreContractTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,WebSocketInputProcessorTest,WebSocketTransportFrameCodecTest,DispatcherInboundHandlerTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest,ServerSessionManagerShutdownTest,SocketSessionManagerTest
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

adapter guard:
  selected-worker task dispatch channels do not call raw endpoint output helpers

raw side-channel guard:
  if raw output remains, it is named raw endpoint output and is not visible
  through WorkerEndpointRegistry
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
- websocket/socket/polling managed worker paths do not require routeKey on wire
  or in public worker APIs
- assigned delivery remains `deliveryBucketId + selectedWorkerId + opaque
  payload/correlation`; no endpointAddress or raw endpoint side-channel leaks
  back into command facts
- endpoint/session address, if retained, is endpoint/session-local and not
  interpreted as worker correctness, queue, lifecycle, reachability, or
  scheduling truth
- active owner docs and proof registry no longer describe routeKey as current
  transport truth
- architecture guards fail if routeKey is reintroduced outside archived docs or
  this roadmap
- residue scan shows only archived historical docs or this roadmap, and this
  roadmap is archived after owner docs are updated
