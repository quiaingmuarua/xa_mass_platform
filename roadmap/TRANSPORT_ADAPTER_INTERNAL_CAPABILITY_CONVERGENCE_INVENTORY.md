# Transport Adapter Internal Capability Convergence Inventory

Status: current code inventory for
`TRANSPORT_ADAPTER_INTERNAL_CAPABILITY_CONVERGENCE_ROADMAP.md`.

This inventory classifies concrete adapter internals after the
`AdapterCommandExecutor` and embedded-adapter-independence mainline work. It is
not proof that the target shape is implemented.

## Symbols

| Symbol | Current Owner | Current Role | Classification | Target |
| --- | --- | --- | --- | --- |
| `PollingWorkerAdapter` | `transport/polling-adapter` | Implements both `AdapterCommandExecutor` and `DeliveryPullChannel`; also owns endpoint lease publisher wiring and public online/offline/refresh helpers. | Mixed adapter capability object | Split into polling command executor, polling pull channel, and pull-session evidence driver. |
| `PollingTransportAdapterBootstrap` | `transport/polling-adapter` | Creates one `PollingWorkerAdapter` and contributes it as both command executor and pull channel. | Adapter composition root with multi-role contribution | Create explicit capability instances and contribute them separately through `TransportBinding`. |
| `PullWorkerSession` | `sdk/xa-mass-embedded-sdk` | SDK-facing pull worker session. Polls through `DeliveryPullChannel`, submits results, and currently writes worker presence, endpoint lease, and selected-worker consumer evidence directly. | Production pull-session action object with embedded transport evidence writes | Keep polling/result session behavior, but move connect/heartbeat/disconnect evidence writes behind a runtime-resolved pull-session evidence driver. |
| `InternalPullWorkerSessions` | `sdk/xa-mass-embedded-sdk` | Internal factory that passes `TransportEndpointLeaseStore`, `DeliveryCommandConsumerRegistry`, and `WorkerPresenceIngress` into `PullWorkerSession`. | Assembly bridge exposing transport internals to the session constructor | Pass a narrow pull-session evidence driver instead of raw stores/registries/ingress. |
| `MassApplication.openPullWorkerSession(...)` | `sdk/xa-mass-embedded-sdk` | Resolves `ResolvedPullWorkerTransport` and opens a `PullWorkerSession`. | Embedded SDK production entry path | Continue as the public starter entry, but stop threading raw evidence stores into the session. |
| `ResolvedPullWorkerTransport` | `transport_runtime` | Carries worker id/group, adapter id, transport hint, pull channel, result ingress, endpoint lease store, and consumer registry. | Runtime pull binding result with too many evidence internals for SDK session construction | Add or resolve a narrow pull-session evidence driver; keep raw stores out of `PullWorkerSession`. |
| `PullSessionEvidenceDriver` | target runtime embedded-support seam | Not present. | Missing production capability seam | Own connect/heartbeat/disconnect evidence projection for pull sessions without exposing stores/registries to SDK session code. |
| `TransportDeliveryService.enqueue(...)` | `transport_runtime` | Converts `DeliveryCommand.deliveryBucketId` into bucket queue key and enqueues `QueuedPulledDispatch`. Also accepts adapter id for store/stats context. | Runtime delivery store front door | Keep in current slice; later decide whether adapter id remains diagnostics/store context or moves behind a narrower polling enqueue command. |
| `TransportDeliveryService.pollItemResult(...)` | `transport_runtime` | Polls bucket queue by `deliveryBucketId + selectedWorkerId` demux. | Runtime pull buffer front door | Keep behavior; polling pull channel should be the only adapter-local caller. |
| `TransportEndpointLeasePublisher` | `transport_runtime` | Builds endpoint lease and selected-worker consumer evidence from adapter session facts. | Runtime evidence projector | Keep as shared projector; concrete adapters should orchestrate calls, not duplicate record construction. |
| `WebSocketTaskDispatchChannel` | `transport/websocket-adapter` | Implements `AdapterCommandExecutor`; sends `DeliveryCommand` by `selectedWorkerId` through selected-worker endpoint registry. | Narrow push command executor | Use as reference shape for polling executor boundaries. |
| `SocketTaskDispatchChannel` | `transport/socket-adapter` | Implements `AdapterCommandExecutor`; sends by selected worker through socket endpoint registry. | Narrow push command executor | Use as secondary reference shape after polling slice lands. |
| `ServerSessionManager` | `transport/websocket-adapter` | Owns selected-worker session index, connection counters, replacement/shutdown behavior, endpoint lease refresh loop orchestration, and presence/lease publisher calls. | Large protocol session manager | Later split selected-worker sender/session store from lease/presence projection orchestration if needed. Do not start here. |
| `SocketSessionManager` | `transport/socket-adapter` | Owns socket session index, selected-worker send, endpoint lease/presence publisher calls, and socket session lifecycle. | Large protocol session manager | Later align with websocket after polling capability split proves the seam. |
| `WebSocketRawWorkerRouteEndpointRegistry` | `transport/websocket-adapter` | Adapter-local raw/manual route side-channel. | Side-channel, not assigned delivery | Keep outside command executor; do not use as assigned-delivery fallback. |
| `TransportBinding` | `transport_runtime` | Holds adapter id, transport hint, protocol label, command executor, and optional pull channel. | Embedded adapter binding metadata | Remains the explicit contribution contract. It must not infer metadata from executor implementations. |
| `DeliveryPullChannel` | `transport_api` | Transport pull channel for polling delivery messages. | Pull-channel contract | A concrete adapter should implement it through a pull-channel object, not by reusing a command executor object. |

## Current Mixed Roles

`PollingWorkerAdapter` currently combines:

- assigned-delivery command execution
- worker pull-buffer polling
- endpoint lease claim / refresh / release orchestration
- adapter-id normalization and rejection logging

`PullWorkerSession` is also part of the production polling path. It currently
combines:

- worker pull session lifecycle methods
- worker invocation polling
- result submission
- worker session-presence publication
- endpoint lease claim / refresh / release
- selected-worker consumer claim / release

`ServerSessionManager` and `SocketSessionManager` remain larger than ideal, but
they are not the first implementation target because push adapters include real
protocol session lifecycle, raw/manual side-channels, and diagnostics. Polling
is still the smaller proof surface for the internal capability split, but the
first polling slice must include the SDK `PullWorkerSession` production path.

## Related Active Or Historical Roadmaps

| Roadmap | Relationship |
| --- | --- |
| `TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md` | Mainline executor boundary is landed; this inventory tracks concrete adapter internal residuals after that work. |
| `TRANSPORT_EMBEDDED_ADAPTER_INDEPENDENCE_CONVERGENCE_ROADMAP.md` | Provides the embedded Java adapter independence pressure test. This inventory does not implement external adapters. |
| `TRANSPORT_ROUTE_KEY_REMOVAL_CONVERGENCE_ROADMAP.md` | Route-key removal should be easier after concrete adapter capabilities stop mixing command execution with route/raw/session concerns. |

## Decisions

- First executable slice should be polling-only.
- Polling-only still crosses `transport/polling-adapter`,
  `transport_runtime`, and `sdk/xa-mass-embedded-sdk`, because
  `PullWorkerSession` is a production evidence writer.
- WebSocket/socket should be used as comparison and later alignment targets, not
  as the first edit surface.
- Do not introduce a generic `AbstractAdapterRuntime` or same-module facade.
  The target is explicit capability ownership, not a new forwarding layer.
- Do not move adapter internals into `transport_api`.
- Do not introduce a service-locator context that lets command execution reach
  endpoint lease, presence, or consumer-evidence capabilities indirectly.
