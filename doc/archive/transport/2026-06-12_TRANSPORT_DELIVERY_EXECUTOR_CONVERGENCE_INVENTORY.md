# Transport Delivery Executor Convergence Inventory

Status: archived completion inventory for
`2026-06-12_TRANSPORT_DELIVERY_EXECUTOR_CONVERGENCE_ROADMAP.md`.

This inventory originally recorded production and test usage before behavior
changes. Keep the table as before-state evidence; use the current outcome
snapshot below for the current mainline.

## Current Outcome Snapshot

- `TaskDispatchDeliveryCommandSubmitter` now lives in SDK/starter assembly and
  translates neutral engine assignment facts into `DeliveryCommand`.
- Transport runtime main sources no longer import `TaskDispatchBatchListener`,
  `TaskDispatchContext`, `TaskDispatchBinding`, `TaskResultIngestFacade`, or
  `TaskResultCorrelation`.
- `RouteTargetedTaskDispatch*`, `TransportDispatchFailureHandler`, and
  Redis dispatch-failure bridge classes are removed from transport runtime
  mainline.
- `TransportDeliveryCommandHandoff` / `DeliveryCommandBatch` are the current
  in-memory and Redis handoff shapes.
- Retryable delivery failures use `TransportDeliveryFailureEvent` and are
  drained by SDK/starter into engine-owned `TaskDispatchDeliveryFailure`
  compensation.
- `RuntimeTaskResultIngestChannel` moved to SDK/starter, where engine result
  correlation and lease/attempt validation belong.
- WebSocket/socket selected-worker endpoint replacement is scoped by worker
  identity inside the adapter, not by routeKey.
- `WorkerEndpointRegistry` is now selected-worker only. Raw/manual route sends
  use `RawWorkerRouteEndpointRegistry` or adapter-local
  `RawWorkerMessageChannel`.

## Owner Decision

Engine core owns assignment, selected-worker binding, task lease truth,
compensation, retry, and result convergence. Engine main sources currently use
neutral `xa-mass-base` dispatch/result contracts and do not import transport
API/runtime packages.

Starter/integration assembly currently wires engine to transport. It may host
the task-assignment to delivery-command translator. Transport runtime must not
become the owner of task assignment, task compensation, or result lifecycle
validation.

Transport owns delivery execution: adapter selection, endpoint/session
evidence, bounded queueing, polling, draining, frame mechanics, and delivery
outcome facts.

## Initial Symbols (Pre-DEX-2)

| Symbol | Current Owner | Production Caller/Location | Classification | Target |
| --- | --- | --- | --- | --- |
| `TaskDispatchBatchListener` | `xa-mass-base` neutral engine dispatch boundary | Engine core accepts it in `EngineRuntimeKernel.start`; SDK/starter creates `RouteTargetedTaskDispatchSubmitter`; transport route-targeted listener/pump consume it indirectly | engine-to-transport boundary translator | Keep engine core on neutral boundary until DEX-2; move transport-specific translation to starter/integration assembly. |
| `TaskDispatchContext` | `xa-mass-base` task assignment snapshot | `TaskDispatchItem`, `TaskDispatchBatchCodec`, `RouteTargetedTaskDispatchBatch`, failure events, submitter/listener compensation | task lifecycle leak in transport runtime | Remove from transport executor/core after DEX-2/DEX-3; allowed only in temporary translator or compatibility codecs. |
| `TaskDispatchBinding` | `xa-mass-base` selected-worker assignment truth | `TaskDispatchItem`, `RouteTargetedTaskDispatchBinding`, `TaskDispatchBatchCodec`, failure channel/event, SDK compensation wiring | engine assignment truth crossing transport runtime | Convert to delivery command in starter/integration assembly; transport core should carry selected-worker delivery facts, not task binding objects. |
| `RouteTargetedTaskDispatch*` | `transport_runtime` legacy handoff path | Submitter, batch, binding, codec, handoff, pump, listener, tests, SDK/starter assembly | route-key/task-aware dispatch runtime residue | Retarget to delivery-command executor/handoff; delete after callers move. |
| `TransportDispatchFailureHandler` | `transport_runtime` transitional bridge | SDK/starter creates a handler that calls engine assignment compensation; submitter/listener call `compensate` | dispatch compensation bridge | New delivery executor path must emit outcome/failure records only; engine-owned drain decides compensation; remove from transport runtime core in DEX-6. |
| `TransportDispatchFailureEvent` / failure channel | `transport_runtime` distributed compensation queue | Redis failure channel/inbox pump and SDK/starter engine-producer role | dispatch compensation bridge | Move payload ownership to engine-owned outcome/failure drain or make transport failure event delivery-shaped before final cleanup. |
| `TaskResultIngestChannel` | `transport_api` worker ingress protocol | Adapters and SDK use it to submit worker result frames/envelopes | worker protocol compatibility surface | Keep as protocol ingress until DEX-4 introduces inbound envelope boundary. |
| `RuntimeTaskResultIngestChannel` | `transport_runtime` current result validator | SDK/starter creates it with `TaskResultIngestFacade`; tests assert validation behavior | result lifecycle leak | Move result identity/lease validation to engine-owned ingestion; transport core should relay inbound envelope/payload only. |
| `TaskResultIngestFacade` | `xa-mass-base` engine result query/apply port | `RuntimeTaskResultIngestChannel`; SDK/starter engine config | engine result lifecycle owner | Must not be imported by transport runtime core after DEX-4. |
| `TaskResultCorrelation` | `xa-mass-base` result lifecycle correlation | `RuntimeTaskResultIngestChannel`; engine owns construction/support | engine result lifecycle truth | Must not be interpreted by transport runtime core after DEX-4. |
| `TaskDispatchItem` | `transport_api` worker-facing dispatch payload | Transport adapters, delivery store tests, packet factory, polling pull result | worker protocol compatibility payload | Keep during convergence as opaque payload/codec output; do not use as executor scheduling or lifecycle truth. |
| `TaskResultReport` | `transport_api` worker-facing result payload | WebSocket/socket frame codecs, polling/server ingress, result envelopes | worker protocol compatibility payload | Keep during convergence as worker protocol DTO; DEX-4 wraps/relays it through inbound envelope without lifecycle validation in transport. |
| `TransportResultEnvelope` | `transport_api` addressed result ingress envelope | Adapters, codecs, result ingest channel, Redis result inbox | inbound protocol envelope | Retarget to `InboundEnvelope` or adapter-specific payload carrier in DEX-4. |
| `DispatchOutcome` / `DispatchOutcomeStatus` | `transport_api` current adapter-neutral delivery result | Adapter SPI, polling store/service, direct send service, adapter tests | transport delivery outcome contract | Evolve in place or rename in one no-dual-track slice; do not introduce parallel `DeliveryOutcome` mainline. |
| `WorkerEndpointRegistry#sendToAdapterRoute` | pre-DEX-5 `transport_api` shared endpoint registry | Removed from `WorkerEndpointRegistry`; raw/manual route sends moved to `RawWorkerRouteEndpointRegistry` and adapter-local `RawWorkerMessageChannel` | resolved raw/manual side-channel mix | Keep assigned task delivery on selected-worker endpoint contract only. |
| `RawWorkerMessageChannel` | `transport_runtime` adapter-scoped raw/manual side channel | SDK/starter registration and `MassApplication` manual send helpers; adapter bootstraps | allowed raw/manual side channel | Preserve as side-channel, but keep separate from assigned task delivery. |
| `WorkerDispatchRouteOwnerView#currentOwners` | `transport_api` route-owner diagnostic/read API | Runtime route stores, diagnostics, default route reads | route-key routing residue when used for dispatch | Assigned delivery executor should use selected-worker lookup only; move route scans to inspection/maintenance API in DEX-6. |

## Initial Hot-Path Residue

| Site | Current Behavior | Classification | Target |
| --- | --- | --- | --- |
| `InMemoryRouteTargetedTaskDispatchHandoff#submit` | Uses blocking `queue.put(batch)` | blocking/backpressure residue | Replace producer path with bounded offer/outcome in DEX-3. |
| `RedisRouteTargetedTaskDispatchHandoff#submit` | Loops `LLEN -> RPUSH -> SADD lanes -> SADD ready-lanes`, sleeps on full | blocking/backpressure and Redis atomicity residue | Single atomic offer updates queue, lane catalog, and ready-lane wakeup; return queued/backpressure/shutdown outcome. |
| `RouteTargetedTaskDispatchBatch` | Requires top-level `routeKey`, target transport node, and same route/lane across all bindings | route-key routing residue | Delivery command batches should be lane/locality grouped; routeKey remains per-command metadata only. |
| `ServerSessionManager` / `SocketSessionManager` replacement | Looks for previous active endpoint by current `routeKey + workerId` | route-key endpoint residue | After raw route side-channel split, selected task endpoint replacement is keyed by `adapterId + selectedWorkerId`. |
| `WebSocketOutputProcessor` | Calls `RawWorkerRouteEndpointRegistry#sendToAdapterRoute` for raw outbound route sends | raw/manual side channel | Keep separate from assigned task dispatch; guard only task dispatch packages. |

## Module Dependencies

| Module | Current Dependency | Reason Seen In Code | Target |
| --- | --- | --- | --- |
| `transport/transport_api` | `xa-mass-base` | `TaskDispatchItem` imports `TaskDispatchContext` and `TaskDispatchBinding`; worker protocol DTO still task-shaped | Keep during protocol compatibility phase; revisit after worker protocol owner decision. |
| `transport/transport_runtime` | `xa-mass-base` | route-targeted dispatch batches/codecs/failure events and result ingest validation | Remove from delivery executor core as DEX-2/DEX-4/DEX-6 land; temporary translator/compatibility code must be named and isolated. |
| `transport/transport_runtime` | `xa-mass-engine` test scope | transport runtime tests currently depend on engine fixtures/behavior | Keep test-only until proof surfaces move; no production engine dependency. |
| `transport/websocket-adapter` | `xa-mass-base`, `xa-mass-engine` | worker protocol tests and adapter assembly fixtures | Revisit after task-shaped worker protocol cleanup; not part of DEX-1. |
| `transport/socket-adapter` | `xa-mass-base`, `xa-mass-engine` | worker protocol tests and adapter assembly fixtures | Revisit after task-shaped worker protocol cleanup; not part of DEX-1. |
| `sdk/xa-mass-embedded-sdk` | engine + transport runtime/API/adapters | current integration assembly and transport submitter wiring | Expected home for DEX-2 translator unless a neutral base contract is added. |
| `xa-mass-engine` | no transport dependency in main sources | engine accepts neutral dispatch listener and produces base assignment truth | Preserve; add guard evidence as DEX-2 lands. |

## Initial Test Usage

Test sources intentionally still construct current worker protocol DTOs, but no
longer preserve route-targeted dispatch batches as production proof:

- `transport_api` tests cover `TaskDispatchItem`, `TaskResultReport`,
  `TransportResultEnvelope`, and `DispatchOutcome`.
- `transport_runtime` tests cover delivery-command handoff, delivery failure
  channel, delivery store, route owner, and architecture guards.
- adapter tests cover selected-worker direct send, raw/manual route send,
  frame codecs, result ingress, and session behavior.
- SDK/starter tests cover distributed transport assembly, result identity
  validation, raw worker message side channels, start/stop order, and engine
  dispatch binding observation.

Tests should move with their production owner. They must not preserve old
route-targeted or task-lifecycle vocabulary as a hidden second production API
after production callers move.

## Decisions For Next Slices

- DEX-1 should evolve current `DispatchOutcome` rather than introduce a parallel
  `DeliveryOutcome`, unless the same slice migrates all adapter SPI callers.
- DEX-1 should add `DeliveryCommand` and `InboundEnvelope` as transport
  contracts without changing runtime behavior.
- DEX-2 translator belongs in starter/integration assembly. Engine core must
  remain transport-free; this is now guarded.
- DEX-3 replaced blocking handoff producer behavior with delivery outcomes.
- DEX-4 moved result correlation and lease validation out of transport runtime
  core into SDK/starter.
- DEX-5 selected-worker endpoint replacement and raw-route interface split are
  landed. Assigned task delivery callers cannot see route-only raw send helpers
  through `WorkerEndpointRegistry`.
- DEX-6 residue removal is landed for production code; active-doc residue is
  handled by the owning transport baseline and proof registry.
