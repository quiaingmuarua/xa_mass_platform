# Transport Route-Key Dispatch Handoff Convergence Roadmap

Status: proposed convergence roadmap.

Last verified against current code/worktree: 2026-06-11.

## Summary

Transport dispatch handoff should converge from node-targeted public semantics
to route-key-owned delivery semantics.

`routeKey` is the transport delivery address. `transportNodeId` is only current
owner evidence: the transport process that currently has a deliverable
connection or lease for that route. A Redis implementation may keep node-level
wakeup indexes so the right transport process does efficient polling, but node
identity must not be the dispatch handoff truth.

The target model is:

```text
engine assignment result
  -> resolve opaque routeKey
  -> enqueue dispatch work by routeKey
  -> current route owner / node consumes routeKey work
  -> adapter endpoint delivers item to worker
```

Not:

```text
engine assignment result
  -> resolve routeKey
  -> extract transportNodeId
  -> submit to node inbox as mainline truth
```

## Current Code Evidence

Current code has two Redis dispatch handoff shapes:

- `RedisTaskDispatchHandoff` implements `TaskDispatchHandoff` as one shared
  Redis queue: `<namespace>:queue`.
- `RedisNodeTargetedTaskDispatchHandoff` implements
  `NodeTargetedTaskDispatchHandoff` as per-node queues:
  `<namespace>:node:<transportNodeId>:queue`.

Current split assembly in `MassApplication` special-cases
`NodeTargetedTaskDispatchHandoff` for `ENGINE_PRODUCER`:

1. resolve worker -> routeKey,
2. read route-owner evidence,
3. extract `transportNodeId`,
4. submit `TaskDispatchBatch` to the node inbox.

Current route-owner code already points in the target direction:

- `TransportRouteOwnerRecord` is keyed by opaque `routeKey`.
- Redis route-owner keys are `owner:<shard>`, `deadline:<shard>`, and derived
  `worker-route:<workerId>`.
- `TransportDeliveryStore` is already routeKey-owned; `adapterId` is delivery
  metadata / endpoint identity, not queue ownership truth.

The gap is that dispatch handoff still exposes `transportNodeId` as a caller
contract through `NodeTargetedTaskDispatchHandoff`.

## Target Boundary

Transport owns two runtime concerns:

1. Worker consumption of assigned task items.
2. Worker-originated event/result ingress into the platform.

Transport does not own worker lifecycle truth. It may know only that a
`routeKey` currently has delivery-feasible owner evidence.

Target ownership:

- `routeKey`: transport delivery address and dispatch handoff truth.
- `transportNodeId`: route-owner evidence and optional Redis wakeup index owner.
- `adapterId`: concrete local endpoint/adapter identity used after a route is
  selected; not queue ownership truth.
- worker runtime reachability/admission: worker runtime interpretation, not
  transport keyspace truth.

## Non-Goals

- Do not move worker capacity, reservation, lease, readiness, dispatch gate, or
  group slot truth into transport.
- Do not change task assignment ownership or retry/finality ownership.
- Do not require routeKey minting rules inside transport runtime; transport
  consumes opaque routeKey values and only encodes them for physical Redis keys.
- Do not keep old node-targeted and route-key-targeted paths as two permanent
  mainlines.
- Do not add startup cleanup that deletes old Redis keys automatically.

## Do Not Start With

Do not start by changing Redis physical queue names from
`node:<transportNodeId>:queue` to `node:<transportNodeId>:route:<routeKey>`
while keeping `NodeTargetedTaskDispatchHandoff` as the mainline interface.
That would preserve the wrong owner boundary and only make the key name more
specific.

Start by defining the route-key handoff contract and moving callers to it. Node
wakeup/index keys can then become implementation details.

## Physical Redis Direction

The preferred Redis shape is route-owned queue plus derived node wakeup index:

```text
xa:mass:transport:dispatch-route:v1:q:<encodedRouteKey>
xa:mass:transport:dispatch-route:v1:meta:<encodedRouteKey>
xa:mass:transport:dispatch-route:v1:routes
xa:mass:transport:dispatch-route:v1:node:<transportNodeId>:ready-routes
```

Semantics:

- `q:<encodedRouteKey>` is the canonical dispatch handoff queue for that route.
- `node:<transportNodeId>:ready-routes` is a derived wakeup/index set or queue.
- If ownership moves from one node to another, the route queue stays valid.
- A stale node wakeup entry must not strand dispatch work; consumers validate
  current route owner before draining.

An implementation may temporarily mint a combined physical key such as
`node:<transportNodeId>:route:<encodedRouteKey>:queue` only as a migration
slice if it is documented as interim and replaced by route-owned queue truth
before completion.

## Phase 0: Inventory And Decision Lock

Goal: freeze the current caller and keyspace inventory before changing the
handoff contract.

Scope:

1. Inventory all `TaskDispatchHandoff` and `NodeTargetedTaskDispatchHandoff`
   call sites.
2. Inventory all Redis dispatch handoff key families.
3. Record which callers need route-key dispatch and which only need shared
   in-process handoff.
4. Update the transport boundary doc with the decision that `routeKey` is
   handoff truth and node is a derived consumer index.

Acceptance:

1. Inventory names production callers, tests, and builder/server config
   surfaces separately.
2. `transport/TRANSPORT_BOUNDARY_BASELINE.md` states route-key-owned handoff
   semantics without claiming implementation completion.
3. Existing `RedisTaskDispatchHandoff` and
   `RedisNodeTargetedTaskDispatchHandoff` are classified as current code, not
   target truth.

Verification:

```powershell
rg -n "TaskDispatchHandoff|NodeTargetedTaskDispatchHandoff|dispatch-node|dispatch-handoff" xa-mass-base transport sdk xa-mass-server
```

## Phase 1: Introduce Route-Key Handoff Contract

Goal: add the target contract without changing distributed behavior yet.

Scope:

1. Add a route-key-owned handoff contract, for example
   `RouteTargetedTaskDispatchHandoff` or `TransportRouteDispatchHandoff`.
2. The submit API accepts an opaque `routeKey` and a dispatch batch/envelope
   shape that keeps the assigned binding identity intact.
3. The consumer API polls work for locally owned routes without requiring
   engine callers to know `transportNodeId`.
4. Keep the contract in the runtime/transport boundary; do not add worker
   runtime APIs.

Acceptance:

1. New contract does not expose `workerId -> routeKey` reverse lookup.
2. New contract does not expose worker online/offline state.
3. Existing embedded and legacy shared handoff tests still pass.
4. No distributed caller has been migrated before route-owner validation rules
   are defined.

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-base,transport/transport_runtime -am -DskipTests compile
```

## Phase 2: Redis Route-Key Implementation

Goal: implement route-key-owned Redis handoff and keep node fan-out as a
derived optimization.

Scope:

1. Add `RedisRouteTargetedTaskDispatchHandoff` under
   `xa:mass:transport:dispatch-route:v1`.
2. Encode routeKey safely for Redis physical keys.
3. Enqueue dispatch work to `q:<encodedRouteKey>`.
4. Maintain a derived local-node ready index when current route-owner evidence
   includes `transportNodeId`.
5. Consumers drain only route queues whose current owner evidence matches the
   local transport node and active lease.
6. Backpressure is per route queue plus bounded ready-index growth.

Acceptance:

1. Producer submits by routeKey, not transportNodeId.
2. Queue ownership remains valid across route owner node movement.
3. Stale node ready entries are ignored or cleaned without dispatching to the
   wrong owner.
4. Redis tests prove physical keys are route-owned and node indexes are
   derived.
5. No transport Redis key stores worker capacity, reservation, active lease,
   dispatch gate, event-binding ceiling, or `group:{groupId}:slots`.

Verification:

```powershell
.\mvnw.cmd -pl transport/transport_runtime -Dtest=RedisRouteTargetedTaskDispatchHandoffTest test
```

## Phase 3: Move Distributed Assembly

Goal: make distributed runtime assembly use the route-key handoff.

Scope:

1. Replace `NodeTargetedTaskDispatchSubmitter` in distributed producer assembly
   with a route-key submitter.
2. The producer resolves routeKey from the assigned binding and submits by
   routeKey.
3. The Redis implementation resolves current owner evidence internally for
   wakeup/indexing.
4. Transport consumers poll route-key handoff work for locally owned routes and
   feed `TransportRoutingTaskDispatchListener`.
5. Compensation remains engine-owned when route owner evidence is absent,
   stale, or Redis enqueue fails.

Acceptance:

1. `MassApplication` no longer extracts `transportNodeId` before handoff submit.
2. Distributed producer code does not depend on `NodeTargetedTaskDispatchHandoff`.
3. Existing result inbox and dispatch-failure inbox semantics do not change.
4. Split transport tests cover at least two routeKeys on different transport
   nodes.

Verification:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk,transport/transport_runtime -am -Dtest=MassApplicationDistributedTransportTest,RedisRouteTargetedTaskDispatchHandoffTest test
```

## Phase 4: Retire Node-Targeted Mainline

Goal: remove node-targeted handoff as a public/mainline dispatch boundary.

Scope:

1. Remove or demote `NodeTargetedTaskDispatchHandoff` to an internal legacy test
   fixture if no production caller remains.
2. Remove `redisNodeTargetedDispatchHandoff(...)` from default distributed
   helper wiring.
3. Keep `dispatch-handoff` as simple/legacy shared handoff only if still
   useful for local fixtures.
4. Update SDK/server docs and transport baseline.
5. Add source guards against reintroducing node-targeted handoff as distributed
   truth.

Acceptance:

1. `redisDistributedChannels(redisUri)` wires route-key dispatch handoff.
2. Non-archive production code has no distributed mainline caller of
   `NodeTargetedTaskDispatchHandoff`.
3. Active docs do not describe `transportNodeId` as the dispatch handoff
   target.
4. Route-owner Redis key manifest and dispatch-route Redis key manifest are
   documented in transport baseline.

Verification:

```powershell
rg -n "NodeTargetedTaskDispatchHandoff|redisNodeTargetedDispatchHandoff|dispatch-node" xa-mass-base transport sdk xa-mass-server -g "*.java" -g "*.md" -g "*.yml"
.\mvnw.cmd -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk,xa-mass-server -am test
```

## Phase 5: Proof And Residue Scan

Goal: make the convergence hard to regress.

Scope:

1. Update `doc/PROOF_REGISTRY.md` so route-key dispatch handoff proof is tied
   to the transport route-owner invariant.
2. Update `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` to include
   dispatch-route key families as route-owned, not node-owned.
3. Add or update source guards:
   - distributed default must not use `dispatch-node` as mainline truth,
   - transport Redis must not introduce worker runtime/admission key families,
   - routeKey must stay opaque and encoded before becoming a Redis key token.
4. Run residue scan for old node-targeted and presence vocabulary.

Acceptance:

1. Proof registry names the representative route-key dispatch tests.
2. Redis proof roadmap forbids node-owned dispatch queue truth for mainline
   distributed transport.
3. Active docs use route-key handoff vocabulary consistently.
4. Completed facts move to owning baseline docs before this roadmap is
   archived.

Verification:

```powershell
rg -n "presence|NodeTargetedTaskDispatchHandoff|dispatch-node|transportNodeId.*handoff|worker online|worker offline" transport sdk xa-mass-server doc roadmap -g "*.java" -g "*.md" -g "*.yml"
.\mvnw.cmd -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk,xa-mass-server -am test
```

## Open Decisions

1. Whether the route-key handoff stores whole `TaskDispatchBatch` values per
   route or stores per-binding envelopes. Per-binding envelopes are likely
   cleaner for route-owned queues, but the migration cost is higher.
2. Whether route-key consumers use a Redis set, stream, or list for
   `ready-routes`. The current code uses lists; a set reduces duplicate route
   wakeups but needs careful empty-queue cleanup.
3. Whether route owner lease expiry should trigger proactive ready-index cleanup
   or remain best-effort during consumer poll.
4. Whether the public SDK keeps `workerOnline/workerOffline` names as protocol
   compatibility while internal transport event names converge to route
   claim/release and worker event ingress.

## Completion Criteria

This roadmap is complete only when:

1. Distributed dispatch handoff submits by routeKey in production assembly.
2. Redis dispatch handoff truth is route-owned.
3. Node wakeup/index data is derived and safe to rebuild/drop.
4. `NodeTargetedTaskDispatchHandoff` is removed from mainline distributed
   wiring or explicitly archived as legacy/simple support.
5. Transport docs, SDK/server docs, proof registry, and Redis proof roadmap all
   agree on the route-key-owned boundary.
6. Focused transport, SDK distributed, and server startup/context verification
   pass after residue cleanup.
