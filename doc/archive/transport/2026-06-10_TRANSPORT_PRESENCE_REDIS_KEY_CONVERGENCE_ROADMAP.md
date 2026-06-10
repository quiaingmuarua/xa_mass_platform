# Transport Presence Redis Key Convergence Roadmap

Status: superseded and archived on 2026-06-10.

Superseded by
[WORKER_RUNTIME_BOUNDARY_CONVERGENCE_ROADMAP.md](./2026-06-10_WORKER_RUNTIME_BOUNDARY_CONVERGENCE_ROADMAP.md)
for implementation. Keep this file only as historical starting inventory and
key-proof rationale.

The executable convergence owner is now WRB. Do not implement the old TPRK
phases below directly. They describe the pre-WRB Redis key problem and the
proof bar that motivated the route-key owner convergence.

Current implementation truth as of 2026-06-10:

- `routeKey` for worker delivery is canonicalized by
  `CanonicalWorkerRouteKeyCodec` from `workerGroupId + workerId`.
- `RedisWorkerPresenceStore` stores the canonical route owner in
  `{namespace}:owner:{shard}` hash fields keyed by `routeKey`.
- Lease pruning is backed by `{namespace}:deadline:{shard}` zsets keyed by
  `routeKey`.
- `worker-route:{workerId}`, `workers`, and `owner-shards` are derived
  compatibility/operator indexes, not canonical worker metadata truth.
- The old `route-presence:{adapterId}\0{routeKey}`,
  `route:{adapterId}\0{routeKey}`, `worker-routes:{workerId}`, and `routes`
  families are no longer the target implementation.
- Delivery queues are routeKey-owned; `adapterId` is delivery request/owner
  value metadata, not queue identity.

This document still records the boundary between transport presence keys and
the worker runtime Redis keys discussed in
[2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md](../core/2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md).

WRB is now the prerequisite for
[REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md](../../../roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md),
not this historical TPRK file. The reusable proof runner should be built after the
transport presence keyspace is converged enough that it is worth codifying.
During this roadmap, committed Python or Node utilities may be used for local
Redis inventory/probes, including third-party Redis libraries. Avoid Bash-only
scripts because they are hard to reuse across local and CI environments.

This is not a Redis sizing roadmap. Local Redis scans may inventory current
key families, but they do not prove scale risk and they do not prove that a key
family should exist. The proof target is key-existence reasonableness: every
retained key family must have a distinct owner, production query, lifecycle, and
truth/derivation rule.

## Historical Starting Observations

These observations describe the state that existed before the WRB route-key /
presence implementation slice. They are not current implementation truth.

Original transport presence implementation:

- `RedisWorkerPresenceStore` owns transport reachability projection behind
  `WorkerPresenceStore`.
- The default namespace is `xa:mass:transport:presence`.
- Server assembly may pass a versioned namespace such as
  `xa:mass:transport:presence:v1`.
- `route-presence` keys were originally shaped as
  `{namespace}:route-presence:{adapterId}\0{routeKey}`.
- Polling originally called `markOnline(workerId, "polling", workerId, workerId,
  reason)`, so polling route keys commonly equal worker ids.
- `routeKey = workerId` is current polling binding policy, not transport-wide
  truth. Transport docs allow adapter ingress to use explicit route keys.

Original Redis presence writes:

```text
{namespace}:route-presence:{adapterId}\0{routeKey}  HASH   full WorkerPresence
{namespace}:worker:{workerId}                       HASH   latest worker projection
{namespace}:route:{adapterId}\0{routeKey}           STRING workerId
{namespace}:worker-routes:{workerId}                SET    adapterId\0routeKey
{namespace}:routes                                  SET    adapterId\0routeKey
{namespace}:workers                                 SET    workerId
```

Original issue:

- `route-presence` and `worker` both store complete `WorkerPresence` payloads.
- `workers` is currently written by `RedisWorkerPresenceStore` but has no known
  production reader in that class.
- `worker-routes:{workerId}` is currently used by `getPresence(workerId)`, but
  Redis `findOwners(workerId)` does not override the default
  `listActivePresences()` filter path. Dispatch route selection therefore does
  not yet prove it benefits from the worker-routes index.
- `route:{adapterId}\0{routeKey}` is currently used as a cleanup/deletion guard,
  not as the dispatch route-owner read model.
- The indexes are updated through multiple commands, not one owner apply.
- Lua script constants exist in `RedisWorkerPresenceStore`, but the main write
  path currently uses separate `HSET`, `SET`, and `SADD` calls.
- A partial write can leave route presence, worker projection, route mapping,
  and route membership indexes disagreeing.
- Any key family without an independent production query or cleanup invariant is
  residue, even if its memory cost is small in local Redis.

Worker runtime Redis implementation from the archived WRSI roadmap, still
separate from transport presence:

- `group:{groupId}:slots` remains the canonical Redis worker aggregate.
- `worker:meta:{workerId}`, `worker:occupancy:{workerId}`, and
  `available:{shard}` are target/residue concepts, not current writable truth.
- `worker:group:{groupId}:heartbeat:0` is the current worker-registry heartbeat
  deadline index.
- Candidate buckets are scheduling candidate indexes and remain hints.

## Owner Review

Transport presence belongs to transport.

Worker runtime may consume transport reachability through `WorkerPresenceStore`
or a narrow reachability view, but worker runtime must not define transport
route ownership.

Worker declaration, scheduling slot, readiness, occupancy, reservation,
capacity, candidate buckets, and dispatch gates belong to worker runtime and
`WorkerRegistry` owners, not to transport presence.

Redis modules are implementation adapters. Redis key names must not become SDK,
server, engine, or policy-facing contracts.

## Boundary Decision

Transport Redis presence and worker runtime Redis keys stay separate keyspaces.

They may share only stable identity references:

```text
workerId
workerGroupId
adapterId
routeKey
```

They must not share writable worker metadata, capability, readiness,
occupancy, reservation, lease, candidate-bucket, or dispatch-gate facts.

Current WRB transport presence truth:

```text
route owner canonical:
  routeKey
    -> workerId
    -> adapterId
    -> connectionId
    -> presenceState
    -> lease/heartbeat timestamps
    -> transportInstanceId

worker presence:
  derived compatibility/operator projection over route owners for one workerId
```

Target worker runtime truth stays as defined by WRSI:

```text
WorkerDeclarationStore / WorkerDeclarationRecord
  -> static worker declaration, group/node membership, attributes

WorkerRegistry / WorkerSlot
  -> current scheduling slot aggregate, dispatch gate, reservation,
     active lease count, exclusive lock, candidate membership

Transport WorkerPresenceStore
  -> route reachability only
```

## Key Existence Proof Bar

A Redis presence key family is justified only when all of these are true:

1. It is classified as exactly one of:
   - canonical transport route-owner fact,
   - derived worker projection/cache,
   - derived lookup index,
   - bounded cleanup/diagnostic index,
   - residue to delete.
2. It answers a named production query or lifecycle operation without a hot-path
   `SCAN` or unbounded full-route scan.
3. It has one writer operation or one owner apply path for the canonical fact
   plus all derived keys it updates.
4. If derived, it has a rebuild, invalidation, or removal rule from the
   canonical route-owner fact.
5. It does not duplicate writable worker declaration, scheduling slot,
   readiness, occupancy, reservation, candidate-bucket, lease, or route-owner
   truth.
6. Its value is proven by behavior:
   - dispatch route selection changes when route-owner keys change,
   - stale route/projection/index corruption cannot make a worker dispatchable,
   - cleanup removes expired route owner and derived indexes consistently.

The following are not proof:

- local Redis memory/key count being small or large,
- tests that only assert physical key existence,
- tests that only copy fields between `WorkerPresence` records,
- a key being cheap enough to keep.

## Redis Key Relationship

| Key Family | Classification | Current Reader / Query | Existence Proof Required | Current Risk |
| --- | --- | --- | --- | --- |
| `transport:presence:*:owner:{shard}` | current canonical route owner hash | `currentOwner(routeKey)`, route materialization, compatibility projection, diagnostics | dispatch route selection is driven by bounded routeKey owner reads; stale connection events cannot revoke newer owner | current implementation owner |
| `transport:presence:*:deadline:{shard}` | current derived cleanup index | `pruneExpired` | cleanup removes expired owners from the canonical owner hash and derived projections | cleanup/support index only |
| `transport:presence:*:worker-route:{workerId}` | current derived compatibility projection | `getPresence(workerId)`, default `isWorkerOnline(workerId)` projection | corruption cannot make dispatch possible; value can be rebuilt from route owners; stale projection is not authoritative | compatibility/operator surface only |
| `transport:presence:*:workers` | current derived compatibility/operator index | projection cleanup / support list behavior | cannot drive scheduling or route selection; may be removed only after compatibility readers disappear | not canonical worker metadata |
| `transport:presence:*:owner-shards` | current derived operator/prune index | owner shard discovery for bounded support scans | support scans remain bounded to known owner shards, not full Redis keyspace | diagnostic/prune support only |
| `transport:presence:*:route-presence:{adapterId}\0{routeKey}` | historical removed target | none in current main code | old family must not be reintroduced as a parallel owner | superseded by routeKey owner hash |
| `transport:presence:*:route:{adapterId}\0{routeKey}` | historical removed target | none in current main code | old cleanup guard must not be reintroduced as a second route mapping | superseded |
| `transport:presence:*:worker-routes:{workerId}` | historical removed target | none in current main code | old multi-route worker index must not reintroduce one-worker-many-owner semantics | superseded |
| `transport:presence:*:routes` | historical removed target | none in current main code | old global route index must not become a hot-path fallback | superseded |
| `runtime:*:worker:group:{groupId}:slots` | worker registry canonical truth | worker scheduling/admission runtime | not part of transport key convergence; must not absorb route owner fields | separate owner |
| `runtime:*:worker:group:{groupId}:heartbeat:0` | worker registry heartbeat deadline index | worker scheduling reachability/admission | not part of transport key convergence; must not absorb connection or route owner truth | separate owner |
| `runtime:*:worker:group:{groupId}:bucket:*:workers` | worker registry candidate hint | scheduling candidate source | not part of transport key convergence; must not depend on transport route keys | separate owner |
| `runtime:*:worker:task:{taskId}:worker-active-count` | worker registry admission/occupancy evidence | capacity/admission | not part of transport key convergence; must not own transport reachability | separate owner |
| `runtime:*:worker:worker:meta:{workerId}` | none today | target/residue only | must not be introduced as a parallel transport/runtime metadata writer | target/residue only |
| `runtime:*:worker:worker:occupancy:{workerId}` | none today | target/residue only | must not be introduced as a parallel transport/runtime metadata writer | target/residue only |

## Hard Rules

1. Do not merge transport presence and worker runtime keyspaces.
2. Do not move WorkerGroup, WorkerDeclaration, WorkerSlot, capacity,
   readiness, occupancy, candidate-bucket, lease, or reservation facts into
   transport presence.
3. Do not move transport `connectionId`, `routeKey`, adapter route ownership,
   or presence lease timestamps into `WorkerRegistry` slot truth.
4. Historical `route-presence` keys were route-owner reachability, not worker
   metadata. Current WRB code uses routeKey-sharded owner hashes instead.
5. `worker:{workerId}` in the transport presence namespace must be either
   removed or documented and enforced as a derived projection/cache.
6. Every presence mutation must have a single writer operation for its canonical
   record plus indexes.
7. Stale heartbeat/offline events may only mutate presence when their
   `connectionId` still owns the route.
8. No hot-path `SCAN` fallback is allowed for presence lookup, routing, or
   cleanup.
9. Redis physical keys must not leak into SDK, server, engine, or policy
   surfaces.
10. Clean-runtime recreation is acceptable in this pre-release stage. Do not add
    compatibility aliases or dual writers unless this roadmap is re-scoped.
11. No key family may remain only because it is cheap in local Redis. Retained
    keys need caller, lifecycle, and truth/derivation proof.

## Non-Goals

- No public worker metadata model redesign.
- No WorkerDeclarationStore migration.
- No WRSI physical split of `worker:meta`, `worker:occupancy`, or
  `available` keys.
- No Scheduling Plane policy change.
- No change to `TaskWorkRuntime`, task lease, result, retry, or terminal truth.
- No change to worker candidate bucket strategy.
- No merge of transport node presence with worker route presence in the first
  slices.
- No live rolling Redis migration requirement.

## Do Not Start With

Historical wrong-order note: the old TPRK plan should not have started by
deleting `route-presence` keys or by adding a single broad `worker:meta` hash
that tried to include transport, scheduling, and declaration facts.

Current execution note: do not start new implementation from TPRK-0/1. Continue
from WRB's remaining phases, or archive this file after WRB residue cleanup.

Do not start with memory/key-count optimization. The first useful work is to
classify every current transport presence key as:

```text
canonical route-owner fact
derived worker projection/cache
derived lookup index
cleanup membership index
residue
```

## TPRK-0: Inventory And Classification (Historical)

Goal at original creation time: create a key/caller inventory before changing
storage shape. This has been superseded by WRB inventory and implementation.

Scope:

1. Inventory all `RedisWorkerPresenceStore` keys and all callers of:
   - `markOnline`,
   - `refreshHeartbeat`,
   - `markOffline`,
   - `getPresence`,
   - `findOwners`,
   - `isRouteOnline`,
   - `listActivePresences`,
   - `pruneExpired`.
2. Classify production vs test-only callers.
3. Classify whether each caller needs:
   - route-owner truth,
   - latest worker projection,
   - online boolean,
   - diagnostic list.
4. For every physical Redis key family, record:
   - writer,
   - reader,
   - production query served,
   - lifecycle cleanup path,
   - rebuild/removal rule when derived,
   - whether it is hot-path, diagnostic, cleanup-only, or residue.
5. Specifically verify current suspected gaps:
   - `workers` is write-only unless a production reader is found,
   - `worker-routes:{workerId}` is not currently used by Redis
     `findOwners(workerId)`,
   - `route:{adapterId}\0{routeKey}` is cleanup/deletion guard only unless a
     production reader is found.
6. Cross-check current worker runtime Redis keys from
   `RedisWorkerRegistryKeyspace` and confirm no transport caller depends on
   them directly.
7. Local Redis keyspace scans may be included as empirical samples, but only as
   inventory evidence. They must not be used as scale proof.
8. Temporary inventory/probe tooling may be implemented in Python or Node, may
   use third-party Redis libraries, and must be committed or otherwise
   replayable. One-off Bash pipelines or console screenshots are not accepted
   as roadmap evidence.
9. Temporary probes should record only structural key evidence needed for this
   roadmap: key family, Redis type, key count/cardinality, TTL/PTTL, namespace,
   and sample key names. They must not validate item payload schema, worker
   declaration fields, or task/result value structure.

Acceptance:

1. A sibling inventory or an inventory section records every current presence
   key family, writer, reader, lifecycle, and proof classification.
2. The inventory identifies whether `worker:{workerId}` is required as a cache
   or can be removed after readers derive from route owners.
3. The inventory explicitly states that WRSI target keys
   `worker:meta:{workerId}` and `worker:occupancy:{workerId}` are not part of
   this roadmap.
4. Any key with no production reader is marked as removal candidate before code
   changes begin.
5. Any key with a reader but no distinct owner/query/lifecycle reason is marked
   as derived-cache or residue, not canonical truth.
6. No implementation change is made in this slice except inventory/doc
   corrections.
7. If a temporary Redis probe is added, it is documented as support inventory
   for TPRK only, not as a reusable proof-runner contract.

Suggested checks:

```powershell
rg -n "markOnline\\(|refreshHeartbeat\\(|markOffline\\(|getPresence\\(|findOwners\\(|isRouteOnline\\(|listActivePresences\\(|pruneExpired\\(" transport xa-mass-server sdk integrations --glob '!**/target/**'
rg -n "route-presence|worker-routes|routeKey\\(|workerKey\\(|workerRoutesKey\\(|routesKey\\(" transport/transport_runtime/src/main/java transport/transport_runtime/src/test/java --glob '!**/target/**'
rg -n "groupSlotsHash|groupHeartbeatDeadlinesZset|groupCandidateBucket|workerBucketMembershipSet|taskWorkerActiveCountsHash" platform_infra/mass-runtime-redis/src/main/java platform_infra/mass-runtime-redis/src/test/java --glob '!**/target/**'
```

Temporary probe guidance:

```powershell
# Prefer a committed Python or Node script when Redis samples are useful.
# The script may use a normal Redis client library and must use bounded SCAN
# over explicit namespace prefixes. It must not use KEYS or delete data.
```

## TPRK-1: Canonical Presence Owner Decision (Historical)

Goal at original creation time: lock the transport presence canonical record
and projection rules. The current decision is WRB's routeKey owner hash.

Scope:

1. Decide and document `route-presence:{adapterId}\0{routeKey}` as the
   canonical transport route-owner fact, unless TPRK-0 proves a stronger
   worker-keyed owner.
2. Define `worker:{workerId}` as one of:
   - removed read-side residue,
   - derived cache with rebuild rules,
   - derived compatibility projection with explicit invalidation rules.
3. Define whether `route:{adapterId}\0{routeKey}` remains an index or is
   redundant because `route-presence` already stores `workerId`.
4. Define cleanup ownership for `worker-routes`, `routes`, and `workers`.
5. For every retained key family, write a one-line proof statement:
   `key exists because <caller/lifecycle operation> needs <bounded query> and
   can be rebuilt/invalidated from <canonical owner>`.

Acceptance:

1. There is exactly one canonical writable transport presence record for a
   route owner.
2. Any retained `worker:{workerId}` key is documented as derived/cache, not a
   second truth track.
3. The decision names every key that may be rebuilt from canonical route-owner
   records.
4. The decision does not alter worker runtime Redis key ownership from WRSI.
5. `workers` is either removed or justified by a named production reader.
6. `route:{adapterId}\0{routeKey}` is either removed or justified by a named
   bounded query that cannot read `route-presence`.

## TPRK-2: Atomic Presence Mutations

Goal: make transport presence writes owner-atomic.

Scope:

1. Replace multi-command presence writes with Lua or `MULTI/EXEC` owner apply
   operations for:
   - online,
   - heartbeat,
   - offline,
   - prune/expire cleanup.
2. Remove unused script constants or wire them as the real write path.
3. Preserve owner checking:
   - heartbeat only applies when `connectionId` matches the current route owner,
   - offline only applies when `connectionId` matches the current route owner,
   - reconnect may replace route owner through online.
4. Update all canonical record, projection/cache, and index keys in one apply
   operation per mutation.

Acceptance:

1. No presence mutation writes a canonical route-owner record and its indexes
   through ungrouped sequential Redis commands.
2. Tests prove stale heartbeat/offline cannot overwrite a newer owner.
3. Tests prove reconnect produces one current route owner and a consistent
   worker projection.
4. Tests prove route presence and route lookup remain consistent after online,
   heartbeat, offline, and prune.

Suggested verification:

```powershell
mvn -pl transport/transport_runtime -am "-Dtest=RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest,WorkerRuntimeViewTest,WorkerDispatchRouteSelectorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## TPRK-3: Worker Projection And Reader Convergence

Goal: prevent worker-level presence reads from becoming a second metadata owner.

Scope:

1. Move readers that need dispatch routing to `findOwners(workerId)`.
2. Make Redis `findOwners(workerId)` use the worker-route index, or remove that
   index if dispatch routing intentionally stays on another bounded owner view.
3. Keep `getPresence(workerId)` as a derived latest projection only when the
   caller really needs a worker-level online/read model.
4. Ensure `isWorkerOnline(workerId)` derives from current route owners, not from
   stale worker projection alone.
5. Ensure diagnostic list APIs are bounded and do not require hot `SCAN`.

Acceptance:

1. Dispatch routing uses route owners, not worker projection truth.
2. `getPresence(workerId)` cannot report online when no matching online
   canonical route owner exists.
3. Worker projection cache corruption, if retained, cannot make a worker
   dispatchable without a live route owner.
4. Redis dispatch owner lookup does not require scanning all route owners for a
   single worker.
5. Tests prove multi-route worker behavior:
   - one route offline leaves another online route usable,
   - latest worker projection follows the current live route,
   - stale projection does not override live route-owner truth.

## TPRK-4: Keyspace Documentation And Guardrails

Goal: make physical Redis key ownership obvious to future agents.

Scope:

1. Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` with the canonical
   route-owner key decision.
2. Add a transport Redis presence key section or module-local baseline under
   `transport/`.
3. Update `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` only to
   cross-link the boundary: worker registry keys do not own transport presence.
4. Add guard/residue checks that reject:
   - worker declaration or scheduling slot fields inside transport presence,
   - transport route fields inside worker registry slot truth,
   - SDK/server/engine code depending on physical Redis presence keys.

Acceptance:

1. Active docs distinguish:
   - transport route presence,
   - worker declaration,
   - worker scheduling slot/admission,
   - task work lease/runtime truth.
2. `route-presence` examples show the `\0` separator or another unambiguous
   escaped representation.
3. WRSI archived roadmap is referenced as historical worker-runtime key context,
   not as an active transport presence implementation plan.
4. Residue checks are listed and either automated or easy to run.

Suggested residue scans:

```powershell
rg -n "route-presence|worker-routes|transport:presence" xa-mass-engine platform_infra/mass-runtime-redis sdk xa-mass-server --glob '!**/target/**'
rg -n "WorkerGroup|WorkerSlot|WorkerDeclaration|capacity|reservedCount|activeLeaseCount|candidateBucket" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/presence transport/transport_api/src/main/java/com/xa/mass/transport/presence --glob '!**/target/**'
rg -n "routeKey|connectionId|route-presence|WorkerPresence" platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis --glob '!**/target/**'
```

## TPRK-5: Runtime Proof Bundle

Goal: prove the key convergence through runtime-visible behavior, not only key
shape assertions or local key counts.

Scope:

1. Use focused Redis presence tests for key consistency.
2. Use transport dispatch route tests for route-owner selection behavior.
3. Use one server/SDK polling or websocket integration proof only if production
   wiring changed.
4. Avoid adding tests that only assert field copying between duplicated
   presence records.

Acceptance:

1. Online worker becomes dispatch-route reachable through route-owner view.
2. Stale heartbeat/offline from an old connection cannot remove a newer route.
3. Removing one route for a multi-route worker does not remove unrelated live
   routes.
4. Expiry/prune removes expired route owner and derived indexes consistently.
5. No test treats physical key existence alone as proof of scheduling or
   dispatch correctness.
6. Each retained key family has at least one proof category:
   - behavior-driving route-owner proof,
   - bounded-query proof,
   - cleanup proof,
   - derived-cache corruption/rebuild proof.

Suggested verification:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -am "-Dtest=RedisWorkerPresenceStoreTest,InMemoryWorkerPresenceStoreTest,WorkerDispatchRouteSelectorTest,NodeTargetedTaskDispatchSubmitterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

# If Spring/server assembly changes:
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=ExternalWorkerPollingApiIntegrationTest,JavaExternalSdkPollingSessionIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Suggested Implementation Order

1. TPRK-0 inventory.
2. TPRK-1 canonical decision.
3. TPRK-2 atomic write path.
4. TPRK-3 reader convergence.
5. TPRK-4 docs and guards.
6. TPRK-5 proof bundle.

Do not build `tools/xa-mass-redis-runtime-proof` as part of this roadmap unless
TPRK has already converged the keyspace and the remaining work is only to
codify stable structural proof. Temporary Python/Node probes are acceptable
support evidence during TPRK; the reusable proof-runner is a successor
roadmap.

## Roadmap Completion Criteria

This roadmap is complete only when:

1. Transport presence has exactly one canonical route-owner truth record.
2. Any worker-level presence record is removed or documented and guarded as a
   derived projection/cache.
3. Presence mutations update canonical record and indexes atomically.
4. Dispatch routing cannot use stale worker projection without a live route
   owner.
5. Every retained transport presence key family has a named caller/query,
   lifecycle rule, and canonical/derived/residue classification.
6. No transport presence key stores worker declaration, capability, readiness,
   capacity, occupancy, candidate-bucket, lease, or reservation truth.
7. No worker runtime Redis key stores transport route owner, connection, or
   transport presence lease truth.
8. Active docs and residue scans explain the relationship with the archived
   WRSI worker runtime key roadmap.
9. Focused Redis/memory transport presence tests and route-owner dispatch tests
   pass.
10. Redis key-family evidence is captured by focused behavior tests plus
    replayable Python/Node structural probes. The successor proof-runner
    roadmap may later replace the temporary probes after the key model is
    stable.

## Open Decisions

1. Whether `worker:{workerId}` should be removed or retained as a derived cache.
2. Whether `route:{adapterId}\0{routeKey}` is still necessary once
   `route-presence` is canonical.
3. Whether `workers` should be deleted as write-only residue or assigned a real
   production reader.
4. Whether Redis `findOwners(workerId)` should use `worker-routes:{workerId}`
   or another bounded route-owner index.
5. Whether transport node presence should later get the same canonical/index
   treatment in a separate roadmap.
