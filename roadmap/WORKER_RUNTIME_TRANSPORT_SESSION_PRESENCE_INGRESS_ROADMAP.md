# Worker Runtime Transport Session Presence Ingress Roadmap

Status: mainline implemented; optional deadline/index optimization deferred.

Current slice: `TSP-A`, `TSP-B`, and `TSP-C` complete; `TSP-D` deferred.

Related records:

- `roadmap/WORKER_RUNTIME_COMPOSITE_ELIGIBILITY_SET_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_ROADMAP.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `transport/AGENTS.md`
- `xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md`

## Purpose

Define the production boundary for using transport session evidence as worker
presence ingress without letting transport endpoint leases become worker
lifecycle, state-report, command-drain, capability, or dispatch-gate truth.

The selected direction is:

```text
transport session connect/disconnect/heartbeat
  -> worker presence ingress
  -> worker-runtime presence/reachability evidence
  -> WorkerReachabilityView / WorkerSchedulingViewRuntime
  -> scheduling stage-2 reachability check
```

The rejected direction is:

```text
endpoint-lease claim/release/refresh
  -> worker online/offline/heartbeat lifecycle event
  -> WorkerResourceRecord.statusName
  -> WORKER_STATE dispatch gate mutation
```

For websocket and socket push workers, the live connection is the natural
presence ingress. For polling workers, explicit worker online/heartbeat/offline
calls may also be presence ingress. In both cases, endpoint-lease state remains
transport session/endpoint evidence only; assigned
delivery feasibility is owned by handoff-private selected-worker consumer
evidence.

## Owner Review

Worker runtime owns:

- worker presence and reachability evidence,
- active presence session arbitration for worker reachability,
- registry-owned slot heartbeat refresh from valid worker data-plane
  connect/heartbeat observations,
- the `WorkerReachabilityView` and `WorkerSchedulingViewRuntime` evidence read
  consumed by engine scheduling,
- worker state-report interpretation, dispatch gates, command drain, capacity,
  admission, and group membership through their existing runtime owners.

Transport owns:

- protocol session connect/disconnect/heartbeat observation,
- endpoint lease claim, refresh, release, and delivery-feasibility lookup,
- adapter endpoint registries, delivery queues, dispatch outcomes, and
  transport node evidence.

Transport does not own:

- worker resource status,
- worker state-report truth,
- worker command drain truth,
- worker capability truth,
- worker scheduling eligibility,
- worker lifecycle projection from endpoint lease records.

Starter/SDK assembly owns:

- wiring session presence ingress into worker-runtime presence projection,
- preserving public worker data-plane APIs where they are real caller
  contracts,
- avoiding compatibility wrappers for superseded internal event names.

Engine owns:

- scheduling orchestration and stage-2 reachability consumption,
- retry or compensation after transport reports delivery failure.

Engine must not read adapter sessions or endpoint lease records to infer worker
reachability.

## Code Gaps Fixed By This Roadmap

The implemented mainline removes these former working-tree gaps:

- removed `TransportRouteLifecycleProjector`, which projected `TransportRouteOwnerRecord`
  currentness into `publishWorkerOnline`, `publishWorkerHeartbeat`, and
  `publishWorkerOffline`.
- socket and websocket session managers no longer call that projector after endpoint lease
  claim, refresh, or release.
- removed `WorkerHeartbeatProjectionListener`, which handled lifecycle-named events by
  rewriting `WorkerResourceRecord.statusName`, updating `lastHeartbeat`, and
  clearing or disabling `DispatchAvailabilitySource.WORKER_STATE`.
- polling SDK paths publish worker presence independent of whether
  `PullWorkerSession` endpoint lease claim/refresh/release says the caller is
  the current endpoint lease holder.
- public `PullWorkerSession#connect`, `heartbeat`, and `disconnect` now publish
  session presence directly instead of remaining an endpoint-lease-only side path.
- SDK inspection now derives reachability from worker-runtime reachability rather than
  `WorkerResourceRecord.statusName`.
- starter `EngineConfig` defaults reachability to the worker-runtime presence
  runtime, while tests may still opt into permissive reachability explicitly.
- reconnect replacement publishes a disconnect for the old session key after the
  new session is online, so old keys are removed without producing worker-level
  offline jitter.
- heartbeat refreshes only existing presence sessions; it cannot create or
  resurrect reachability for a disconnected or replaced session token.
- transport and engine architecture guards now cover the rejected indirect
  endpoint-lease-to-lifecycle chain and the new presence ingress boundary.
- worker state scheduling proof remains on the engine scheduling rows; presence
  does not write state or dispatch-gate truth.

This roadmap repaired those gaps by changing the mechanism, not by adding a
facade around the old route-owner projector.

## Boundary Decisions

### 1. Session Presence Is Ingress, Not Lifecycle Truth

Transport adapters may publish session presence evidence when a concrete
transport session connects, refreshes, or disconnects. The published fact is
about a session observation.

It is not a worker state report. It must not be named or consumed as
`WorkerStatus.ONLINE` / `WorkerStatus.OFFLINE` truth.

Target internal event shape:

```text
WorkerSessionPresenceEvent
  workerId
  adapterId
  routeKey
  sessionToken
  eventType: CONNECTED | HEARTBEAT | DISCONNECTED
  observedAtMillis
  reason
  traceId
```

Presence currentness is keyed by:

```text
PresenceSessionKey = workerId + adapterId + sessionToken
```

`routeKey` remains opaque diagnostic metadata and must not participate in worker
presence identity. `sessionToken` is not a global namespace by itself. The event
must not carry or expose endpoint lease metadata.

### 2. Replace Generic Lifecycle-Named Channel Internals

The former `WorkerSystemEventChannel` method names
`publishWorkerOnline`, `publishWorkerHeartbeat`, and
`publishWorkerOffline` are too easy to consume as lifecycle truth.

The internal target is a presence-specific ingress such as:

```text
WorkerPresenceIngress
  sessionConnected(WorkerSessionPresenceEvent)
  sessionHeartbeat(WorkerSessionPresenceEvent)
  sessionDisconnected(WorkerSessionPresenceEvent)
```

This is a real protocol seam because concrete adapters need a transport-neutral
way to report session evidence while worker runtime owns the projection. Do not
keep the old channel as a parallel compatibility path for internal callers.
Update in-repo callers directly.

Public SDK worker data-plane methods may keep caller-facing names like
`workerOnline`, `workerHeartbeat`, and `workerOffline` only if their docs and
implementation make clear they are presence ingress, not worker state-report or
capability truth.

### 3. Endpoint Lease Store Remains Delivery Feasibility Only

Adapters write `TransportEndpointLeaseStore` for endpoint/session feasibility
evidence. Assigned task delivery still finds concrete consumers through the
handoff-private `DeliveryCommandConsumerRegistry`, not through endpoint lease
lookup.

No code path may use endpoint lease claim/refresh/release success as the
condition for worker presence projection. Endpoint lease currentness can inform
whether an endpoint/session is feasible. Worker-runtime presence currentness
decides whether a worker is reachable.

Valid adapter session flow:

```text
session observed
  -> publish WorkerSessionPresenceEvent
  -> write or refresh TransportEndpointLeaseStore for endpoint feasibility
```

The order may be adapter-specific for retry safety, but the two writes are
owned separately and either one failing must not be interpreted as the other
truth.

The guard target is data dependency, not file-level co-existence. A session
manager may both publish presence and write endpoint lease evidence. It must not:

- pass `TransportEndpointLeaseMetadata` into the presence event or presence runtime,
- wrap `sessionConnected` / `sessionHeartbeat` / `sessionDisconnected` inside a
  `claimEndpointLease` / `refreshEndpointLease` / `releaseEndpointLease` currentness
  check,
- use endpoint lease write success or failure as the condition for presence
  publication.

### 4. Presence Does Not Mutate WORKER_STATE Or WorkerResource Status

Session presence must not:

- write `WorkerResourceRecord.statusName`,
- clear `DispatchAvailabilitySource.WORKER_STATE`,
- disable `DispatchAvailabilitySource.WORKER_STATE`,
- clear command drain,
- update worker capability truth,
- register or unregister workers.

`WORKER_STATE` remains the worker state-report / drain owner. Worker command
availability remains the worker command owner. Capability remains
WorkerGroup/registration capability truth.

Connected and heartbeat observations may also refresh registry-owned slot
heartbeat freshness, because CES Stage-1 source eligibility depends on the
worker-runtime slot deadline. That write is not worker status, state-report,
dispatch gate, command, capability, or endpoint-lease truth.

Presence may otherwise update only the worker-runtime presence/reachability
evidence that backs `WorkerReachabilityView`.

### 5. Reconnect Replacement Must Not Produce Worker Offline Jitter

For a worker with an active current session, a replacement connection must not
cause a worker-level `OFFLINE -> ONLINE` transition.

Required semantics:

- connect for the new session installs or refreshes the current presence token,
- disconnect for the replaced old session is either not emitted as worker-level
  presence or is rejected as stale by worker-runtime presence currentness,
- endpoint lease release for the old connection must not revoke a newer
  endpoint lease record,
- disconnect closes only the matching `PresenceSessionKey`,
- the worker remains reachable while the active non-expired session set is
  non-empty.

Diagnostics may record a stale session disconnect, but scheduling and worker
inspection must not observe a temporary worker-offline state.

### 6. Stage-2 Reads Reachability; Stage-1 Remains Slot Lifecycle

`WorkerSchedulingCandidateEnumerator` already consumes reachability through
`WorkerSchedulingViewRuntime#getWorkerReachability(...)`. This roadmap should
make that source production-owned.

Stage-1 candidate acquisition/source guard stays on worker-runtime slot
lifecycle evidence: group membership, heartbeat/deadline where registry-owned,
dispatch gate, removing, and reserve validation.

Do not move endpoint lease evidence into stage-1 eligibility. If stage-1 needs a
group-scoped reachable-worker set later, create a worker-runtime-owned
`WORKER_PRESENCE` / `WORKER_REACHABILITY` projection with deadline semantics.
That is a later performance slice, not a prerequisite for this roadmap's first
implementation.

## Do Not Start With

Do not start by keeping `TransportRouteLifecycleProjector` and moving it behind
another bridge/facade. That preserves the wrong owner decision.

Do not start by making `WorkerHeartbeatProjectionListener` write less status
while still using endpoint lease records as lifecycle evidence. That hides the
problem and leaves the same boundary leak.

Do not start by adding Redis key families or ZSET deadlines. First establish the
owner contract and in-process proof that presence, state, command, capability,
and endpoint lease evidence cannot overwrite each other.

Do not split the first executable slice into "delete old projector now, add
presence runtime later". That creates a reachability write-source gap for SDK
inspection and engine stage-2.

Do not implement guards that forbid a session manager from both publishing
presence and writing endpoint-lease state. The invalid
edge is endpoint-lease write result driving presence, not
session-flow co-location.

## Implementation Slices

### TSP-A - Minimal Presence Owner Cutover

Status: complete.

Goal: replace the wrong endpoint-lease-to-lifecycle path with a real
worker-runtime presence owner in one compileable, verifiable slice. This slice
must not be split into "remove old writes" and "add new reachability source"
sub-slices.

Actions:

- Introduce `WorkerSessionPresenceEvent`,
  `PresenceSessionKey(workerId, adapterId, sessionToken)`, and
  `WorkerPresenceIngress`.
- Add an in-memory worker-runtime presence runtime that owns active session
  state and derives `WorkerReachabilityState`.
- Add a worker-runtime slot heartbeat refresh path for connected/heartbeat
  observations so CES Stage-1 deadline eligibility has a current worker-runtime
  source without restoring status/gate projection.
- Wire starter/embedded runtime so production assembly uses the presence runtime
  as the default `WorkerReachabilityView`. Test harnesses may still opt into
  permissive reachability explicitly.
- Make `WorkerSchedulingViewRuntime#getWorkerReachability(...)` and
  `MassSdkApplication#isWorkerReachable(...)` / `listReachableWorkerIds()` read
  the same worker-runtime presence source instead of
  `WorkerResourceRecord.statusName` or transport endpoint lease lookup.
- Delete `TransportRouteLifecycleProjector` and remove all projector wiring
  from transport adapter bootstrap, socket session manager, websocket session
  manager, polling/session paths, and tests.
- Replace or remove `WorkerHeartbeatProjectionListener` as a status/gate
  projection. Presence consumers may refresh only presence/reachability state.
- Move websocket, socket, and polling worker paths to publish session presence
  from session observation. Route-owner claim/refresh/release still runs for
  delivery feasibility, but its result is not the predicate for presence
  publication.
- Ensure polling data-plane `workerOnline`, `workerHeartbeat`, and
  `workerOffline` publish presence regardless of endpoint lease store success;
  endpoint lease failure is delivery-feasibility evidence only.
- Add first-pass guards that forbid endpoint lease results from driving presence:
  no `TransportEndpointLeaseMetadata` in presence payload/projection, no presence
  publication inside endpoint lease currentness checks, and no endpoint lease write
  result used as a condition for `sessionConnected`, `sessionHeartbeat`, or
  `sessionDisconnected`.
- Add first-pass guards that forbid presence projection from mutating
  `WorkerResourceRecord.statusName`, `WorkerDispatchGateRuntime`,
  `DispatchAvailabilitySource.WORKER_STATE`, worker command drain, or worker
  capability owners.

Acceptance:

- SDK inspection, server/runtime inspection through SDK paths, and engine
  stage-2 read the same worker-runtime reachability source.
- A current active presence session makes the worker reachable.
- Presence online/heartbeat does not clear state-report drain or command drain.
- Presence online/heartbeat refreshes registry-owned slot heartbeat but does not
  write worker resource status or dispatch-gate truth.
- Presence offline/disconnect does not write worker state report, worker
  resource status, command drain, or capability truth.
- Polling presence publication is not swallowed when endpoint lease write fails.
- Public `PullWorkerSession#connect`, `heartbeat`, and `disconnect` participate
  in the same presence ingress contract as `workerOnline`, `workerHeartbeat`,
  and `workerOffline`.
- SDK facade `workerOnline`, `workerHeartbeat`, and `workerOffline` use
  `PullWorkerSession` as the single polling-session presence path and do not
  also call lower-level `publishWorkerSession*` helpers.
- Socket and websocket replacement do not expose worker-level offline jitter.
- Transport selected-worker delivery feasibility still uses
  `DeliveryCommandConsumerRegistry` handoff evidence written by
  adapter/session ingress. Route-owner or endpoint lease state remains
  session/endpoint evidence and must not be restored as assigned-delivery
  lookup truth.

### TSP-B - Multi-Session And Staleness Hardening

Status: complete.

Goal: make the presence owner robust across adapter families, reconnect races,
and stale disconnects after the minimal cutover lands.

Actions:

- Define active worker reachability as active non-expired
  `PresenceSessionKey` set size greater than zero.
- Ensure disconnect closes only the matching `PresenceSessionKey`.
- Prove cross-adapter coexistence: polling and websocket sessions for the same
  worker do not overwrite each other.
- Prove same-adapter reconnect: old session disconnect cannot close the newer
  session.
- Add optional diagnostic snapshots for stale disconnects without feeding them
  into scheduling eligibility.

Acceptance:

- reachability remains online while any active session is current,
- stale heartbeat cannot create or resurrect presence and cannot refresh slot
  heartbeat for an unaccepted session observation,
- stale disconnect closes only the matching key when present and otherwise does
  not create worker-level offline evidence,
- routeKey remains diagnostic metadata and never becomes presence identity.

### TSP-C - Full Guards, Proof, And Owner Docs

Status: complete.

Goal: prevent the boundary from regressing and make the implemented behavior
the documented owner truth.

Actions:

- Expand architecture guards to cover indirect endpoint-lease-to-presence data
  dependencies in adapters, transport runtime, SDK worker session code, and
  starter assembly.
- Restore a strong worker state scheduling proof: draining and command-drained
  workers must be excluded with explicit gate/state evidence, not merely absent
  from unrelated records.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `xa-mass-engine/README.md`,
  `xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md`, and
  `doc/PROOF_REGISTRY.md` after implementation lands.
- Link WES as the follow-up owner for server/frontend/SDK diagnostic vocabulary
  cleanup; do not mix external surface renaming into this runtime cutover.

Acceptance:

- guards fail on the rejected endpoint-lease-to-lifecycle chain,
- guards do not fail on valid session flow that both publishes presence and
  writes endpoint lease delivery evidence,
- docs describe implemented behavior only,
- proof registry distinguishes transport endpoint lease delivery feasibility from
  worker-runtime reachability.

### TSP-D - Optional Deadline-Aware Presence Index

Status: deferred.

Goal: optimize reachability reads only after the owner boundary is stable.

This slice is explicitly deferred.

Possible actions:

- Add a worker-runtime-owned presence deadline index.
- Use a ZSET only behind `WorkerPresenceRuntime`; do not expose Redis key shapes
  outside runtime implementation docs and guards.
- Optionally maintain group-scoped reachable-worker sets if stage-1 needs a
  bounded read path.

Acceptance before starting:

- TSP-A through TSP-C are complete,
- hot-path read cost is measured,
- the caller contract is a worker-runtime API, not a Redis key or endpoint lease
  lookup,
- bucket rules and safety policies have an owner decision.

## Verification

Minimum commands for the first implementation slices:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime -am "-Dtest=TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl transport/socket-adapter -am "-Dtest=SocketSessionManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl transport/websocket-adapter -am "-Dtest=ServerSessionManagerShutdownTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=InMemoryWorkerPresenceRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimePresenceIngressTest,WorkerRuntimeSelectionIntegrationTest,PullWorkerSessionTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-engine -am "-Dtest=WorkerStateReportSchedulingIntegrationTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,EngineSchedulingCoreArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -B -pl platform_infra/mass-runtime-redis -am test
.\mvnw.cmd -B -pl xa-mass-server -am "-Dtest=ExternalWorkerParitySuite,MemoryRuntimeLateReplayE2eScenario,RedisRuntimeLateReplayE2eScenario" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=CrawlerPullWorkerSdkRegistrationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -B -pl xa-mass-testing -am test
git diff --check
```

Executed on 2026-06-13 for the mainline implementation:

- `wsl redis-cli ping` -> `PONG`
- `.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=InMemoryWorkerPresenceRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimePresenceIngressTest,WorkerRuntimeSelectionIntegrationTest,PullWorkerSessionTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl transport/transport_runtime -am "-Dtest=TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl transport/socket-adapter -am "-Dtest=SocketSessionManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl transport/websocket-adapter -am "-Dtest=ServerSessionManagerShutdownTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl xa-mass-engine -am "-Dtest=WorkerStateReportSchedulingIntegrationTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,EngineSchedulingCoreArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -am test` -> pass
- `.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=ExternalWorkerParitySuite,MemoryRuntimeLateReplayE2eScenario,RedisRuntimeLateReplayE2eScenario" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `WorkerRuntimeSelectionIntegrationTest` was retargeted to explicit worker session presence after `xa-mass-testing -am test` exposed the old permissive-reachability assumption
- `.\mvnw.cmd -q -pl xa-mass-testing -am test` -> pass

Executed on 2026-06-13 for review-gap repairs:

- `.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=InMemoryWorkerPresenceRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimePresenceIngressTest,PullWorkerSessionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimeSelectionIntegrationTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl transport/transport_runtime -am "-Dtest=TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl transport/socket-adapter -am "-Dtest=SocketSessionManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl transport/websocket-adapter -am "-Dtest=ServerSessionManagerShutdownTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=CrawlerPullWorkerSdkRegistrationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl xa-mass-engine -am "-Dtest=WorkerStateReportSchedulingIntegrationTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,EngineSchedulingCoreArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass
- `.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -am test` -> pass
- `.\mvnw.cmd -q -pl xa-mass-testing -am test` -> pass
- `git diff --check` -> pass; only line-ending normalization warnings were reported.

Executed on 2026-06-13 for SDK facade double-publish repair:

- `.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=MassSdkTest#workerLifecycleFacadePublishesPresenceOnceThroughPullWorkerSession,PullWorkerSessionTest,WorkerRuntimePresenceIngressTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> pass

When Redis-backed runtime behavior is touched, verify local Redis first:

```powershell
wsl redis-cli ping
```

Expected response:

```text
PONG
```

## Completion Criteria

The roadmap is complete only when all of these are true:

- no endpoint lease claim, refresh, release result, owner record, or endpoint lease
  Redis key can drive worker lifecycle, worker reachability, or slot heartbeat
  directly,
- transport session evidence reaches worker runtime through a
  presence-specific ingress contract,
- worker-runtime presence evidence backs `WorkerReachabilityView`,
- accepted worker data-plane connected/heartbeat observations refresh
  registry-owned slot heartbeat without writing worker resource status or
  dispatch gates,
- public polling `PullWorkerSession` lifecycle methods publish the same
  presence evidence as the higher-level worker data-plane methods,
- higher-level SDK worker lifecycle methods do not publish presence a second
  time outside `PullWorkerSession`,
- heartbeat cannot create or resurrect worker reachability, nor refresh slot
  heartbeat, for a stale or disconnected session token,
- reconnect replacement cannot produce worker-level offline jitter,
- presence online/heartbeat/offline cannot mutate `WORKER_STATE`,
  `WORKER_COMMAND`, worker resource status, or capability truth,
- SDK inspection and engine scheduling read worker-runtime reachability instead
  of transport endpoint lease evidence,
- guards cover direct and indirect regressions,
- owner docs and proof registry entries describe the implemented behavior,
- optional Redis/deadline indexes, if added, are hidden behind worker-runtime
  contracts and not exposed as external interface truth.

## Out Of Scope

- Server, frontend, SDK diagnostic vocabulary cleanup owned by WES.
- Attribute indexing and policy-defined worker metadata indexes.
- Bucket compaction, Redis key physical layout, and security policy tuning.
- Worker command protocol expansion.
- Worker state-report protocol expansion.
- Capability self-report protocol expansion.
- Transport delivery retry policy changes unrelated to selected-worker delivery
  feasibility.

## Open Decisions

- Whether TSP-D needs a Redis deadline/index projection after in-memory cutover
  metrics prove a hot-path need.
- Whether public SDK `workerOnline` / `workerHeartbeat` / `workerOffline`
  method names are acceptable as data-plane presence ingress names. If changed,
  that should be coordinated with WES rather than mixed into TSP-A.
- How much diagnostic history to retain for stale session disconnects. The
  scheduling requirement is clear: stale disconnect must not change
  reachability.
