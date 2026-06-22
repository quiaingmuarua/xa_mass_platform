# Transport Agent Handoff

Last updated: 2026-06-18

Status: current transport owner handoff.

Read the repo-root [AGENTS.md](../AGENTS.md) first. This file is only the fast
entry for `transport/`.

## TL;DR

- `transport` is a product subsystem, not a WebSocket utility folder.
- `transport_api` owns transport-neutral contracts.
- `transport_runtime` owns shared runtime assembly, embedded Java adapter
  support, and delivery semantics.
- `polling-adapter`, `websocket-adapter`, and `socket-adapter` are peer adapters.
- Transport core and adapter are different layers. Transport core owns minimal
  assigned-delivery commands, delivery queues/stores, endpoint lease evidence,
  result ingress, and delivery outcomes. A concrete adapter owns protocol
  server/client I/O, local session indexes, protocol frames, and final-hop send
  attempts for already selected workers.
- Current adapters are embedded Java modules, but the transport boundary should
  stay compatible with future remote adapter processes. Java-only object wiring
  belongs to embedded adapter support; cross-process facts must be typed
  delivery commands, endpoint lease evidence, routing result-ingress envelopes,
  delivery outcomes, diagnostics, or lifecycle observations.
- `transport` is a pure delivery executor. It owns correct assigned-worker
  delivery, queue claim/ack consistency, endpoint/session evidence, and delivery
  outcomes; it does not own retry, reassign, compensation, worker lifecycle,
  worker scheduling, or task payload schema.
- `WorkerGroup` owns event capability and the scheduling entry boundary.
  `Worker` owns execution identity plus scheduling evidence. Adapters cannot
  expand either capability or worker universe; they only expose final-hop
  connectivity evidence.
- Transport cannot choose workers. Assigned delivery may use
  `selectedWorkerId` only as the already chosen delivery constraint and may use
  endpoint lease evidence only to decide final-hop feasibility for that worker.
- Engine must not select by raw transport identifiers such as `adapterId`,
  `routeKey`, `connectionId`, endpoint lease ids, session handles, or
  transport queue names. Any delivery reachability needed by scheduling must be
  projected through worker-runtime evidence first.
- `adapterId` is concrete transport runtime truth, not an external worker API,
  SDK worker-session, engine/starter delivery contract, or worker-selection
  input. It identifies a concrete adapter binding only after transport has
  endpoint evidence for the already selected worker. `transportHint` is only a
  coarse family.
- `deliveryBucketId` is upstream scheduling/index context. It is not the
  physical dispatch queue owner and transport must not derive adapter mailbox
  routing from it.
- `routeKey` is opaque connection/domain metadata. Transport runtime and
  adapters must not know whether it was minted from worker group, adapter lane,
  or another owner-level rule, and must not depend on routeKey cardinality for
  wrong-worker prevention.
- `selectedWorkerId` is the engine-selected execution target carried into
  transport as a delivery constraint. Delivery-command handoff keeps it as a
  command field for dispatcher demux and final-hop endpoint/session lookup; it
  must not become a second physical queue address, scheduling input, lifecycle
  mutation, or route-key minting rule.
- Assigned-delivery physical queueing is addressed by opaque
  `adapterMailboxKey`. Worker-runtime delivery target evidence resolves an
  already selected worker to that mailbox before transport handoff. The mailbox
  may serve many workers; it must not express worker selection.
- external worker execution/session APIs must not expose endpoint/session internals such as
  `routeKey`, `connectionId`, `deliveryQueueKey`, endpoint lease ids, or
  adapter runtime ids. `adapterNodeId`, where still present, belongs to
  worker/resource control-plane declaration surfaces, not transport delivery
  executor identity.
- Polling task delivery is selected-worker delivery: the poll request carries
  the registered worker id as `selectedWorkerId`. Two polling workers may
  share one routeKey and one adapter mailbox; they must still receive only
  commands whose command-level `selectedWorkerId` matches the polling worker.
- `CanonicalWorkerGroupRouteKeyCodec` is the current SDK/starter default
  worker-consumption route mint rule from `workerGroupId`; transport runtime
  and adapters receive explicit route keys as opaque metadata instead of
  importing or resolving that rule.
- route-only endpoint helpers may exist inside one concrete adapter for
  raw/manual side channels. Task dispatch must use selected-worker addressing:
  producer-side delivery integration consumes worker-runtime mailbox evidence,
  handoff readiness is finite mailbox-level consumer lease evidence, and push
  adapters dispatch by concrete adapter command executors using a worker-id-only
  local session lookup rather than a route-only fallback.
- `WorkerEndpointRegistry` has been removed. Do not reintroduce a generic
  assigned-delivery endpoint interface; raw/manual route delivery uses
  `RawWorkerRouteEndpointRegistry` or `RawWorkerMessageChannel`, not the
  assigned-task command path.
- raw/debug worker side-channels are also adapter-scoped. They may resolve one
  concrete active route for a worker, but once resolved they must dispatch via
  the serving adapter identity instead of reviving route-only shared semantics.
- worker endpoint lease evidence lives in a transport-owned endpoint lease
  plane keyed by `deliveryBucketId + workerId`. Handoff consumer availability is
  mailbox-level evidence, not `deliveryBucketId + selectedWorkerId` evidence.
  Assigned-delivery producers must not re-own transport endpoint evidence
  through worker heartbeat folding or endpoint lease lookup; delivery
  integration consumes worker-runtime mailbox evidence, while adapters use
  local selected-worker session lookup for the final hop.
  Endpoint lease writes are connection-aware: each claim carries an explicit
  `deliveryBucketId`, heartbeat only extends the matching endpoint lease, and
  release only removes the endpoint lease when the caller still holds the
  stored `endpointLeaseId` / public `sessionToken`.
- `WorkerPresenceIngress` is current session-presence ingress only. Adapters may
  publish connect/heartbeat/disconnect observations, while worker-runtime owns
  derived reachability and registry slot heartbeat freshness. Endpoint leases
  remain delivery feasibility evidence and must not become worker lifecycle
  truth, worker state-report truth, slot heartbeat truth, or capability truth.
- `DeliveryCommand` is the assigned-item delivery intent. It carries only
  `deliveryBucketId`, `selectedWorkerId`, an opaque worker payload, opaque
  delivery correlation, and item timing/id facts. Task shell metadata such as
  `taskName`, `project`, and `userId`, plus adapter, queue, node, endpoint,
  connection, and session facts are not command fields.
- Transport core changes must pass the embedded-adapter independence pressure
  test: if a fact cannot be stably provided by a future cross-language adapter,
  it is not a transport-core contract; if it is only needed by embedded Java
  runtime assembly, it belongs to the embedded adapter layer; if it must cross a
  process boundary, it must be typed queue, evidence, outcome, or result-ingress
  data rather than Java object wiring.
- Concrete embedded Java adapters expose assigned delivery through runtime
  embedded-support `AdapterCommandExecutor.dispatch(List<DeliveryCommand>)`.
  Adapter metadata, server lifecycle, raw/manual channels, diagnostics, and
  pull channels are explicit binding/contribution facts, not executor facts.
- `AdapterCommandExecutor` is the embedded Java final-hop SPI, not the
  transport-neutral remote adapter contract. Do not make transport core depend
  on executor-local connection/session classes, late-bound handler setters, or
  adapter-owned registries.
- `EmbeddedAdapterRuntimeSet` and `EmbeddedAdapterContributionRuntime` own
  embedded Java adapter lifecycle around contribution-owned managed resources
  and servers. `AdapterMailboxLeaseRuntime` owns only mailbox consumer claim,
  refresh, and release for one command-delivery binding. `MassApplication`
  assembles and starts/stops these runtime units; concrete adapter bootstraps
  must not receive or call `AdapterMailboxConsumerRegistry`.
- Concrete adapters may observe protocol sessions and send to selected workers,
  but endpoint lease evidence is projected by `TransportEndpointLeasePublisher`,
  mailbox consumer evidence is claimed by embedded adapter runtime lifecycle,
  and worker session-presence observations are projected by
  `WorkerPresenceSessionPublisher`.
  WebSocket now splits this into explicit session store, server handle, command
  executor, evidence driver, and refresh-loop roles. Assigned delivery lookup
  inside the adapter is worker-id-only; `deliveryBucketId` remains upstream
  scheduling/index context.
  Socket uses the same adapter-local final-hop rule through its session manager;
  there is no generic selected-worker endpoint-registry wrapper on the assigned
  delivery path.
- `DeliveryCommandBatch` is consumer-local handoff materialization:
  `adapterMailboxKey`, handoff-owned command references, and command items
  only. It does not carry bucket, lane, target node, adapter route, connection,
  or endpoint lease facts.
- Runtime embedded-support `TransportDeliveryCommandListener` consumes
  adapter-mailbox handoff references, resolves the local adapter binding by
  mailbox, and invokes the final-hop adapter SPI. Core handoff pumping depends
  only on the narrow batch-listener callback; the final-hop adapter SPI consumes
  `DeliveryCommand` directly and sends by selected worker.
- `DeliveryPullResult` / `PulledDeliveryMessage` are the transport-core pull
  shapes. They carry status plus opaque delivery messages only. Task-shaped
  worker invocation/poll result DTOs live at the SDK/server public worker
  boundary, where the SDK-owned payload and correlation codecs decode them.
- `QueuedPulledDispatch` is the current polling queue value. It carries only
  delivery id, selected worker, payload, correlation, and timing; packet, route,
  endpoint, taskName/project/userId, and deliveryQueueKey are not serialized in
  the Redis queue value.
- Queue mechanics may live under `platform_infra`; transport still owns
  `DeliveryCommand`, `DeliveryCommandBatch`, `QueuedPulledDispatch`,
  `TransportDeliveryStore`, and `DispatchOutcome`.
  `DispatchOutcome` is the single delivery-failure fact owner; failure inbox
  events wrap the outcome instead of maintaining group/item snapshot copies.
  `DispatchOutcome` reports only delivery identity, selected worker, opaque
  correlation, status, retryability, reason, and time. It must not expose
  adapter, lane, route, node, connection, endpoint evidence, or task-shaped
  message/attempt fields.
- Embedded runtime composition currently defaults to the in-memory delivery
  store, but SDK/starter wiring may swap in a Redis-backed
  `TransportDeliveryStore` without changing transport-facing contracts.

Canonical transport concepts live in
[TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md). Use that as
the transport truth document.

Document layering inside `transport/`:

- current truth: `AGENTS.md`, `TRANSPORT_BOUNDARY_BASELINE.md`,
  `WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md`
- design/reference only: `TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md`
- historical inventory only: `refactor/*`

## Module Map

- `transport/transport_api`
  - artifact: `xa-mass-transport-api`
  - owns transport-neutral contracts only
- `transport/transport_runtime`
  - artifact: `xa-mass-transport-runtime`
  - owns shared runtime assembly and embedded Java adapter support
- `transport/polling-adapter`
  - artifact: `xa-mass-transport-polling`
  - owns polling/pull worker behavior
- `transport/websocket-adapter`
  - artifact: `xa-mass-transport-websocket`
  - owns WebSocket protocol/session/frame/dispatch behavior only
- `transport/socket-adapter`
  - artifact: `xa-mass-transport-socket`
  - owns socket protocol/session/frame/dispatch behavior only

## Hard Rules

- Do not redefine a worker as a WebSocket client. A worker is an executor
  reachable through some transport.
- Do not make `websocket-adapter` the hidden mainline for new transport work.
- Do not push adapter-specific frame/session/protocol types into `transport_api`.
- Do not push runtime-only queue/store state into `transport_api`.
- Do not preserve old runtime/package seams as compatibility wrappers.
- Manual/raw/control messaging is a side-channel. It must not mutate task
  lifecycle state directly.
- Do not add transport hot-path scans or model-coupled observability fields
  when logs, traces, counters, or indexed lookups can answer the question.
- Do not reintroduce `TaskDispatchContent`, `TaskDispatchExecutionContext`,
  task-shaped pull result DTOs, or task-shaped worker invocation DTOs into
  `transport_api`.
- Do not turn endpoint lease/presence evidence into a post-assignment routing
  engine. Missing selected-worker delivery evidence is infeasible delivery,
  not permission for transport to choose another worker.

## Reading Map

Use this order for transport changes:

1. local code under the touched module
2. [TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md)
3. [WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](./WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) when changing WebSocket adapter behavior
4. [TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md) when changing worker registration endpoint, adapter-node, or node/group relation design
5. [TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](./TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md) for future queue-first/high-volume direction
6. repo-root [../doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md) and [../xa-mass-testing/VERIFIED_RUNBOOK.md](../xa-mass-testing/VERIFIED_RUNBOOK.md) for repo truth and verification

## Fast Verification

Prefer these after transport changes:

```bash
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,InMemoryTransportDeliveryCommandHandoffTest,TransportDeliveryCommandBatchCodecTest,RedisTransportDeliveryCommandHandoffTest,RedisTransportDeliveryFailureChannelTest,BufferedTransportResultIngressChannelTest,RedisTransportResultIngressChannelTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest,RouteEndpointIndexTest,TransportConvergenceArchitectureGuardTest
./mvnw -q -pl transport/transport_api,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter test -Dtest=CanonicalWorkerGroupRouteKeyCodecTest,RoutingEnvelopeTest,WebSocketInputProcessorTest,WebSocketFrameReadersTest,DispatcherInboundHandlerTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest,WebSocketSessionControllerTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassSdkTest,MassApplicationDistributedTransportTest,RuntimeTaskResultIngestChannelTest,EmbeddedPullWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Acceptance focus:

- dispatch hits the correct internal adapter after transport resolution
- task dispatch preserves the engine-selected worker through
  `selectedWorkerId` and cannot fallback to route-only delivery
- polling `poll` and result submission work
- realtime push final-hop, endpoint lease delivery feasibility, and worker-runtime
  presence/reachability projection work
- result lifecycle validation remains outside transport runtime; starter/engine
  assembly applies result correlation before engine mutation
