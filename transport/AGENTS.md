# Transport Agent Handoff

Last updated: 2026-06-11

Status: current transport owner handoff.

Read the repo-root [AGENTS.md](../AGENTS.md) first. This file is only the fast
entry for `transport/`.

## TL;DR

- `transport` is a product subsystem, not a WebSocket utility folder.
- `transport_api` owns transport-neutral contracts.
- `transport_runtime` owns shared runtime assembly and delivery semantics.
- `polling-adapter`, `websocket-adapter`, and `socket-adapter` are peer adapters.
- `adapterId` is concrete runtime truth. `transportHint` is only a coarse family.
- `routeKey` is the transport delivery address. Transport runtime and adapters
  treat it as opaque and must not know whether it was minted from worker,
  worker-group, or another owner-level rule.
- `CanonicalWorkerGroupRouteKeyCodec` is the current SDK/starter default
  worker-consumption route mint rule from `workerGroupId`; transport runtime
  and adapters receive explicit route keys or injected resolvers instead of
  importing that rule.
- route-only endpoint helpers may exist inside one concrete adapter, but shared
  runtime delivery must first resolve the current route owner for `routeKey`
  and then dispatch through the owner value's `adapterId + routeKey`.
  Composite registries must not guess ownership from route-only access.
- raw/debug worker side-channels are also adapter-scoped. They may resolve one
  concrete active route for a worker, but once resolved they must dispatch via
  the serving adapter identity instead of reviving route-only shared semantics.
- worker route-owner heartbeat evidence now lives in a transport-owned
  route-owner plane. Adapters write `TransportRouteOwnerStore`; engine consumes
  `WorkerDispatchRouteOwnerView` and must not re-own transport route evidence
  through worker heartbeat folding. Route-owner writes are connection-aware:
  reconnect may replace the current owner, heartbeat only extends the matching
  owner lease, and release only removes the owner when the caller still holds
  the stored `connectionId` / public `sessionToken`.
- `WorkerSystemEventChannel` is current worker presence ingress only. It is not
  the lifecycle owner for future worker command, worker state-report, or
  capability self-report flows.
- `TransportPacket` is the internal flat transport envelope. Dispatch now
  creates packet-backed envelopes before adapter delivery, but worker-facing
  websocket/socket/polling JSON remains unchanged in this phase.
- Queue mechanics may live under `platform_infra`; transport still owns
  `TransportDispatchEnvelope`, `TransportDeliveryStore`, and `DispatchOutcome`.
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
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,RouteTargetedTaskDispatchSubmitterTest,RouteTargetedTaskDispatchHandoffPumpTest,InMemoryRouteTargetedTaskDispatchHandoffTest
./mvnw -q -pl transport/transport_api,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter test -Dtest=CanonicalWorkerGroupRouteKeyCodecTest,WebSocketInputProcessorTest,DispatcherInboundHandlerTest,SocketTransportServerTest,SocketTransportFrameCodecTest,PollingWorkerAdapterTest,SocketSessionManagerTest,ServerSessionManagerShutdownTest
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test -Dtest=MassSdkTest,MassApplicationDistributedTransportTest -Dsurefire.failIfNoSpecifiedTests=false
```

Acceptance focus:

- dispatch hits the correct adapter by `adapterId`
- polling `poll` and result submission work
- realtime direct-send and route-owner reachability perception work
- result ingest remains transport-only and does not mutate engine lifecycle directly
