# Transport Adapter Internal Capability Convergence Inventory

Status: current code inventory for
`TRANSPORT_ADAPTER_INTERNAL_CAPABILITY_CONVERGENCE_ROADMAP.md`.

This inventory classifies concrete adapter internals after the polling
capability split. Polling is now the implemented reference shape; WebSocket and
Socket remain later alignment targets.

## Symbols

| Symbol | Current Owner | Current Role | Classification | Target |
| --- | --- | --- | --- | --- |
| `PollingWorkerAdapter` | removed | Historical mixed object that implemented both `AdapterCommandExecutor` and `DeliveryPullChannel` and also owned endpoint lease publisher wiring. | Removed residue | Keep deleted; do not reintroduce as a compatibility wrapper. |
| `PollingAdapterMetadata` | `transport/polling-adapter` | Adapter-local metadata for polling bootstrap contribution: adapter id, protocol label, and transport hint. | Metadata holder | Keep local to polling runtime; do not derive metadata from executors. |
| `PollingDeliveryExecutor` | `transport/polling-adapter` | Implements `AdapterCommandExecutor` and calls `TransportDeliveryService.enqueue(adapterId, commands)`. | Narrow polling command executor | Must not import pull-channel, endpoint lease, consumer registry, or presence types. |
| `PollingDeliveryPullChannel` | `transport/polling-adapter` | Implements `DeliveryPullChannel` and calls `TransportDeliveryService.pollItemResult(deliveryBucketId, selectedWorkerId, ...)`. | Narrow polling pull channel | Must not import command-executor, endpoint lease, consumer registry, or presence types. |
| `PollingSessionEvidenceDriver` | `transport/polling-adapter` | Implements `PullSessionEvidenceDriver` and delegates session observations to `TransportEndpointLeasePublisher` and `WorkerPresenceSessionPublisher`. | Pull-session evidence driver | May use evidence publishers; must not import `DeliveryCommand`, pull-channel, or polling delivery buffer classes. |
| `PollingTransportAdapterBootstrap` | `transport/polling-adapter` | Creates explicit polling metadata, command executor, pull channel, and session evidence driver, then contributes them through `TransportBinding`. | Adapter composition root with explicit capability contribution | Keep as the owner of polling embedded adapter composition. |
| `PullWorkerSession` | `sdk/xa-mass-embedded-sdk` | SDK-facing pull worker session. Polls through `DeliveryPullChannel`, submits results, and calls a runtime-resolved `PullSessionEvidenceDriver` for connect/heartbeat/disconnect evidence. | Production pull-session action object | Must not import raw endpoint lease stores, consumer registries, presence ingress, lease command models, or consumer claim models. |
| `InternalPullWorkerSessions` | `sdk/xa-mass-embedded-sdk` | Internal factory that passes pull channel, result ingress channel, and `PullSessionEvidenceDriver` into `PullWorkerSession`. | Assembly bridge with narrow evidence seam | Keep raw stores/registries/ingress out of the session constructor. |
| `MassApplication.openPullWorkerSession(...)` | `sdk/xa-mass-embedded-sdk` | Resolves `ResolvedPullWorkerTransport` and opens a `PullWorkerSession`. | Embedded SDK production entry path | Continue as the public starter entry; evidence internals stay behind resolved transport binding. |
| `ResolvedPullWorkerTransport` | `transport_runtime` | Carries worker id/group, adapter id, transport hint, pull channel, result ingress, and pull-session evidence driver. | Runtime pull binding result | Keep raw stores and consumer registries out of SDK session construction. |
| `PullSessionEvidenceDriver` | `transport_runtime` embedded support | Narrow connect/heartbeat/disconnect evidence projection seam consumed by `PullWorkerSession`. | Runtime-resolved capability seam | Own evidence projection without exposing stores/registries to SDK session code. |
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

Polling is now split into explicit capabilities:

- `PollingDeliveryExecutor`: assigned-delivery command execution and polling
  enqueue outcome logging
- `PollingDeliveryPullChannel`: worker pull-buffer polling and status mapping
- `PollingSessionEvidenceDriver`: endpoint lease, selected-worker consumer, and
  worker session-presence projection through runtime publishers

`PullWorkerSession` is still part of the production polling path. It now owns:

- worker pull session lifecycle methods
- worker invocation polling
- result submission

It no longer owns worker session-presence event construction, endpoint lease
record construction, or selected-worker consumer claim construction.

`ServerSessionManager` and `SocketSessionManager` remain larger than ideal, but
they are not the first implementation target because push adapters include real
protocol session lifecycle, raw/manual side-channels, and diagnostics. Polling
is still the smaller proof surface for the internal capability split, but the
first polling slice must include the SDK `PullWorkerSession` production path.

## Related Active Or Historical Roadmaps

| Roadmap | Relationship |
| --- | --- |
| `2026-06-18_TRANSPORT_ADAPTER_COMMAND_EXECUTOR_CONVERGENCE_ROADMAP.md` | Mainline executor boundary is landed; this inventory tracks concrete adapter internal residuals after that work. |
| Transport baseline embedded-adapter pressure test | Provides the embedded Java adapter independence rule. This inventory does not implement external adapters. |
| Transport baseline route-key cleanup | Route-key removal should be easier after concrete adapter capabilities stop mixing command execution with route/raw/session concerns. |

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
