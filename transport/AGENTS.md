# Transport Agent Handoff

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
  it from `workerId`, but that is policy, not a transport-global invariant.
- Queue mechanics may live under `platform_infra`; transport still owns
  `TransportDispatchEnvelope`, `TransportDeliveryStore`, and `DispatchOutcome`.

Canonical transport concepts live in
[TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md). Use that as
the transport truth document.

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
4. [TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](./TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md) for future queue-first/high-volume direction
5. repo-root [../doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md) and [../doc/VERIFIED_RUNBOOK.md](../doc/VERIFIED_RUNBOOK.md) for repo truth and verification

Use [refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md](./refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md)
only when auditing old WebSocket refactor context. It is not current truth.

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
- realtime direct-send and endpoint online/offline perception work
- result ingest remains transport-only and does not mutate engine lifecycle directly
