# Transport Agent Handoff

This file is the local handoff for `transport/`. Read the repo-root [AGENTS.md](../AGENTS.md) first. Use this file to avoid transport-specific misreads.

## 0. TL;DR

- `transport` is a product subsystem, not a WebSocket utility folder.
- Transport owns worker connectivity, task dispatch delivery, result ingest wiring, and system-event ingress/egress.
- `transport_api` is the transport-neutral contract layer.
- `transport_runtime` is the shared runtime assembly layer.
- `polling-adapter`, `websocket-adapter`, and `socket-adapter` are peer adapters.
- `adapterId` is runtime truth. `transportHint` is only a coarse transport family hint.
- `com.xa.mass.sdk.transport.*` is SDK composition, not transport module internals.
- Stable transport concepts are limited to dispatch channel/outcome, runtime delivery,
  result ingest, and result envelope. See [../doc/TRANSPORT_BOUNDARY_BASELINE.md](../doc/TRANSPORT_BOUNDARY_BASELINE.md).

## 1. Module Map

- `transport/transport_api`
  - Maven artifact: `xa-mass-transport-api`
  - Java packages: `com.xa.mass.transport.*`
  - Owns transport-neutral contracts only: dispatch/result/system-event seams, endpoint registry, transport server contracts, adapter-facing transport models
- `transport/transport_runtime`
  - Maven artifact: `xa-mass-transport-runtime`
  - Java packages: `com.xa.mass.transport.runtime.*`
  - Owns shared runtime assembly: adapter binding, routing registry, registration resolution, delivery service/store, result ingest wiring, result-envelope validation, raw/control side-channel resolution
- `transport/polling-adapter`
  - Maven artifact: `xa-mass-transport-polling`
  - Java packages: `com.xa.mass.transport.polling.*`
  - Owns polling/pull worker transport behavior
- `transport/websocket-adapter`
  - Maven artifact: `xa-mass-transport-websocket`
  - Java packages: `com.xa.mass.transport.websocket.*`
  - Owns WebSocket server/session/frame/dispatch behavior only
- `transport/socket-adapter`
  - Maven artifact: `xa-mass-transport-socket`
  - Java packages: `com.xa.mass.transport.socket.*`
  - Owns socket server/session/frame/dispatch behavior only

## 2. Naming Truth

- Worker runtime identity:
  - `adapterId` = concrete adapter truth such as `polling`, `websocket`, `socket`
  - `transportHint` = coarse family such as `polling` or `realtime`
- Routing and side-channel resolution must key off `adapterId`, not `transportHint`
- Family-level matching is only for filtering, capability grouping, and compatibility defaults
- Do not move transport-owned runtime classes back under `com.xa.mass.starter.*`
- Do not treat SDK composition classes under `com.xa.mass.sdk.transport.*` as adapter/runtime ownership targets

## 3. Hard Rules

- Do not redefine a worker as a WebSocket client. A worker is an executor reachable through some transport.
- Do not make `websocket-adapter` the hidden mainline for new transport work.
- Do not push adapter-specific frame/codec types into `transport_api`.
- Do not push runtime-only queue/store state into `transport_api`.
- Do not add compatibility wrappers that preserve old runtime/package paths as a second mainline.
- Do not route new socket-style behavior through WebSocket-only abstractions unless the code is explicitly protocol-agnostic.
- Manual/raw/control messaging is a side-channel. It must not mutate task lifecycle state directly.
- Do not add JavaBean getters for internal dispatch metadata such as attempt identity unless the worker wire contract is intentionally changed.
- Do not enforce `leaseToken` until token generation, storage, expiry, retry behavior, compatibility, and rejection semantics are explicitly designed.

## 4. Boundary Freeze

Use these ownership rules before adding transport abstractions:

- `engine` owns worker matching, worker-context locks, `TaskMsgAttempt`, retry, release, and terminal lifecycle.
- `transport_runtime` owns delivery queue/store/drain, adapter routing, dispatch outcome logging, and result-envelope validation.
- concrete adapters own protocol I/O, endpoint/session state, frame/request codecs, and online/offline perception.
- worker wire payloads must not carry internal runtime metadata by accident.
- `TaskDispatchItem` is currently a dispatch payload plus internal metadata hybrid; do not split it until the split is planned across adapter codecs and worker API tests.
- `TransportResultEnvelope` wraps `TaskResultReport` with runtime metadata; it is not a second worker result protocol.

Prefer extending one of the stable concepts below over adding a new model:

- `TaskDispatchChannel`
- `DispatchOutcome`
- `TransportDelivery`
- `TaskResultIngestChannel`
- `TransportResultEnvelope`

## 5. Change Guide

Use this reading order for transport work:

1. local code under the touched transport module
2. [../doc/TRANSPORT_BOUNDARY_BASELINE.md](../doc/TRANSPORT_BOUNDARY_BASELINE.md) for transport model ownership and stable concept questions
3. [../doc/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../doc/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) for adapter boundary questions
4. [../doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md) for current repo/module truth
5. [../doc/VERIFIED_RUNBOOK.md](../doc/VERIFIED_RUNBOOK.md) and [../doc/INTEGRATION_TESTS.md](../doc/INTEGRATION_TESTS.md) for verification surfaces

When adding or changing an adapter:

- depend on `transport_api` and `transport_runtime`, not another concrete adapter
- expose a concrete `adapterId()` and a coarse `transportHint()`
- plug into shared dispatch/result/system-event/runtime channels instead of inventing a parallel path
- keep server/bootstrap/session/frame concerns inside the adapter module
- keep task lifecycle, assignment, and business event semantics out of the adapter

## 6. Fast Verification

Prefer these checks after transport changes:

```bash
./mvnw -q test-compile
./mvnw -q -pl transport/transport_runtime -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,RuntimeTaskResultIngestChannelTest,TransportRoutingTaskMsgDispatchListenerTest
./mvnw -q -pl xa-mass-sdk -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MassSdkTest
./mvnw -q -pl xa-mass-dev-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TransportChannelWiringIntegrationTest,PollingWorkerTaskFlowIntegrationTest,ExternalWorkerPollingApiIntegrationTest,ExternalWorkerRealtimeRegistrationIntegrationTest,NodeWebSocketWorkerBlackBoxIntegrationTest
```

Focus acceptance on:

- worker online/offline state perception
- task dispatch hitting the correct adapter by `adapterId`
- polling `poll` and `submitResult`
- realtime registration and message delivery
- raw/control side-channel resolution
- task terminal closure and worker/context resource release
