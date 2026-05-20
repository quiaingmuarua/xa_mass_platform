# Transport Agent Handoff

Last updated: 2026-05-11

Status: current transport owner handoff.

Read the repo-root [AGENTS.md](../AGENTS.md) first. This file is only the fast
entry for `transport/`.

## TL;DR

- `transport` is a product subsystem, not a WebSocket utility folder.
- `transport_api` owns transport-neutral contracts.
- `transport_runtime` owns shared runtime assembly and delivery semantics.
- `polling-adapter`, `websocket-adapter`, and `socket-adapter` are peer adapters.
- `adapterId` is concrete runtime truth. `transportHint` is only a coarse family.
- `routeKey` is the transport delivery address. Current mainline bindings resolve
  it from `workerId` by default, but adapter ingress may bind an explicit
  `routeKey` instead. That is policy, not a transport-global invariant.
- route-only endpoint helpers may exist inside one concrete adapter, but the
  transport-neutral runtime surface must route with `adapterId + routeKey`
  only. Composite registries must not guess ownership from route-only access.
- raw/debug worker side-channels are also adapter-scoped. They may resolve one
  concrete active route for a worker, but once resolved they must dispatch via
  the serving adapter identity instead of reviving route-only shared semantics.
- worker reachability truth now lives in a transport-owned presence plane.
  Adapters write `WorkerPresenceStore`; engine consumes a reachability view and
  must not re-own transport online truth through worker heartbeat folding.
  Presence ownership is connection-aware: reconnect may replace the current
  owner, while heartbeat/offline only apply when the caller still holds the
  stored `connectionId`.
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
- convergence roadmap: `ADAPTER_NODE_WORKER_REGISTRATION_ROADMAP.md`
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
4. [ADAPTER_NODE_WORKER_REGISTRATION_ROADMAP.md](./ADAPTER_NODE_WORKER_REGISTRATION_ROADMAP.md) when changing worker registration endpoint, adapter-node, or node/group relation design
5. [TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](./TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md) for future queue-first/high-volume direction
6. repo-root [../doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md) and [../doc/VERIFIED_RUNBOOK.md](../doc/VERIFIED_RUNBOOK.md) for repo truth and verification

## Fast Verification

Prefer these after transport changes:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,RuntimeTaskResultIngestChannelTest,TransportRoutingTaskMsgDispatchListenerTest
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingWorkerAdapterTest
./mvnw -q -pl xa-mass-sdk -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MassSdkTest
```

Acceptance focus:

- dispatch hits the correct adapter by `adapterId`
- polling `poll` and result submission work
- realtime direct-send and presence/online perception work
- result ingest remains transport-only and does not mutate engine lifecycle directly
