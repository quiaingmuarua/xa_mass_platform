# Worker Runtime Selected Worker Mailbox Evidence Inventory

Status: current code inventory for
`WORKER_RUNTIME_SELECTED_WORKER_MAILBOX_EVIDENCE_CONVERGENCE_ROADMAP.md`.

## Summary

The current mainline has the right narrow read shape:

```text
selectedWorkerId -> adapterMailboxKey
```

The embedded/in-memory path already materializes this from worker session
presence. Split runtime still needs an explicit deployment-injected view or a
future shared worker-runtime projection. This inventory must not be used as
permission to add worker lists, mailbox lists, counts, dashboards, or endpoint
inspection to the delivery target mainline.

## Mainline Symbols

| Symbol | Current Owner | Current Caller | Classification | Target |
| --- | --- | --- | --- | --- |
| `WorkerDeliveryTargetView` | worker-runtime evidence | `TaskDispatchRoutingSubmitter`, injected test fixtures | dispatch-facing point lookup | Keep as the only producer-side delivery target read contract. |
| `SelectedWorkerDeliveryTargetEvidence` | worker-runtime evidence | submitter and worker-runtime tests | selected-worker mailbox evidence | Keep narrow; do not add list/stat/view fields. |
| `WorkerPresenceRuntime` | worker-runtime presence | `WorkerRuntimePresenceIngress`, tests, embedded assembly | presence ingress plus embedded default delivery target view | Keep local/embedded default only; do not treat as distributed truth. |
| `InMemoryWorkerPresenceRuntime` | worker-runtime embedded implementation | `EngineConfig` default, tests | local projection | Valid for embedded runtime where producer and session observations share a process. Not proof of split-runtime truth. |
| `EngineConfig.workerDeliveryTargetView` | SDK/starter assembly | `MassApplication` submitter assembly | injection seam | Formalize as split producer contract and guard missing explicit view. |
| `TaskDispatchRoutingSubmitter` | SDK/starter delivery integration | engine assignment output | delivery integration consumer | Consume `WorkerDeliveryTargetView` only after engine selects `selectedWorkerId`. |

## Presence Writers

| Writer | Current Fact | Classification | Target |
| --- | --- | --- | --- |
| `WorkerPresenceSessionPublisher` | normalizes adapter session observations into `WorkerSessionPresenceEvent` | transport-to-worker-runtime ingress helper | Keep as ingress; it does not own delivery target truth. |
| `PollingSessionEvidenceDriver` | publishes polling worker session presence with adapter mailbox metadata | adapter session evidence writer | Keep writer; no producer-side lookup. |
| `WebSocketSessionController` / `WebSocketSessionEvidenceRefresher` via `AdapterSessionEvidencePublisher` | publishes WebSocket session presence with adapter mailbox metadata | adapter session evidence writer | Keep direct publisher capability; controller owns connect/disconnect, refresher owns heartbeat/keepalive, and no WebSocket-only driver wrapper or producer-side lookup is allowed. |
| `SocketSessionManager` | publishes socket session presence with adapter mailbox metadata | adapter session evidence writer | Keep writer; no producer-side lookup. |
| `WorkerRuntimePresenceIngress` | forwards `adapterMailboxKey` into `WorkerPresenceRuntime` | worker-runtime ingress bridge | Focused proof covers `adapterId != adapterMailboxKey`. |
| Direct test calls to `sessionConnected(...)` | seed in-memory runtime in unit/integration tests | test fixture | Keep test-only; do not cite as split distributed proof. |

## Field Disposition

| Field | Current Use | Classification | Target |
| --- | --- | --- | --- |
| `workerId` | submitter checks evidence matches selected worker | required delivery target | Keep. |
| `adapterMailboxKey` | submitter groups delivery commands by physical mailbox | required delivery target | Keep as opaque mailbox address. |
| `reachabilityState` | `isDeliverable(...)` rejects non-online evidence | bounded deliverability evidence | Keep only while submitter needs online/stale distinction; do not expand labels for inspection. |
| `generation` | records replacement/migration generation | stale-protection evidence | Keep only for stale-target protection and tests; not an operator sequence view. |
| `observedAtEpochMillis` | records last observation time on evidence | diagnostic/support metadata | Not required by current submitter behavior. A later narrowing slice should move it to diagnostics/store-private metadata if no strategy uses it. |
| `expiresAtEpochMillis` | `isDeliverable(...)` rejects expired evidence | freshness evidence | Keep. |

## Statistics And View Inventory

| Symbol | Current Use | Classification | Target |
| --- | --- | --- | --- |
| `InMemoryWorkerPresenceRuntime.activeSessionCount(...)` | removed | removed residue | Do not reintroduce count APIs into the delivery-target mainline. |
| Endpoint/session inspectors under transport adapters | diagnostics and support tests | side-channel diagnostics | Do not connect to `WorkerDeliveryTargetView` or producer dispatch. |
| Delivery store/queue stats | queue diagnostics and tests | transport diagnostics | Do not use as worker reachability, mailbox evidence, or scheduling input. |

## Current Gaps

- No shared worker-runtime projection exists for split producer/consumer
  deployments. That remains optional until injection is proven insufficient.
- Full `xa-mass-worker-runtime` test execution is currently blocked by
  unrelated stale test sources that reference removed candidate/admission
  classes; main compile and cross-module SWME proof pass.

## Decisions

- The injected view contract and startup guard are the accepted split-runtime
  mainline unless a deployment later proves a shared store is necessary.
- Do not implement a shared Redis or runtime store merely to satisfy this
  roadmap if explicit deployment injection is enough for the accepted split
  profile.
- Do not add list, count, snapshot, stats, or inspect APIs to make proof easier.
  Proof should be point lookup, failure semantics, and end-to-end delivery.
