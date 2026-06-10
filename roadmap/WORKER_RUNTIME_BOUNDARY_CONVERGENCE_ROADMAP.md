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
12. Production route-owner lookup must be bounded. Redis-backed
    `currentOwner(routeKey)` requires the WRB-3 routeKey owner/index shape; WRB-2
    must not claim routeKey-only Redis lookup while the physical key is still
    `(adapterId, routeKey)`.
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
contract convergence only. It should not retarget production dispatch, rewrite
Redis keys, delete old APIs, or change worker connection behavior.

Phase order is intentional:

```text
inventory
  -> contract / codec convergence
  -> production route-path retarget
  -> Redis physical convergence
  -> integrated proof
  -> residue removal
```

Do not merge these phases to make the roadmap look shorter. This is kernel
work; each phase must be independently reviewable and testable.

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
   - `connectionId` generation and reuse in each adapter/session path
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
8. Record where `connectionId` is generated or reused:
   - polling worker adapter,
   - SDK pull session,
   - websocket sessions,
   - socket sessions,
   - Redis presence fallback UUID generation.

Acceptance:

1. Inventory proves the dependency model from code, not only from docs.
2. Every current transport presence key family has a named owner classification.
3. Every scheduling or dispatch route caller of `WorkerPresenceStore` is named.
4. Every route-owner path that can scan all active presences is named.
5. `WorkerStatusEventListener` is classified as mainline, compatibility
   residue, or removal target.
6. Redis worker runtime key families are classified separately from transport
   presence key families.
7. Every adapter/session path that reuses `workerId` as `connectionId` is named.

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

rg -n "markOnline\\(|refreshHeartbeat\\(|markOffline\\(|connectionId|publishWorkerOnline|publishWorkerHeartbeat|publishWorkerOffline" `
  transport sdk/xa-mass-embedded-sdk xa-mass-server `
  --glob '!**/target/**'

rg -n "groupSlotsHash|groupHeartbeatDeadlinesZset|groupCandidateBucket|workerBucketMembershipSet|taskActiveWorkersSet" `
  platform_infra/mass-runtime-redis/src/main/java `
  --glob '!**/target/**'
```

## WRB-1 Canonical RouteKey Contract Convergence

Goal: create a single route-key contract and generation owner before changing
production route behavior.

Scope:

1. Define the platform route-key minting contract:

```text
selected workerGroupId + workerId -> canonical routeKey
```

2. Choose the owner and module location for the codec. The codec may live in a
   shared transport/runtime contract module, but it must not live inside an
   adapter implementation.
3. Define codec policy v1:
   - input facts: `workerGroupId` and `workerId`,
   - output: stable opaque `routeKey`,
   - callers must not rely on the string layout,
   - adapters must not invent or override the canonical rule.
4. Define how missing `workerGroupId` is handled:
   - fail fast, or
   - bounded lookup through worker-runtime evidence.
   Silent fallback to raw `workerId` is not allowed in the target path.
5. Define the route-owner read contract shape, for example:

```text
currentOwner(routeKey)
```

   This is contract definition only. Production callers may still use the old
   path until WRB-2.
6. Classify current `findOwners(workerId)`, `getPresence(workerId)`, and
   `listActivePresences()` as compatibility/operator/support surfaces for now.
7. Do not rewrite Redis physical keys in this phase.
8. Do not delete old APIs or tests in this phase unless they block compilation.
9. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` with a current-vs-target
   note: canonical routeKey contract exists or is being introduced, while any
   production path still using adapter-local routeKey semantics remains
   compatibility/residue until WRB-2/WRB-3.

Acceptance:

1. There is exactly one named codec / contract owner for canonical routeKey
   generation.
2. Codec policy v1 is documented as `workerGroupId + workerId` input, while
   encoded output remains opaque to callers.
3. Adapter modules do not define their own canonical routeKey rule.
4. Missing group evidence handling is explicit and cannot silently collapse to
   raw `workerId`.
5. The proposed `currentOwner(routeKey)`-style read contract is named, even if
   production callers are not retargeted until WRB-2.
6. Existing old-path APIs are classified but not removed.
7. The transport owner baseline no longer presents adapter-local routeKey as the
   unqualified target model once the canonical routeKey contract is introduced.

Primary proof:

- codec contract tests for deterministic routeKey minting from worker subject
  evidence.
- compile proof that adapter modules reference the shared codec/contract when
  they need canonical routeKey behavior.

Support only:

- `rg` scans for adapter-local routeKey rules. These are residue scans, not
  runtime behavior proof.

Suggested checks:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -am `
  "-Dtest=CanonicalWorkerRouteKeyCodecTest" test

rg -n "TransportRouteKeyResolvers\\.workerId\\(|routeContext\\.workerId\\(|routeKeyResolver\\(" `
  transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java `
  --glob '!**/target/**'

rg -n "canonical routeKey|adapter-local delivery address|multiple route owners" `
  transport/TRANSPORT_BOUNDARY_BASELINE.md
```

## WRB-2 Production RouteKey Carrier Retarget

Goal: move the production route path onto the canonical routeKey contract
without changing Redis physical storage or claiming Redis-backed
routeKey-only owner lookup.

Scope:

1. Retarget route-key producers and consumers to the codec output:
   - worker connection registration / markOnline input,
   - dispatch envelope route-key resolution,
   - adapter poll/drain route key.
2. Introduce or retarget non-Redis route-owner read contracts to canonical
   routeKey where they can be bounded without physical storage changes:

```text
currentOwner(routeKey)
```

   Redis-backed production `currentOwner(routeKey)` is deferred to WRB-3 unless
   this phase explicitly adds and owns a minimal `routeKey -> owner` index. If
   that index is added, this phase is no longer "without Redis physical storage"
   and WRB-3 acceptance must be updated in the same change.
3. Treat `WorkerPresenceStore#findOwners(workerId)` and
   `isRouteOnline(adapterId, routeKey)` as compatibility/operator surfaces
   unless they can resolve through a bounded routeKey index.
4. Keep `getPresence(workerId)` as compatibility projection. It must not become
   the new routeKey owner lookup.
5. Ensure `TaskDispatchBinding` routeKey minting uses binding evidence when
   `workerGroupId` is present. Missing group evidence must fail fast or perform
   a bounded worker-runtime lookup.
6. Ensure every adapter/session path has a real session/epoch `connectionId`
   owner token. Polling and pull-session paths must not reuse `workerId` as the
   owner token after this phase.
7. Ensure missing, stale, or offline route owners produce engine-owned
   compensation/retry after assignment, not transport-owned rescheduling.
8. Preserve worker concurrency semantics: multiple task dispatch bindings may
   target the same canonical routeKey when worker-runtime admission/capacity
   allows them.
9. Do not remove the old workerId route resolver or old presence APIs in this
   phase unless all production callers have already moved and tests prove it.
10. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` to record the production
    routeKey carrier state reached by this phase without describing WRB-3 Redis
    owner lookup as already implemented.

Acceptance:

1. Dispatch envelope route resolution, worker connection registration, and
   adapter poll/drain use the same canonical routeKey for a worker subject.
2. Redis-backed route-owner lookup is either explicitly deferred to WRB-3 or
   backed by a named minimal routeKey owner index introduced in this phase.
3. No adapter/session path reuses `workerId` as `connectionId`.
4. Scheduling reachability does not gain a new worker projection fallback.
5. Dispatch route selection has no fallback to worker declaration or projection
   online state.
6. `listActivePresences()` is not on the scheduling or route-selection mainline.
7. Reconnect/takeover still prevents stale heartbeat/offline from revoking a
   newer route owner.
8. Missing route owner after assignment compensates through the engine dispatch
   failure path.
9. There is at most one active delivery owner for a presence subject.
10. Multiple dispatch bindings can use the same routeKey when
   `WorkerAdmissionRuntime` and `WorkerRegistry` capacity allow it.

Primary proof:

- transport runtime tests for canonical routeKey carrier use, session-token
  owner checks, stale owner rejection, and reconnect/takeover replacement.
- transport delivery test proving envelope routeKey and adapter poll/drain use
  the same canonical token; route-owner lookup is covered here only for
  non-Redis stores or for a named minimal routeKey owner index.
- node-targeted dispatch test proving unresolved route owner triggers
  compensation.
- integration proof that transport presence changes reachability through
  `WorkerReachabilityView`.

Support only:

- `rg` scans for scheduling callers of `listActivePresences()` or
  `getPresence(workerId)`.
- `rg` scans for raw workerId route-key production in production code.

Suggested checks:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -am `
  "-Dtest=RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest,WorkerDispatchRouteSelectorTest,NodeTargetedTaskDispatchSubmitterTest" test

.\mvnw.cmd -pl transport/polling-adapter -am `
  "-Dtest=PollingWorkerAdapterTest" test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am `
  "-Dtest=PullWorkerSessionTest,MassApplicationDistributedTransportTest" test

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=TaskSchedulingGateAndTargetingTest" test

rg -n "listActivePresences\\(|getPresence\\(" `
  sdk/xa-mass-embedded-sdk/src/main/java `
  transport/transport_runtime/src/main/java `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'

rg -n "TransportRouteKeyResolvers\\.workerId\\(|routeContext\\.workerId\\(|findOwners\\(worker\\.workerId\\(" `
  transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java `
  --glob '!**/target/**'

rg -n "markOnline\\([^\\n]*workerId[^\\n]*workerId|refreshHeartbeat\\([^\\n]*workerId[^\\n]*workerId|markOffline\\([^\\n]*workerId[^\\n]*workerId" `
  transport sdk/xa-mass-embedded-sdk `
  --glob '!**/target/**'
```

## WRB-3 Transport Presence Redis Physical Convergence

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
5. Make Redis-backed `currentOwner(routeKey)` a bounded read over the canonical
   owner record or a named derived routeKey owner index. It must not require
   caller-provided `adapterId`, and it must not scan all route-owner records.
6. Keep `adapterId`, `transportNodeId`, and `connectionId` inside the value or
   derived delivery indexes. They are not canonical Redis key subjects.
   `routeKey` may be a hash field or zset member, but it must not be embedded in
   NUL-delimited compound Redis key names.
7. Remove NUL-delimited key identity from new physical keys. If compound member
   tokens are needed, keep them as encoded values or explicitly parseable tokens,
   not shell-hostile Redis key names.
8. Define atomic mutation boundaries for:
   - mark online,
   - heartbeat refresh,
   - offline,
   - materialize stale,
   - prune expired,
   - route takeover.
9. Define cleanup indexes:
   - routeKey-shard deadline zset,
   - optional transport-node delivery-owner index,
   - optional adapter/transport-node sets.
10. Define cutover:
   - clean-runtime recreation is allowed for local/pre-release runtime Redis,
   - no rolling dual-write truth unless a later production migration decision
     explicitly proves why it is required.

Acceptance:

1. One canonical writable Redis owner exists for each canonical `routeKey`
   presence subject.
2. Worker projection is not writable truth beside subject presence truth.
3. Every derived index has a named cleanup owner and failure behavior.
4. Presence lookup by canonical `routeKey` remains bounded.
5. Redis-backed `currentOwner(routeKey)` no longer requires adapterId as an
   input.
6. Presence cleanup by routeKey-shard deadline remains bounded.
7. No new key shape uses NUL-delimited key names.
8. `adapterId`, `transportNodeId`, and `connectionId` are not canonical key
   subjects.
9. The old key families have explicit removal conditions.
10. `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` is updated or
   superseded to reflect this decision before implementation starts.

Suggested checks:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -am `
  "-Dtest=RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest" test

rg -n "route-presence|worker-routes|routesKey|workersKey|workerKey\\(|routePresenceKey|routeKey\\(" `
  transport/transport_runtime/src/main/java `
  --glob '!**/target/**'
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
  "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest" test
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
.\mvnw.cmd -pl xa-mass-worker-runtime -am `
  "-Dtest=WorkerManagerTest,WorkerAdmissionOwnerTest" test

.\mvnw.cmd -pl transport/transport_runtime -am `
  "-Dtest=RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest,WorkerDispatchRouteSelectorTest,NodeTargetedTaskDispatchSubmitterTest" test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am `
  "-Dtest=MassApplicationDistributedTransportTest" test

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest" test

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=EngineSchedulingCoreSuite" test

git diff --check
```

## WRB-6 Remove Residue And Align Docs

Goal: remove old route-owner, heartbeat, projection, and documentation residue
only after the converged mainline has proof.

Scope:

1. Remove or rewrite old workerId route-key resolver production use.
2. Remove target semantics that allow one workerId to have multiple active
   dispatch route owners. Multiple endpoint modes must be modeled as distinct
   workerIds if they are independently schedulable.
3. Remove tests that protect old multi-owner semantics and replace them with
   canonical routeKey replacement tests.
4. Decide the fate of `WorkerStatusEventListener`:
   - delete if no longer production mainline,
   - or rename/narrow it as process-local compatibility heartbeat projection.
5. Ensure worker declaration writes cannot become heartbeat or online/offline
   truth.
6. Ensure `WorkerResourceRuntime#updateWorker(...)` is not the default path for
   heartbeat refresh.
7. Keep `WorkerRuntimeStateRecord` as read evidence assembled from runtime
   owners, not persisted declaration truth.
8. Keep `statusName` display compatibility out of scheduling predicates.
9. Update `xa-mass-worker-runtime/README.md` and `CONTRACTS.md` if worker
   runtime evidence or dispatch-gate ownership changes.
10. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` if route-owner read or
    storage semantics change.
11. Update `xa-mass-engine/README.md` or scheduling baseline if engine
    `WorkerSchedulingView` ownership wording changes.
12. Update `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` as the
    physical Redis child roadmap or mark it superseded.
13. Keep `REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` deferred until the
    transport presence key model is converged enough to prove.
14. Run residue scans before archiving this roadmap.

Acceptance:

1. No production route-key path uses raw workerId fallback.
2. No test protects one workerId having multiple active delivery owners.
3. A worker online/offline event cannot update declaration truth as online
   authority.
4. Any remaining heartbeat refresh path is documented as compatibility residue
   or worker-registry evidence refresh, not transport reachability truth.
5. `WorkerDeclarationRecord` remains free of heartbeat, online/offline,
   reservation, lease, and dispatch-gate fields.
6. Active docs agree on owner boundaries and do not describe target key shapes
   as current behavior.
7. Child roadmap status reflects whether it is blocked, active, or superseded
   by WRB decisions.

Suggested checks:

```powershell
rg -n "TransportRouteKeyResolvers\\.workerId\\(|routeContext\\.workerId\\(|findOwners\\(worker\\.workerId\\(" `
  transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/websocket-adapter/src/main/java transport/socket-adapter/src/main/java `
  --glob '!**/target/**'

rg -n "workerCanExposeMultipleOnlineRouteOwners|multiple active route owners|updateWorker\\(withHeartbeat|setLastHeartbeat|setStatus\\(" `
  transport sdk xa-mass-worker-runtime xa-mass-engine `
  --glob '!**/target/**'

git diff --check
```

## Completion Criteria

This roadmap is complete only when:

1. The inventory exists and classifies current callers and key families.
2. Canonical routeKey generation has one named codec / contract owner; adapter
   modules do not define their own route-key rule.
3. Scheduling reachability consumes route-owner evidence through a bounded
   canonical routeKey subject path.
4. Dispatch route selection consumes route-owner evidence through a bounded
   canonical routeKey subject path.
5. `getPresence(workerId)` is not a scheduling truth fallback.
6. No adapter/session path reuses `workerId` as `connectionId`; stale
   heartbeat/offline commands cannot revoke a newer route owner.
7. Redis-backed `currentOwner(routeKey)` is a bounded read that does not require
   caller-provided `adapterId`.
8. Transport presence has one chosen canonical writable Redis owner model keyed
   by canonical `routeKey`.
9. `WorkerStatusEventListener` heartbeat/status residue is removed or explicitly
   classified and bounded.
10. Engine matching consumes worker-runtime contracts, not transport presence or
   low-level registry primitives.
11. Integrated proof covers both:
   - transport presence -> worker-runtime evidence -> engine assignment, and
   - engine binding -> transport route owner -> inbox/compensation.
12. Presence lookup, dispatch envelope route resolution, and adapter poll/drain
   use the same canonical routeKey.
13. Proof shows that one delivery owner for a routeKey does not reduce
    worker-runtime capacity or multi-binding task dispatch behavior.
14. `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` is updated as a child
   roadmap or superseded.
15. `REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` remains deferred until key
    convergence is real.
16. Owner docs are updated in the phase that changes current facts; WRB-6 is
    final alignment, not the first owner-doc update.
17. Residue scan passes for old duplicate presence truth and scheduling
    projection fallback paths.

## Risks

| Risk | Guard |
| --- | --- |
| Redis key cleanup starts before owner decision | WRB-0 / WRB-1 first; WRB-3 owns physical rewrite only after route-key contract and production path converge |
| Worker projection remains de facto scheduling truth | WRB-2 removes scheduling fallback to `getPresence(workerId)` |
| Runtime proof freezes duplicate transport presence keys | WRB-5 runs after WRB-3 owner/key convergence, not before |
| Engine starts importing transport implementation for reachability | Hard Rules 1 and 3 plus WRB-4 scans |
| Transport starts mutating worker runtime state to compensate dispatch failures | Node-targeted compensation must go through engine failure handler |
| Worker declaration updates keep carrying heartbeat truth | WRB-6 classifies or removes `WorkerStatusEventListener` residue |
| Broad wrapper hides ownership instead of clarifying it | Do Not Start With forbids generic boundary service/facade |
