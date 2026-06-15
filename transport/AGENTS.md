# Transport Agent Handoff

Last updated: 2026-06-15

Status: current transport owner handoff.

Read the repo-root [AGENTS.md](../AGENTS.md) first. This file is only the fast
entry for `transport/`.

## TL;DR

- `transport` is a product subsystem, not a WebSocket utility folder.
- `transport_api` owns transport-neutral contracts.
- `transport_runtime` owns shared runtime assembly and delivery semantics.
- `polling-adapter`, `websocket-adapter`, and `socket-adapter` are peer adapters.
- `adapterId` is concrete transport runtime truth, not an external worker API,
  SDK worker-session, or engine/starter delivery contract. `transportHint` is
  only a coarse family.
- `deliveryBucketId` is the engine/starter to transport assigned-delivery
  bucket. At the current boundary it is derived from worker-group context, but
  transport treats it as an opaque delivery-domain id, not as worker scheduling,
  capability, lifecycle, or adapter identity.
- `routeKey` is opaque connection/domain metadata. Transport runtime and
  adapters must not know whether it was minted from worker group, adapter lane,
  or another owner-level rule, and must not depend on routeKey cardinality for
  wrong-worker prevention.
- `selectedWorkerId` is the engine-selected execution target carried into
  transport as a delivery constraint. Transport may filter sessions or selected
  worker sub-lanes with it, but must not use it to schedule, rank, admit,
  mutate lifecycle, or mint route keys.
- `deliveryQueueKey` is only a queue/storage/batching partition. It may be
  shared by many workers; it must not express worker selection.
- external worker/session APIs must not expose route-owner internals such as
  `routeKey`, `connectionId`, `transportNodeId`, `deliveryQueueKey`, or
  adapter runtime ids. Managed workers declare `adapterNodeId` plus
  `transportHint`; transport resolves internal adapter/runtime evidence.
- Polling task delivery is selected-worker delivery: the poll request carries
  the registered worker id as `selectedWorkerId`, while the runtime resolves a
  shared `deliveryQueueKey` such as adapter id internally. Two polling workers
  may share one routeKey and one deliveryQueueKey; they must still drain only
  their own selected-worker sub-lane.
- `CanonicalWorkerGroupRouteKeyCodec` is the current SDK/starter default
  worker-consumption route mint rule from `workerGroupId`; transport runtime
  and adapters receive explicit route keys as opaque metadata instead of
  importing or resolving that rule.
- route-only endpoint helpers may exist inside one concrete adapter for
  raw/manual side channels. Task dispatch must use selected-worker addressing:
  producer feasibility lookup goes through `deliveryBucketId +
  selectedWorkerId`, and push adapters dispatch through
  `sendToSelectedWorker(...)` rather than a route-only fallback.
- `WorkerEndpointRegistry` is selected-worker only. Raw/manual route delivery
  uses `RawWorkerRouteEndpointRegistry` or `RawWorkerMessageChannel`, not the
  assigned-task endpoint interface.
- raw/debug worker side-channels are also adapter-scoped. They may resolve one
  concrete active route for a worker, but once resolved they must dispatch via
  the serving adapter identity instead of reviving route-only shared semantics.
- worker route-owner heartbeat evidence now lives in a transport-owned
  route-owner plane. Adapters write `TransportRouteOwnerStore`; engine consumes
  `WorkerDispatchRouteOwnerView` and must not re-own transport route evidence
  through worker heartbeat folding. Route-owner writes are connection-aware:
  each claim carries an explicit `deliveryBucketId`, a new claim replaces the
  current consumer for `(deliveryBucketId, selectedWorkerId)`, heartbeat only
  extends the matching owner lease, and release only removes the owner when the
  caller still holds the stored `connectionId` / public `sessionToken`.
- `WorkerPresenceIngress` is current session-presence ingress only. Adapters may
  publish connect/heartbeat/disconnect observations, while worker-runtime owns
  derived reachability and registry slot heartbeat freshness. Route-owner leases
  remain delivery feasibility evidence and must not become worker lifecycle
  truth, worker state-report truth, slot heartbeat truth, or capability truth.
- `DeliveryCommand` is the assigned-item delivery intent. It carries only
  `deliveryBucketId`, `selectedWorkerId`, minimal task dispatch content, typed
  attempt context, and item timing/id facts. Task shell metadata such as
  `taskName`, `project`, and `userId`, plus adapter, queue, node, route-owner,
  connection, and session facts are not command fields.
- `DeliveryCommandBatch` owns the process-boundary lane:
  `deliveryBucketId`, `deliveryLaneKey`, `targetTransportNodeId`, and command
  items only. It carries a node hint, not adapterId, routeKey, connectionId, or
  endpoint leases.
- `TransportDeliveryCommandListener` re-resolves endpoint evidence on the
  target transport node before calling an adapter. `AdapterDispatchRequest` is
  the final-hop adapter request.
- `PulledTaskDispatch` is the polling worker pull DTO. Task-dispatch
  `TransportPacket` is a final-hop/wire projection assembled after endpoint
  evidence is known. Neither is the delivery-command handoff payload.
- `QueuedPulledDispatch` is the polling queue value. It carries only the typed
  pull DTO source facts and selected worker sub-lane identity; packet, route,
  endpoint, taskName/project/userId, and deliveryQueueKey are not serialized in
  the Redis queue value.
- Queue mechanics may live under `platform_infra`; transport still owns
  `DeliveryCommand`, `DeliveryCommandBatch`, `AdapterDispatchRequest`,
  `QueuedPulledDispatch`, `TransportDeliveryStore`, and `DispatchOutcome`.
  `DispatchOutcome` is the single delivery-failure fact owner; failure inbox
  events wrap the outcome instead of maintaining group/item snapshot copies.
  `DispatchOutcome` reports only stable delivery identity, selected worker,
  attempt/task/message identity, status, retryability, reason, and time; it
  must not expose adapter, lane, route, node, connection, or endpoint evidence.
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
- convergence roadmaps: `TRANSPORT_WORKER_MATCH_SPINE_ROADMAP.md`
- historical inventory only: `refactor/*`

## Module Map

- `transport/transport_api`
  - artifact: `xa-mass-transport-api`
  - owns transport-neutral contracts only
- `transport/transport_runtime`
  - artifact: `xa-mass-transport-runtime`
  - owns shared runtime assembly
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
- Do not turn route-owner/presence evidence into a post-assignment routing
  engine. Missing selected-worker delivery evidence is infeasible delivery,
  not permission for transport to choose another worker.

## Reading Map

Use this order for transport changes:

1. local code under the touched module
2. [TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md)
3. [WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](./WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) when changing WebSocket adapter behavior
4. [TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md) when changing worker registration endpoint, adapter-node, or node/group relation design
5. [TRANSPORT_WORKER_MATCH_SPINE_ROADMAP.md](./TRANSPORT_WORKER_MATCH_SPINE_ROADMAP.md) when changing external-worker registration, group-first dispatch evidence, worker report feedback, or transport worker proof
6. [TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](./TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md) for future queue-first/high-volume direction
7. repo-root [../doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md) and [../xa-mass-testing/VERIFIED_RUNBOOK.md](../xa-mass-testing/VERIFIED_RUNBOOK.md) for repo truth and verification

## Fast Verification

Prefer these after transport changes:

```bash
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,InMemoryTransportDeliveryCommandHandoffTest,TransportDeliveryCommandBatchCodecTest,RedisTransportDeliveryCommandHandoffTest,RedisTransportDeliveryFailureChannelTest,InMemoryTransportRouteOwnerStoreTest,RedisTransportRouteOwnerStoreTest,RouteEndpointIndexTest,TransportConvergenceArchitectureGuardTest
./mvnw -q -pl transport/transport_api,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter test -Dtest=CanonicalWorkerGroupRouteKeyCodecTest,WebSocketInputProcessorTest,DispatcherInboundHandlerTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest,ServerSessionManagerShutdownTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassSdkTest,MassApplicationDistributedTransportTest,RuntimeTaskResultIngestChannelTest -Dsurefire.failIfNoSpecifiedTests=false
```

Acceptance focus:

- dispatch hits the correct internal adapter after transport resolution
- task dispatch preserves the engine-selected worker through
  `selectedWorkerId` and cannot fallback to route-only delivery
- polling `poll` and result submission work
- realtime direct-send, route-owner delivery feasibility, and worker-runtime
  presence/reachability projection work
- result lifecycle validation remains outside transport runtime; starter/engine
  assembly applies result correlation before engine mutation
