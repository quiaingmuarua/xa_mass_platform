# Worker Runtime Boundary Convergence Roadmap

Status: proposed convergence roadmap.

This roadmap converges the ownership boundary between:

- `transport/transport_runtime`
- `xa-mass-worker-runtime`
- `xa-mass-engine`

The immediate driver is transport presence / worker runtime Redis key confusion,
but the boundary problem is broader than Redis key names. The core question is:

```text
who owns worker declaration, worker runtime evidence, transport route ownership,
and scheduling consumption?
```

This roadmap must land before treating
[TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md](./TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md)
as executable implementation work. Redis transport key convergence is a child
slice of this owner-boundary decision, not the first source of truth.

Read with:

- [AGENTS.md](../AGENTS.md)
- [doc/AGENT_BASELINE.md](../doc/AGENT_BASELINE.md)
- [doc/INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [xa-mass-worker-runtime/README.md](../xa-mass-worker-runtime/README.md)
- [xa-mass-worker-runtime/CONTRACTS.md](../xa-mass-worker-runtime/CONTRACTS.md)
- [transport/AGENTS.md](../transport/AGENTS.md)
- [transport/TRANSPORT_BOUNDARY_BASELINE.md](../transport/TRANSPORT_BOUNDARY_BASELINE.md)
- [xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [xa-mass-engine/doc/baseline/SCHEDULING_KERNEL_BASELINE.md](../xa-mass-engine/doc/baseline/SCHEDULING_KERNEL_BASELINE.md)
- [doc/archive/core/2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md](../doc/archive/core/2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md)

## Problem

Current docs correctly say that worker runtime state is split across separate
owners:

- transport owns volatile route-owner presence and connectivity evidence,
- worker-runtime owns worker declarations, WorkerGroup capability, candidate
  source, dispatch gates, admission, reservation, locks, load, and bounded
  worker-control projections,
- engine owns task scheduling orchestration, rule/rank/allocation, dispatch
  binding, compensation, and result convergence.

Current code mostly follows this dependency direction, but several shapes still
make the boundary easy to misread:

1. `RedisWorkerPresenceStore` writes the same `WorkerPresence` fact into both
   `worker:{workerId}` and `route-presence:{adapterId}\0{routeKey}`. That is a
   physical duplicate truth risk, not just key clutter.
2. `WorkerPresenceStore#findOwners(workerId)` defaults to
   `listActivePresences().stream().filter(...)`, so the route-owner read path is
   not guaranteed to be bounded even though Redis already writes
   `worker-routes:{workerId}`.
3. `MassApplication` still falls back from `findOwners(workerId)` to
   `getPresence(workerId)` for engine reachability. That keeps the compatibility
   worker projection close to scheduling truth.
4. `WorkerStatusEventListener` still refreshes heartbeat evidence through
   `WorkerResourceRuntime.updateWorker(...)`. Declaration storage does not
   persist heartbeat, but the call shape still reads like worker-row online
   truth.
5. `WorkerSchedulingView` lives in `xa-mass-engine`. This is acceptable as an
   engine scheduling candidate view, but it must stay explicitly downstream of
   worker-runtime evidence and must not become a second worker state owner.
6. Transport delivery route keys are still resolved as `workerId` by default.
   That keeps delivery queue ownership, presence lookup, and scheduling identity
   too close together.
7. Presence tests still protect "one worker, multiple active route owners".
   That is old transport semantics, not the target if workerId is already the
   smallest schedulable execution identity.

## Dependency Model

Do not describe the code dependency as:

```text
transport -> worker-runtime -> engine
```

That is only partly true for runtime evidence flow, not compile ownership.

Current intended compile-time dependencies:

```text
xa-mass-engine
  -> xa-mass-worker-runtime contracts
  -> platform_infra/mass-runtime-api

transport/transport_runtime
  -> xa-mass-worker-runtime query contracts
  -> transport_api contracts

xa-mass-worker-runtime
  -> platform_infra/mass-runtime-api

transport/transport_runtime must not depend on xa-mass-engine.
xa-mass-worker-runtime must not depend on xa-mass-engine or transport adapters.
xa-mass-engine must not depend on transport runtime implementation.
```

Current intended runtime evidence flow:

```text
transport adapters
  -> WorkerPresenceStore route-owner records
  -> WorkerReachabilityView assembled by SDK/server runtime composition
  -> xa-mass-worker-runtime WorkerSchedulingViewRuntime evidence
  -> xa-mass-engine RuntimeWorkerSelection prefilter / rule / rank / reserve
  -> engine dispatch binding
  -> transport route selection after concrete worker assignment
```

This distinction matters. Transport produces reachability evidence, but it does
not own worker registration, capability, dispatch gate, admission, or scheduling.
Engine consumes evidence and calls admission, but it does not own transport
presence or low-level worker registry state.

## Current Code Facts

- `WorkerManager` implements `WorkerResourceRuntime`,
  `WorkerCandidateRuntime`, `WorkerSchedulingViewRuntime`,
  `WorkerAdmissionRuntime`, `WorkerDispatchGateRuntime`,
  `WorkerReportRuntime`, and `WorkerWarmHintRuntime`.
- `WorkerManager` reads transport reachability through
  `WorkerReachabilityView`, not through transport sessions.
- `WorkerResourceOwner` writes declaration rows to `WorkerDeclarationStore` and
  projects current worker slots into `WorkerRegistry`.
- `WorkerDeclarationRecord` intentionally excludes heartbeat, online/offline
  state, dispatch gates, reservations, leases, and worker-level capability
  hints.
- `WorkerRuntimeStateRecord` is a current runtime-state view assembled from
  registry, reachability, heartbeat freshness, dispatch gate, and admission
  evidence.
- `WorkerCandidateIndex` acquires group/bucket-bounded candidates from
  `WorkerRegistry` and source-guards each candidate against the current slot.
- `WorkerAdmissionOwner` translates `WorkerRegistry` reserve/confirm/release
  and exclusive lease primitives into worker-runtime admission results.
- `RuleBasedTaskWorkerMatchingStrategy` resolves scheduling policy, asks
  `WorkerCandidateRuntime` for candidate rows, materializes engine-local
  `WorkerSchedulingView` evidence, prefilters dispatch gate / reachability /
  lock, evaluates rules, ranks, and then calls `WorkerAdmissionRuntime`.
- `TransportRuntimeRegistry` reads worker declaration/adapter hints through
  `WorkerResourceQueryRuntime`; it does not own worker declaration truth.
- `WorkerDispatchRouteSelector` selects a current route owner only after engine
  has already produced concrete `TaskDispatchBinding` values.
- `NodeTargetedTaskDispatchSubmitter` writes post-assignment dispatch batches to
  route-owner node inboxes and compensates unresolved route owners through
  engine-owned dispatch failure handling.
- `RedisWorkerRegistry` stores canonical worker runtime state in
  `group:{groupId}:slots`; candidate buckets, heartbeat zsets, worker group
  maps, and bucket-membership keys are indexes or cleanup aids.
- `RedisWorkerPresenceStore` currently stores route-owner presence through
  multiple overlapping key families:
  `route-presence:*`, `worker:*`, `route:*`, `worker-routes:*`, `routes`, and
  `workers`.

## Boundary Decision

Use three distinct owner surfaces.

```text
Transport route-owner presence
  Owner: transport_runtime
  Contract: WorkerPresenceStore / WorkerDispatchRouteOwnerView
  Subject: canonical routeKey
  RouteKey source: platform-minted token derived from workerGroupId + workerId
  Delivery evidence: adapterId, transportNodeId, connectionId,
                     ONLINE/STALE/OFFLINE lease evidence, updatedAt
  Correlation evidence: workerId and optional decoded subject parts for
                        operator/debug output only
  Purpose: worker reachability and post-assignment route delivery
  Non-owner: worker declaration, WorkerGroup capability, admission, reservation,
             dispatch gate, task retry/finality

Worker runtime state
  Owner: xa-mass-worker-runtime
  Contracts: WorkerResourceRuntime, WorkerCandidateRuntime,
             WorkerSchedulingViewRuntime, WorkerAdmissionRuntime,
             WorkerDispatchGateRuntime, WorkerReportRuntime
  Facts: worker declaration, WorkerGroup capability, AdapterNode/NodeGroupBinding,
         candidate source, reachability read evidence, dispatch gates,
         load/reservation/capacity/lock, bounded worker state projection
  Non-owner: protocol sessions, route owner storage, task lifecycle,
             dispatch binding, result convergence

Engine scheduling and assignment
  Owner: xa-mass-engine
  Contracts: TaskWorkerMatchingStrategy, AssignmentAllocationPolicy,
             TaskWorkerAssignListener, SimpleTaskDispatchBinder,
             WorkerDispatchResourcePolicy, WorkerDispatchResourceReleaser
  Facts: task scheduling orchestration, prefilter/rule/rank/allocation,
         binding, compensation, result/terminal convergence
  Non-owner: transport online truth, worker declaration truth, Redis worker
             registry physical truth
```

The correct runtime chain is:

```text
worker-runtime candidate evidence (workerGroupId + workerId)
  -> platform routeKey minting
  -> routeKey presence subject
  -> worker reachability evidence
  -> engine scheduling use
```

The correct route-delivery chain is:

```text
engine assignment binding
  -> binding workerGroupId + workerId
  -> platform routeKey minting
  -> current adapter / transport-node / connection owner
  -> transport inbox
```

Do not collapse these two chains into one owner.

## Hard Rules

1. `transport_runtime` must not import or call engine internals.
2. `xa-mass-worker-runtime` must not import transport adapter implementations or
   engine implementation classes.
3. `xa-mass-engine` must not import `RedisWorkerPresenceStore`,
   `InMemoryWorkerPresenceStore`, transport adapter sessions, or transport Redis
   keyspace classes.
4. Engine matching must not import or consume `WorkerRegistry`, `WorkerSlot`,
   `WorkerMeta`, `ReserveResult`, or `ReserveStatus` as strategy contracts.
5. Transport route-owner state must not become worker declaration truth.
6. Worker declaration updates must not be the canonical path for online/offline
   or heartbeat churn.
7. `WorkerPresenceStore#getPresence(workerId)` is a compatibility/operator
   projection. Scheduling reachability and dispatch route selection must use
   route-owner evidence keyed by canonical `routeKey`.
8. Transport presence subject identity is canonical `routeKey`.
   `workerGroupId + workerId` is the platform minting input. Transport may
   treat the token as opaque unless a named route-key codec is explicitly part
   of the contract.
9. `adapterId`, `transportNodeId`, and `connectionId` are delivery-owner
   evidence, not scheduling identity. `routeKey` is also not a caller-provided
   scheduling policy; it is derived from the selected worker subject.
10. A presence subject has at most one active dispatch delivery owner. Reconnect,
   adapter change, or route takeover replaces the owner; stale heartbeat/offline
   may apply only when `connectionId` still matches.
11. One active delivery owner does not mean one active task. Task concurrency,
    reservation, exclusive locks, and capacity remain worker-runtime admission
    truth.
12. Production Redis route-owner lookup must be bounded by canonical `routeKey`.
    It must not rely on all-route scans.
13. Presence owner lookup, dispatch envelope route-key resolution, and adapter
    poll/drain route keys must converge to the same canonical `routeKey`.
14. No Redis convergence slice may keep two writable transport presence truths
    for the same route-owner fact.
15. No compatibility bridge may be added to keep old and new transport presence
    storage shapes live indefinitely.
16. `WorkerSchedulingView` may remain engine-local only if it is documented and
    tested as a downstream scheduling candidate view, not a worker-runtime truth
    owner.
17. State-report values such as `DRAINING` / `AVAILABLE` may affect scheduling
    only through the worker-runtime dispatch gate policy path.
18. Transport `STALE` / `OFFLINE` is reachability evidence only. It must not
    mutate WorkerGroup capability, declaration, admission counters, or task
    lifecycle directly.
19. Redis proof tooling must not be built first over known-bad key families.
    Proof follows owner/key convergence; it does not freeze duplicate truth.

## Do Not Start With

Do not start by rewriting Redis keys, adding a broad
`WorkerRuntimeBoundaryService`, or making a compatibility bridge between old and
new presence records.

The first useful slice is owner/caller inventory plus canonical routeKey
read-path convergence. Physical Redis shape comes after the route-owner
contract is settled.

## WRB-0 Inventory And Owner Map

Goal: record the current owner/caller/key map before changing behavior.

Artifact:

- `WORKER_RUNTIME_BOUNDARY_CONVERGENCE_INVENTORY.md`

Scope:

1. Inventory production callers of:
   - `WorkerPresenceStore#findOwners`
   - `WorkerPresenceStore#getPresence`
   - `WorkerPresenceStore#listActivePresences`
   - `WorkerPresenceStore#isWorkerOnline`
   - `WorkerReachabilityView`
   - `WorkerStatusEventListener`
   - `WorkerResourceRuntime#updateWorker`
   - `WorkerCandidateRuntime`
   - `WorkerSchedulingViewRuntime`
   - `WorkerAdmissionRuntime`
   - `WorkerDispatchGateRuntime`
   - `WorkerRegistry`
   - `TaskDispatchBinding#workerId`
   - `TaskDispatchBinding#workerGroupId`
   - `TransportRouteKeyResolver`
   - adapter poll/drain route-key usage
   - worker group lookup during post-assignment route selection
2. Inventory Redis transport presence key families:
   - `route-presence:*`
   - `worker:*`
   - `route:*`
   - `worker-routes:*`
   - `routes`
   - `workers`
3. Inventory Redis worker runtime key families:
   - `group:{groupId}:slots`
   - `group:{groupId}:heartbeat:{shard}`
   - `group:{groupId}:bucket:{bucket}:workers`
   - `group:{groupId}:node:{node}:bucket:{bucket}:workers`
   - `group:{groupId}:worker:{workerId}:bucket-membership`
   - `worker:group`
   - `groups`
   - task active-worker indexes
4. Classify each fact as:
   - canonical truth,
   - derived index,
   - compatibility projection,
   - diagnostic-only,
   - residue.
5. Classify each read path as:
   - scheduling reachability,
   - dispatch route selection,
   - operator/SDK display,
   - cleanup,
   - test/support.
6. Record where `workerGroupId` is available on the dispatch path:
   - assignment/matching candidate evidence,
   - `TaskDispatchBinding`,
   - `WorkerResourceRecord`,
   - fallback worker-runtime lookup.
7. Record where `routeKey` is currently generated or consumed:
   - `TransportRouteKeyResolvers.workerId()`,
   - `TransportDispatchRouteContext`,
   - `TransportBinding#resolveRouteKey`,
   - adapter connection registration,
   - delivery queue poll/drain.

Acceptance:

1. Inventory proves the dependency model from code, not only from docs.
2. Every current transport presence key family has a named owner classification.
3. Every scheduling or dispatch route caller of `WorkerPresenceStore` is named.
4. Every route-owner path that can scan all active presences is named.
5. `WorkerStatusEventListener` is classified as mainline, compatibility
   residue, or removal target.
6. Redis worker runtime key families are classified separately from transport
   presence key families.

Suggested checks:

```powershell
rg -n "findOwners\\(|getPresence\\(|listActivePresences\\(|isWorkerOnline\\(" `
  transport sdk xa-mass-server xa-mass-engine xa-mass-worker-runtime `
  --glob '!**/target/**'

rg -n "WorkerReachabilityView|WorkerStatusEventListener|WorkerResourceRuntime|WorkerCandidateRuntime|WorkerSchedulingViewRuntime|WorkerAdmissionRuntime|WorkerDispatchGateRuntime|WorkerRegistry" `
  sdk xa-mass-server xa-mass-engine xa-mass-worker-runtime transport `
  --glob '!**/target/**'

rg -n "route-presence|worker-routes|routesKey|workersKey|workerKey\\(|routeKey\\(|routePresenceKey" `
  transport/transport_runtime/src/main/java `
  --glob '!**/target/**'

rg -n "TransportRouteKeyResolvers\\.workerId\\(|resolveRouteKey\\(|TransportDispatchRouteContext|drainEnvelopes\\(|pollEnvelopes\\(" `
  transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java `
  --glob '!**/target/**'

rg -n "groupSlotsHash|groupHeartbeatDeadlinesZset|groupCandidateBucket|workerBucketMembershipSet|taskActiveWorkersSet" `
  platform_infra/mass-runtime-redis/src/main/java `
  --glob '!**/target/**'
```

## WRB-1 Canonical RouteKey Read Path Convergence

Goal: make canonical `routeKey` the primary bounded subject for scheduling
reachability and post-assignment route delivery.

Scope:

1. Introduce or retarget the route-owner read contract so the current delivery
   owner can be read by canonical route subject:

```text
routeKey
```

2. Define the platform route-key minting contract:
   - input: selected `workerGroupId + workerId`,
   - output: canonical `routeKey`,
   - owner: platform/worker-runtime contract or a named route-key codec,
   - transport behavior: consume the token; do not reinterpret WorkerGroup
     scheduling semantics.
3. Treat current `WorkerPresenceStore#findOwners(workerId)` as transitional or
   compatibility surface unless it can be proven to resolve the same subject
   through canonical routeKey evidence without all-route scans.
4. Keep `listActivePresences()` as operator/support read only.
5. Remove engine reachability fallback that treats `getPresence(workerId)` as
   scheduling truth, unless inventory proves a bounded equivalent that remains
   route-owner derived.
6. Keep `getPresence(workerId)` as compatibility projection produced from the
   current presence subject.
7. Ensure `WorkerDispatchRouteSelector` consumes delivery owner evidence after
   engine has selected a concrete worker subject and the canonical routeKey has
   been minted.
8. Align three route-key consumers to the same canonical token:
   - presence owner lookup,
   - dispatch envelope route-key resolution,
   - adapter poll/drain route key.
9. If `TaskDispatchBinding` does not carry `workerGroupId`, route-key minting
   must either fail fast or perform a bounded worker-runtime lookup. It must not
   silently fall back to `workerId` route ownership.
10. Ensure missing, stale, or offline route owners produce engine-owned
   compensation/retry after assignment, not transport-owned rescheduling.
11. Remove target semantics that allow one workerId to have multiple active
   dispatch route owners. Multiple endpoint modes must be modeled as distinct
   workerIds if they are independently schedulable.
12. Preserve worker concurrency semantics: multiple task dispatch bindings may
   target the same canonical routeKey when worker-runtime admission/capacity
   allows them.

Acceptance:

1. Redis route-owner lookup for one presence subject is bounded by canonical
   `routeKey`.
2. Canonical routeKey is minted from `workerGroupId + workerId` or from an
   explicitly bounded worker-runtime lookup; missing group evidence does not
   fall back to raw `workerId` delivery.
3. Scheduling reachability no longer depends on a worker projection when
   route-owner evidence is absent.
4. Dispatch route selection has no fallback to worker declaration or projection
   online state.
5. `listActivePresences()` is not on the scheduling or route-selection
   mainline.
6. Reconnect/takeover still prevents stale heartbeat/offline from revoking a
   newer route owner.
7. Missing route owner after assignment compensates through the engine dispatch
   failure path.
8. There is at most one active delivery owner for a presence subject.
9. Multiple dispatch bindings can use the same routeKey when
   `WorkerAdmissionRuntime` and `WorkerRegistry` capacity allow it.
10. `adapterId` affects delivery channel selection only; it does not change
    candidate universe or scheduling identity.

Primary proof:

- transport runtime tests for canonical routeKey lookup, stale owner rejection,
  and reconnect/takeover replacement.
- node-targeted dispatch test proving unresolved route owner triggers
  compensation.
- transport delivery test proving presence lookup, envelope routeKey, and
  adapter poll/drain use the same canonical token.
- engine/starter integration test proving transport presence change changes
  scheduling reachability outcome through `WorkerReachabilityView`.

Support only:

- `rg` scans for scheduling callers of `listActivePresences()` or `getPresence()`.
- `rg` scans for `TransportRouteKeyResolvers.workerId()` and other raw
  workerId route-key residue.

Suggested checks:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -am `
  "-Dtest=RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest,WorkerDispatchRouteSelectorTest,NodeTargetedTaskDispatchSubmitterTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk,xa-mass-engine -am `
  "-Dtest=MassApplicationDistributedTransportTest,TaskSchedulingGateAndTargetingTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

rg -n "listActivePresences\\(|getPresence\\(" `
  sdk/xa-mass-embedded-sdk/src/main/java `
  transport/transport_runtime/src/main/java `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'

rg -n "TransportRouteKeyResolvers\\.workerId\\(|routeContext\\.workerId\\(|findOwners\\(worker\\.workerId\\(" `
  transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java `
  --glob '!**/target/**'
```

## WRB-2 Transport Presence Storage Shape Decision

Goal: choose a storage shape that matches canonical routeKey presence semantics
and avoids duplicate writable truth.

This stage owns the parent decision for
`TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md`.

Scope:

1. Decide the canonical transport presence fact:
   - chosen direction: canonical `routeKey` is the transport presence subject,
   - `workerGroupId + workerId` is routeKey minting input, not a transport-owned
     scheduling axis,
   - current delivery owner evidence is the subject value,
   - worker projection is derived compatibility/operator output only.
2. Decide physical Redis shape using the runtime worker lesson:
   - prefer aggregate owner records plus bounded indexes,
   - do not create one full canonical hash per worker plus one full canonical
     hash per route.
3. Use routeKey-sharded aggregate records as the first implementation
   candidate. This keeps transport from owning WorkerGroup semantics while
   avoiding one Redis key per worker route:

```text
{ns}:owner:{shard}
  HASH field = routeKey
  value = workerId, adapterId, transportNodeId, connectionId,
          state, leaseExpireAt, updatedAt

{ns}:deadline:{shard}
  ZSET member = routeKey
  score = leaseExpireAt
```

4. If a group-partitioned physical shape is later chosen, decoding must be
   supplied by the named route-key codec. Transport must not infer WorkerGroup
   scheduling semantics from the decoded parts.
5. Keep `adapterId`, `transportNodeId`, and `connectionId` inside the value or
   derived delivery indexes. They are not canonical Redis key subjects.
   `routeKey` may be a hash field or zset member, but it must not be embedded in
   NUL-delimited compound Redis key names.
6. Remove NUL-delimited key identity from new physical keys. If compound member
   tokens are needed, keep them as encoded values or explicitly parseable tokens,
   not shell-hostile Redis key names.
7. Define atomic mutation boundaries for:
   - mark online,
   - heartbeat refresh,
   - offline,
   - materialize stale,
   - prune expired,
   - route takeover.
8. Define cleanup indexes:
   - routeKey-shard deadline zset,
   - optional transport-node delivery-owner index,
   - optional adapter/transport-node sets.
9. Define cutover:
   - clean-runtime recreation is allowed for local/pre-release runtime Redis,
   - no rolling dual-write truth unless a later production migration decision
     explicitly proves why it is required.

Acceptance:

1. One canonical writable Redis owner exists for each canonical `routeKey`
   presence subject.
2. Worker projection is not writable truth beside subject presence truth.
3. Every derived index has a named cleanup owner and failure behavior.
4. Presence lookup by canonical `routeKey` remains bounded.
5. Presence cleanup by routeKey-shard deadline remains bounded.
6. No new key shape uses NUL-delimited key names.
7. `adapterId`, `transportNodeId`, and `connectionId` are not canonical key
   subjects.
8. The old key families have explicit removal conditions.
9. `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` is updated or
   superseded to reflect this decision before implementation starts.

## WRB-3 Worker Runtime Heartbeat And Status Residue

Goal: remove or narrow legacy paths that make worker declaration updates look
like online truth.

This stage is residue classification. It must not block WRB-1 or WRB-2 unless
inventory proves a current mainline reachability dependency on this listener.

Scope:

1. Decide the fate of `WorkerStatusEventListener`:
   - delete if no longer production mainline,
   - or rename/narrow it as process-local compatibility heartbeat projection.
2. Ensure worker declaration writes cannot become heartbeat or online/offline
   truth.
3. Ensure `WorkerResourceRuntime#updateWorker(...)` is not the default path for
   heartbeat refresh.
4. Keep `WorkerRuntimeStateRecord` as read evidence assembled from runtime
   owners, not persisted declaration truth.
5. Keep `statusName` display compatibility out of scheduling predicates.

Acceptance:

1. A worker online/offline event cannot update declaration truth as online
   authority.
2. Any remaining heartbeat refresh path is documented as compatibility residue
   or worker-registry evidence refresh, not transport reachability truth.
3. Scheduling rejection for stale/offline worker still comes from route-owner
   reachability and worker-registry admission checks.
4. `WorkerDeclarationRecord` remains free of heartbeat, online/offline,
   reservation, lease, and dispatch-gate fields.

Suggested checks:

```powershell
rg -n "WorkerStatusEventListener|updateWorker\\(withHeartbeat|setLastHeartbeat|setStatus\\(" `
  sdk xa-mass-worker-runtime xa-mass-engine transport `
  --glob '!**/target/**'

.\mvnw.cmd -pl xa-mass-worker-runtime,xa-mass-engine,sdk/xa-mass-embedded-sdk -am `
  "-Dtest=WorkerManagerTest,WorkerAdmissionOwnerTest,TaskWorkerEligibilityTest,MassApplicationDistributedTransportTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WRB-4 Engine Scheduling View Ownership

Goal: keep engine scheduling consumption explicit without turning engine into a
worker-state owner.

Scope:

1. Document `WorkerSchedulingView` as an engine-local scheduling candidate view
   built from worker-runtime evidence.
2. Ensure `WorkerSchedulingView` does not import or materialize transport
   session/storage/keyspace types.
3. Ensure engine matching code consumes only:
   - `WorkerCandidateRuntime`,
   - `WorkerSchedulingViewRuntime`,
   - `WorkerAdmissionRuntime`,
   - engine-owned policies/resolvers/rankers.
4. Keep low-level `WorkerRegistry` primitives out of engine strategy code.
5. Classify `DispatchAvailabilitySource` usage in engine control policy:
   - current tolerated cross-boundary enum because `WorkerDispatchGateRuntime`
     exposes it,
   - not a reason to import registry slots/reserve results in matching.
6. Decide whether any future worker scheduling read-model type should move to
   worker-runtime. Do not move it in this roadmap unless it removes a real
   owner ambiguity and avoids churn.

Acceptance:

1. Engine matching has no transport runtime implementation dependency.
2. Engine matching has no direct `WorkerRegistry`, `WorkerSlot`, `WorkerMeta`,
   `ReserveResult`, or `ReserveStatus` dependency.
3. `WorkerSchedulingView` remains downstream of worker-runtime evidence.
4. Prefilter responsibility is documented as RuntimeWorkerSelection consumption
   of worker-runtime / transport evidence, not engine ownership of worker state.
5. Assignment records and trace snapshots may carry scheduling evidence, but
   they do not become worker runtime truth.

Suggested checks:

```powershell
rg -n "RedisWorkerPresenceStore|InMemoryWorkerPresenceStore|WorkerPresenceStore|WorkerRegistry|WorkerSlot|WorkerMeta|ReserveResult|ReserveStatus" `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WRB-5 Integrated Boundary Proof

Goal: prove the boundary through behavior, not source-shape assertions.

Required proof scenarios:

1. Transport route owner changes reachability outcome:
   - worker declaration and registry slot remain constant,
   - candidate `workerGroupId + workerId` mints the same canonical routeKey,
   - route owner ONLINE for that routeKey allows scheduling,
   - route owner STALE/OFFLINE or missing for that routeKey blocks scheduling.
2. Worker state report changes dispatch gate outcome:
   - `WorkerStateReport(DRAINING)` enters through `WorkerControlService`,
   - dispatch gate rejects scheduling,
   - `AVAILABLE` clears only `WORKER_STATE`,
   - worker becomes schedulable again if transport reachability and capacity
     still allow it.
3. Worker occupancy/admission changes outcome:
   - same route owner and declaration,
   - capacity/full/reservation/lock perturbation changes assignment result.
4. Route owner missing after assignment triggers dispatch submit compensation:
   - engine already bound worker,
   - binding evidence mints canonical routeKey,
   - route owner unavailable,
   - compensation releases worker resources and re-enters retry path.
5. Route-owner replacement for one presence subject:
   - reconnect or adapter change replaces the current delivery owner for
     canonical `routeKey`,
   - stale owner cannot revoke the newer owner,
   - worker projection does not override route-owner view.
6. One delivery owner does not cap task concurrency:
   - same canonical routeKey,
   - multiple `TaskDispatchBinding` values are produced only when
     worker-runtime capacity/admission allows,
   - transport route owner selects delivery target but does not decide capacity.
7. Candidate source remains group/bucket bounded:
   - no event-code all-worker scan,
   - explicit worker group selector controls candidate universe.

Acceptance:

1. Proof starts from public/module owner entry points, not private field-copy
   tests.
2. At least one proof crosses all three modules through SDK/server assembly or
   equivalent runtime composition:

```text
transport presence -> worker-runtime reachability/evidence -> engine assignment
```

3. At least one proof crosses the post-assignment route path:

```text
engine binding -> canonical routeKey -> transport route selection
  -> transport inbox or compensation
```

4. At least one proof verifies that envelope routeKey and adapter poll/drain
   routeKey are the same canonical token used by presence owner lookup.
5. Low-level unit tests may remain as support regression only when integrated
   proof would be excessively expensive.
6. `rg` scans are residue sanity only; they are not proof of runtime behavior.

Suggested checks:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime,xa-mass-engine,transport/transport_runtime,sdk/xa-mass-embedded-sdk -am `
  "-Dtest=WorkerManagerTest,WorkerAdmissionOwnerTest,RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest,WorkerDispatchRouteSelectorTest,NodeTargetedTaskDispatchSubmitterTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,MassApplicationDistributedTransportTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=EngineSchedulingCoreSuite" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

git diff --check
```

## WRB-6 Docs And Child Roadmap Alignment

Goal: move final current facts into owning docs and keep child roadmaps from
running on stale assumptions.

Scope:

1. Update `xa-mass-worker-runtime/README.md` and `CONTRACTS.md` if worker
   runtime evidence or dispatch-gate ownership changes.
2. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` if route-owner read or
   storage semantics change.
3. Update `xa-mass-engine/README.md` or scheduling baseline if engine
   `WorkerSchedulingView` ownership wording changes.
4. Update `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` to become the
   physical Redis child implementation roadmap after WRB-2.
5. Keep `REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` deferred until the
   transport presence key model is converged enough to prove.
6. Run residue scans before archiving this roadmap.

Acceptance:

1. Active docs agree on owner boundaries and do not describe target key shapes
   as current behavior.
2. Child roadmap status reflects whether it is blocked, active, or superseded
   by WRB decisions.
3. No active doc says worker declaration owns heartbeat/online truth.
4. No active doc says transport owns worker capability/admission truth.
5. No active doc says engine owns transport presence truth.

## Completion Criteria

This roadmap is complete only when:

1. The inventory exists and classifies current callers and key families.
2. Scheduling reachability consumes route-owner evidence through a bounded
   canonical routeKey subject path.
3. Dispatch route selection consumes route-owner evidence through a bounded
   canonical routeKey subject path.
4. `getPresence(workerId)` is not a scheduling truth fallback.
5. Transport presence has one chosen canonical writable Redis owner model keyed
   by canonical `routeKey`.
6. `WorkerStatusEventListener` heartbeat/status residue is removed or explicitly
   classified and bounded.
7. Engine matching consumes worker-runtime contracts, not transport presence or
   low-level registry primitives.
8. Integrated proof covers both:
   - transport presence -> worker-runtime evidence -> engine assignment, and
   - engine binding -> transport route owner -> inbox/compensation.
9. Presence lookup, dispatch envelope route resolution, and adapter poll/drain
   use the same canonical routeKey.
10. Proof shows that one delivery owner for a routeKey does not reduce
    worker-runtime capacity or multi-binding task dispatch behavior.
11. `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` is updated as a child
   roadmap or superseded.
12. `REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` remains deferred until key
    convergence is real.
13. Owner docs are updated with current facts.
14. Residue scan passes for old duplicate presence truth and scheduling
    projection fallback paths.

## Risks

| Risk | Guard |
| --- | --- |
| Redis key cleanup starts before owner decision | WRB-0 / WRB-2 first; no physical rewrite before route-owner canonical fact is chosen |
| Worker projection remains de facto scheduling truth | WRB-1 removes scheduling fallback to `getPresence(workerId)` |
| Runtime proof freezes duplicate transport presence keys | WRB-5 requires proof after owner/key convergence, not before |
| Engine starts importing transport implementation for reachability | Hard Rules 1 and 3 plus WRB-4 scans |
| Transport starts mutating worker runtime state to compensate dispatch failures | Node-targeted compensation must go through engine failure handler |
| Worker declaration updates keep carrying heartbeat truth | WRB-3 classifies or removes `WorkerStatusEventListener` residue |
| Broad wrapper hides ownership instead of clarifying it | Do Not Start With forbids generic boundary service/facade |
