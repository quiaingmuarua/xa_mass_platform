# Transport Push Adapter Final-Hop Boundary Inventory

Status: post-implementation inventory for
`2026-06-18_TRANSPORT_PUSH_ADAPTER_FINAL_HOP_BOUNDARY_CONVERGENCE_ROADMAP.md`.

## Scope

This inventory covers assigned-delivery push adapter final-hop execution:

```text
DeliveryCommandBatch
  -> TransportDeliveryCommandListener
  -> AdapterCommandExecutor
  -> concrete push adapter local session write
  -> DispatchOutcome
```

It does not cover polling pull-buffer internals, result ingress, raw/manual
route channels, endpoint lease store semantics, or route-key removal except
where those surfaces currently leak into push assigned delivery.

## Symbols

| Symbol | Current Owner | Current Use | Classification | Target |
| --- | --- | --- | --- | --- |
| `AdapterCommandExecutor` | `transport_runtime` embedded support | Local embedded Java final-hop callback consumed by `TransportDeliveryCommandListener` | valid executor seam | Keep. It remains the single push/poll adapter dispatch input: `dispatch(List<DeliveryCommand>)`. |
| `TransportDeliveryCommandListener` | `transport_runtime` embedded support | Reads endpoint lease by `deliveryBucketId + selectedWorkerId`, resolves local `TransportBinding`, groups by adapter id, calls `AdapterCommandExecutor` | valid runtime-to-adapter bridge | Keep. It should own executor-level unavailable/rejected/failure normalization only. |
| `TransportDeliveryService.enqueue/poll/drain` | `transport_runtime` delivery store owner | Polling queue and delivery-store operations | valid queue service | Keep for polling/buffered delivery. |
| `TransportDeliveryService.sendDirect(...)` | removed | No production or test caller should remain | resolved owner leak | Keep removed. Push executors produce `DispatchOutcome` directly. |
| `TransportDeliverySender` | removed | No longer needed after `sendDirect(...)` removal | resolved helper residue | Keep deleted. |
| `WorkerEndpointRegistry` | removed from `transport_api` | No assigned-delivery neutral registry remains | resolved mixed owner | Keep deleted; do not replace with a same-shape interface. |
| `CompositeWorkerEndpointRegistry` | removed from `transport_runtime` | No global selected-worker registry aggregation remains | resolved runtime wrapper residue | Keep deleted. |
| `TransportAdapterBootstrapContext.getEndpointRegistry()` | removed | Adapter bootstraps no longer receive a global selected-worker registry | resolved assembly leak | Keep absent. |
| `TransportConfig.workerEndpointRegistry` and `endpointRegistryFactory` | removed | Embedded callers no longer override assigned-delivery endpoint registry | resolved public assembly residue | Keep absent; use explicit diagnostics/lifecycle seams only if needed. |
| `TransportRuntimeComposition.resolveWorkerEndpointRegistry()` | removed | Runtime composition no longer creates a selected-worker endpoint registry | resolved assembly residue | Keep absent. |
| `MassApplication.endpointRegistry` / `getEndpointRegistry()` | removed | Starter diagnostics now use `WorkerEndpointInspector` / queue stats | resolved diagnostics/dispatch residue | Keep absent. |
| `DefaultRuntimeDiagnosticsOperations.resolveEndpointRegistry()` | removed | Session stats derive from `WorkerEndpointInspector` snapshots | resolved diagnostics coupling | Keep absent. |
| `WebSocketTaskDispatchChannel` | `websocket-adapter` | Reads `WebSocketSessionStore.activeRecordForWorker(selectedWorkerId)`, writes the WebSocket frame, and returns `DispatchOutcome` directly | converged executor shape | Keep. It owns assigned-delivery final-hop send and outcome production for WebSocket. The local lookup key is worker id only, not bucket plus worker. |
| `WebSocketSessionController implements WebSocketServerSessionHandle` | `websocket-adapter` | Server session orchestration, bind/remove/shutdown, evidence/refresh coordination | converged session-controller shape | Keep as server/session orchestration only; it must not implement selected-worker send. |
| `WebSocketSessionStore` | `websocket-adapter` | Owns active session indexes, lookup, snapshots, replacement/retired-channel state | valid state owner | Keep as adapter-local state/index owner. It must not own frame write behavior. |
| `WebSocketServerSession` | `websocket-adapter` | Bounded current-session projection returned to inbound handler | narrow projection, naming questionable | Keep only if renamed/contained as `BoundSession` on server handle; do not expose bucket/route/channel/context. |
| `SocketTaskDispatchChannel` | `socket-adapter` | Produces outcomes directly, encodes frame, calls adapter-local `SocketSessionManager.sendToWorker(selectedWorkerId, message)` | converged executor shape | Keep. It must not regain `TransportDeliveryService`, `sendDirect(...)`, `WorkerEndpointRegistry`, `SocketCommandDispatchContext`, or executor-owned adapter id. |
| `SocketCommandDispatchContext` | removed | No longer used by socket push assigned delivery | resolved wrapper residue | Keep deleted. |
| `SocketSessionManager` | `socket-adapter` | Adapter-local session/evidence/shutdown owner with worker-id send helper | broad manager, accepted as current adapter-local owner | It no longer implements `WorkerEndpointRegistry`; optional future Socket internal cleanup may split store/controller/evidence/diagnostics. |
| `TransportConvergenceArchitectureGuardTest` WebSocket/Socket push guards | `transport_runtime` tests | Guards now forbid endpoint registry, command-context wrappers, `sendDirect(...)`, and executor-owned adapter id on push assigned delivery | active guard proof | Keep aligned with final-hop boundary. |
| `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md` | WebSocket owner doc | Records WebSocket store/controller/executor split | current doc | Keep current. |
| `transport/TRANSPORT_BOUNDARY_BASELINE.md` | transport owner doc | Records concrete push adapter final-hop ownership and queue-only delivery service stats | current doc | Keep current. |
| `doc/PROOF_REGISTRY.md` | proof index | Records WebSocket/Socket final-hop proof without endpoint-registry tests | current proof index | Keep current. |

## Current Production Call Families

| Caller Family | Current Path | Target |
| --- | --- | --- |
| WebSocket assigned delivery | `WebSocketTaskDispatchChannel -> WebSocketSessionStore.activeRecordForWorker(selectedWorkerId) -> channel.writeAndFlush -> DispatchOutcome` | Current target shape. Keep executor-owned final-hop write; store remains lookup/state only. Do not add bucket-worker lookup to the adapter-local send path. |
| Socket assigned delivery | `SocketTaskDispatchChannel -> SocketSessionManager.sendToWorker(selectedWorkerId, message) -> DispatchOutcome` | Current target shape. |
| Runtime adapter assembly | Adapter bootstraps create local executors/session owners directly; no global endpoint registry is passed through bootstrap context | Current target shape. |
| Diagnostics | `DefaultRuntimeDiagnosticsOperations -> MassApplication.getEndpointInspector()` plus queue stats | Current target shape. |
| Shutdown | Managed adapter/server lifecycle owns shutdown; selected-worker send interfaces do not own lifecycle | Current target shape. |

## Decisions Already Clear

- `sendToSelectedWorker` is not a transport-neutral API.
- Push adapter final-hop correctness belongs to the concrete adapter executor;
  the local session store/manager may provide lookup or session access, but it
  must not become a neutral send API.
- Push adapter local lookup is `selectedWorkerId` / worker id only.
  `deliveryBucketId` remains queue/evidence context and must not become a
  second adapter-local session lookup dimension.
- `TransportDeliveryService.sendDirect(...)` is a callback loop and should not
  remain the push adapter mainline.
- A concrete adapter class should not implement multiple role interfaces unless
  it is a deliberately documented top-level adapter manager/assembly point.
- `WorkerEndpointRegistry` should not remain in `transport_api` just to support
  embedded Java adapter wiring.

## Open Classification

- Whether push final-hop counters should be added later as adapter-local
  diagnostics. They must not be recorded by routing push sends through
  `TransportDeliveryService`.
- Whether `WebSocketServerSession` should be renamed to an inner
  `WebSocketServerSessionHandle.BoundSession` projection. It is not a truth
  record and must not grow endpoint/bucket/route/channel fields.
- Whether Socket should split into `SocketSessionStore` and
  `SocketSessionController` in the same implementation pass as final-hop
  executor cleanup, or in the immediately following slice.
