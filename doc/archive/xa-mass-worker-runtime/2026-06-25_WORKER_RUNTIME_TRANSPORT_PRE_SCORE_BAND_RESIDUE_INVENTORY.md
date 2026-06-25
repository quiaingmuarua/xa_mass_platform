# Worker Runtime / Transport Pre-Score-Band Residue Inventory

Status: archived cleanup inventory for
[Worker Runtime / Transport Pre-Score-Band Residue Convergence Roadmap](./2026-06-25_WORKER_RUNTIME_TRANSPORT_PRE_SCORE_BAND_RESIDUE_CONVERGENCE_ROADMAP.md).

This inventory supports the score-band cleanup prerequisite. It classifies the
old worker-runtime / transport surfaces before implementation so the cleanup
does not accidentally delete post-selection delivery target lookup or
topology/admin inventory.

## Symbols

| Symbol | Current Owner | Current Callers | Classification | Target |
| --- | --- | --- | --- | --- |
| `WorkerPresenceIngress` | `transport_api` | deleted | transport-to-worker-runtime session event bridge residue | Deleted. Do not replace with a generic presence event interface. |
| `NoopWorkerPresenceIngress` | `transport_api` | deleted | compatibility/default residue for the event bridge | Deleted with `WorkerPresenceIngress`. |
| `WorkerSessionPresenceEvent` / `WorkerPresenceEventType` | `transport_api` | deleted | generic session event DTO residue | Deleted. Current-session disconnect may cross only as narrow negative dispatch block evidence. |
| `WorkerRuntimePresenceIngress` | `sdk/xa-mass-embedded-sdk` | deleted | embedded bridge from transport events to worker-runtime reachability, heartbeat refresh, dispatch recovery, and block | Deleted. Connected/heartbeat do not refresh worker-runtime or reopen dispatch eligibility. |
| `TransportConfig.customWorkerPresenceIngress` / builder `workerPresenceIngress(...)` / `TransportRuntimeComposition.resolveWorkerPresenceIngress()` | `sdk/xa-mass-embedded-sdk` | deleted | public-looking configuration for deleted bridge | Deleted. No compatibility alias. |
| `TransportAdapterBootstrapContext.workerPresenceIngress` | `transport_runtime` | deleted | bootstrap exposure of deleted bridge | Deleted. Replaced only by narrow current-session disconnect sink. |
| `AdapterSessionEvidencePublisher` | `transport_runtime` | websocket/socket/polling session evidence drivers | transport-local endpoint/session evidence publisher | Kept and narrowed. It writes transport endpoint/session evidence and may notify a narrow current-session disconnect sink only after currentness is accepted. |
| `TransportEndpointLeasePublisher` / `TransportEndpointLeaseStore` | `transport_runtime` / `transport_api` | session evidence publisher, memory/Redis lease stores, tests | transport-owned session/endpoint evidence | Keep for now. Not scheduling truth and not a worker-runtime positive recovery writer. |
| `WorkerDispatchBlockRuntime` / `WorkerDispatchBlockSignal` / `WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED` | `xa-mass-worker-runtime` | SDK/transport integration, worker-runtime gate tests | allowed negative dispatch evidence ingress | Keep narrow. Connected/heartbeat must not call it; only accepted current-session disconnect may emit `TRANSPORT_DISCONNECTED`. |
| `InMemoryWorkerPresenceRuntime` / `WorkerPresenceChange` | `xa-mass-worker-runtime` | deleted | composite in-memory session presence projection residue | Deleted. Embedded assigned delivery no longer depends on session-presence projection. |
| `SelectedWorkerDeliveryTargetEvidence` | `xa-mass-worker-runtime` | `EngineConfig`, `TaskDispatchRoutingSubmitter`, dispatch tests | post-selection delivery target evidence | Kept as assigned-dispatch point lookup. Current code uses `workerId`, `adapterMailboxKey`, and `isDeliverable(now)`; `generation` and `observedAtEpochMillis` were deleted. |
| `TaskDispatchRoutingSubmitter` delivery target resolver | `sdk/xa-mass-embedded-sdk` | engine assignment listener | post-selection translator from selected worker to adapter mailbox | Kept. Resolver caller already has `selectedWorkerId`; embedded default resolves worker registration plus transport binding to mailbox, and split engine-producer runtime requires an explicit resolver. |
| `WorkerReachabilityState` / `WorkerManager#getWorkerReachability(...)` | `xa-mass-worker-runtime` | `EngineConfig`, `MassSdkApplication` diagnostics, tests | reachability read model residue | Delete or isolate later. It must not sit on selection/admission/recovery path. |
| `WorkerRuntimeStateRecord` / `WorkerReadinessState` | `xa-mass-worker-runtime` | no production caller found in current main-source scan | composite read model residue | Default delete in WTP-3 unless a concrete API owner is accepted. |
| `WorkerOccupancyState` | `xa-mass-worker-runtime` | `WorkerLoadSnapshot#occupancyState()` | narrow load helper | Keep only if it remains a helper over load counters; do not promote to slot eligibility truth. |
| `AdapterNodeRecord` / `NodeGroupBindingRecord` / `WorkerNodeBindingRuntime` | `xa-mass-worker-runtime` | `WorkerManager`, SDK runtime-control operations, server/external worker routes and tests | adapter topology/admin inventory | Keep conservative for now. Document as topology/admin inventory, not score-band supply, demand lane, mailbox, or final-hop routing truth. |
| `WorkerCandidateBucketPolicy` / `candidateBucketKeys` | `platform_infra` / engine / worker-runtime | `RedisWorkerRegistry`, `WorkerTaskSelector`, scheduling resolver, tests | current bounded-candidate partition mechanism | Keep as BCA transition mechanism. Not the final score-band lane/index model. |
| `TaskCandidateWarmPool` | `xa-mass-worker-runtime` | `WorkerCandidateSourceOwner`, tests, architecture guards | task-local warm candidate cache residue | Delete in WTP-4. Do not rename or replace with another task-local worker cache. |
| `WorkerWarmHintRuntime` | `xa-mass-worker-runtime` | `WorkerManager`, `EngineConfig`, architecture guards | mutation port for warm-pool residue | Delete with warm pool. |
| `WorkerCandidateBatch` / `findWorkerCandidateBatch(...)` | `xa-mass-worker-runtime` | `WorkerCandidateSourceOwner`, `WorkerManager`, `WorkerSelectionOwner`, tests, guards | warm/cold diagnostic wrapper around candidate rows | Delete or collapse to row-returning `findWorkerCandidates(...)`. Current selection only needs candidate rows. |
| warm/cold candidate counters | `xa-mass-worker-runtime` tests/guards | `WorkerManagerTest`, guard tests | proof vocabulary for warm-pool behavior | Delete or rewrite with WTP-4. |

## Dependencies And Surfaces

| Module | Surface | Scope | Reason | Target |
| --- | --- | --- | --- | --- |
| `transport_api` | `com.xa.mass.transport.channel.WorkerPresence*` | main | Generic transport session event bridge to worker-runtime. | Deleted. |
| `transport_runtime` | `AdapterSessionEvidencePublisher` | main | Connected/heartbeat/disconnect writes endpoint lease evidence; current disconnect may emit narrow negative evidence. | Keep endpoint/session evidence write; no worker-runtime presence forwarding. |
| `sdk/xa-mass-embedded-sdk` | `WorkerRuntimePresenceIngress` | main | Former projection from transport events into worker reachability, heartbeat refresh, dispatch recovery, and block. | Deleted; replaced only by the accepted current-session disconnect block path. |
| `sdk/xa-mass-embedded-sdk` | `EngineConfig` delivery target resolver and starter fallback resolver | main | Assigned dispatch still needs selected-worker to adapter mailbox point lookup. | Keep; embedded default derives mailbox from worker registration plus local transport binding, while split engine-producer runtime requires explicit resolver. |
| `xa-mass-worker-runtime` | `WorkerCandidateSourceOwner` warm-pool path | main | Samples task-local warm candidates then merges cold candidates. | Delete warm path; acquire bounded candidates directly. |
| `xa-mass-worker-runtime` | `WorkerCandidateRuntime` batch method | main | Returns warm/cold metadata that selection does not need. | Rename/narrow to candidate rows. |
| `xa-mass-engine` | architecture guards | test | Some guards currently protect warm-pool/batch vocabulary. | Rewrite guards to forbid reintroduction after deletion. |
| `transport_runtime` | architecture guards | test | Guards currently mention delivery target/reachability and presence bridge. | Rewrite guards to protect the new deletion boundary. |

## Decisions

- Delete the transport-to-worker-runtime presence event bridge entirely.
  Transport connected, heartbeat, and keepalive are transport-local evidence.
- Keep a narrow current-session disconnect path as negative dispatch evidence.
  It must not carry a generic session event DTO and must only fire after the
  adapter/endpoint evidence owner accepts the disconnect as current.
- Keep selected-worker delivery target lookup as post-selection assigned
  delivery infrastructure. It is not scheduling input.
- Embedded starter default delivery target resolution uses worker registration
  plus local transport binding metadata, not transport session presence writes.
  Engine-producer split runtime must inject an explicit resolver.
- Keep `SelectedWorkerDeliveryTargetEvidence` with only `workerId`,
  `adapterMailboxKey`, and `expiresAtEpochMillis`; dispatch still uses
  `isDeliverable(now)`.
- Delete task-local warm candidate cache and its mutation port. Future warming
  belongs to score-band demand-lane/resource preallocation, not per-task worker
  candidate hints.
- Keep `WorkerCandidateBucketPolicy` and `candidateBucketKeys` as current BCA
  transition mechanics until the score-band acquire roadmap replaces them.
- Keep adapter-node/node-group binding conservative as topology/admin
  inventory for now. Do not use it as score-band or adapter-mailbox truth.
- Default-delete `WorkerRuntimeStateRecord` and `WorkerReadinessState` in the
  runtime-state slice unless a concrete current API owner is named before that
  slice starts.
