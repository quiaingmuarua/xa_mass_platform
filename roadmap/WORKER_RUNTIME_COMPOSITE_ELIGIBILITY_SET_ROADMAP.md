# Worker Runtime Composite Eligibility Set Roadmap

Status: slice complete, roadmap active. The current engine / worker-runtime
mainline now consumes a registry-owned slot lifecycle validator before Stage-2
matching and carries WorkerGroup-scoped admission evidence through reserve,
bind, release, final, and result repair. The hot-path candidate row is now a
slim source/static-metadata row and is guarded against live evidence fields.
Redis deadline-aware slot lifecycle projection is now implemented for the
current scheduling candidate path. Stage-2 no longer rereads dispatch gate as a
candidate predicate, and candidate bucket policy now declares source-index
fan-out cost. Reachability composition optimization and engine-external surface
cleanup remain active follow-up phases.

Related:

- `roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`
- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`
- `xa-mass-worker-runtime/README.md`
- `xa-mass-worker-runtime/CONTRACTS.md`
- `doc/PROOF_REGISTRY.md`

## Purpose

Converge the scheduling hot path toward WorkerGroup-scoped eligibility without
collapsing separate state owners into one Redis writer.

This roadmap owns the worker lifecycle eligibility boundary:

- current engine / worker-runtime scheduling mainline convergence,
- Redis shape exposure inventory and guardrails for that mainline,
- registry-owned slot lifecycle eligibility,
- worker-runtime composition with reachability evidence,
- group-scoped admission target decisions,
- rule-context and diagnostic-shape guardrails.

This roadmap is allowed to replace or narrow existing in-repo scheduling
interfaces when they are the wrong production boundary. It must not hide old
semantics behind a wrapper, bridge, or compatibility facade. The target is a
single production mainline, not a parallel proof surface.

Engine-external surface consistency is follow-up work unless a current slice
touches that surface directly. Server inspection/controller/API DTOs, SDK-facing
worker views, transport semantic contracts, and frontend console DTOs should get
a separate roadmap once the engine mainline mechanism is established.

It does not supersede
`roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`. BCA remains
the owner for complete-set versus bounded-subset candidate acquisition semantics.
Current BCA-0/BCA-1 outcome: scheduling candidate acquisition is a bounded
source-batch contract, and Redis may fetch a bounded bucket subset before
policy sampling. CES Redis slot lifecycle work must cite that contract instead
of reintroducing complete-bucket assumptions.

## Current Slice Landed

2026-06-12 current mainline slice:

- `WorkerRegistry` now exposes `slotLifecycleStatus(groupId, workerId, now)`.
  Memory and Redis implementations evaluate slot existence, group membership,
  removing state, heartbeat freshness, and dispatch gate without including
  capacity.
- `WorkerCandidateIndex` calls that predicate in the production candidate
  source guard. Group acquisition and fixed target-worker lookup both reject
  stale heartbeat, dispatch-disabled, removing, missing, or group-mismatched
  slots before Stage-2 reserve.
- `WorkerAdmissionRuntime` and `WorkerAdmissionOwner` no longer expose
  worker-id-only admission lifecycle methods to engine callers. The engine
  scheduling, binder, release, and final paths use `WorkerAdmissionTarget`
  carrying `(workerGroupId, workerId, taskId, permits)`.
- `WorkerAdmissionTarget`, `WorkerAdmissionRuntime`, `WorkerRegistry`, and
  worker-runtime owner docs now describe group-scoped admission as the engine
  scheduling lifecycle boundary. Engine architecture guards fail if the
  admission mutation surface or current lifecycle files reintroduce
  worker-id-only reserve/confirm/release/claim/final calls.
- Runtime work evidence now carries `workerGroupId` through
  `WorkerClaimTarget`, `ClaimedTaskWork`, `ActiveLeaseRecord`,
  `RuntimeResultApplyContext`, `TaskResultCallbackDraft`,
  `TaskResultFinalDraft`, and `TaskResultRuntimeRow`, so result repair can
  republish attempt-closed events without losing group-scoped final accounting
  evidence.
- Redis candidate acquisition semantics were narrowed through BCA. Current
  Redis candidate bucket SETs still mean source membership, while scheduling
  acquisition now receives the scheduling clock and reads sibling per-bucket
  lifecycle deadline ZSETs before policy sampling. The support source-membership
  path remains bounded with `SRANDMEMBER`.
- Redis maintains per-bucket lifecycle deadline ZSETs beside the group/node
  source bucket SETs. Source buckets remain metadata/policy membership; the
  ZSETs are derived scheduling indexes maintained from canonical slot truth on
  heartbeat/upsert, dispatch-gate, removing-slot, and cleanup paths.
- Reachability remains worker-runtime-composed through `WorkerReachabilityView`;
  Redis still does not own transport reachability. CES-5 selected the current
  per-candidate reachability read as the production composition path until a
  future worker-runtime reachability projection writer is explicitly designed.
- Engine architecture guard coverage now prevents `RedisWorkerRegistry` from
  consuming transport route-owner / presence packages or key families as worker
  reachability truth.
- `WorkerCandidateRow` was slimmed in-place to source/static metadata:
  `workerId`, `workerGroupId`, adapter/node identity, online strategy,
  agent version, and worker attributes. Live lifecycle, dispatch, load,
  capability, capacity, and diagnostic evidence are joined through separate
  scheduling/evidence surfaces, not this hot-path row.
- Engine architecture guard coverage now prevents `WorkerCandidateRow` from
  regrowing `statusName`, `lastHeartbeat`, capability lists, `available`,
  capacity/load/reserve, dispatch, or reachability fields.
- Engine architecture guard coverage now also prevents engine and
  worker-runtime mainline Java sources from importing Redis worker adapter /
  keyspace types or embedding worker Redis key-family literals.
- Low-level `WorkerRegistry` worker-id default methods remain support SPI for
  diagnostics, commands, tests, and callers that genuinely lack group evidence.
  They are not the engine scheduling lifecycle contract.

## Current Code Observations

- `WorkerCandidateIndex` is the current Stage-1 candidate source. Its
  production group path passes one scheduling clock into registry acquisition
  and the source guard. Redis can use that clock against deadline projections;
  the source guard still evaluates canonical registry-owned slot lifecycle
  status before returning group or target-worker candidates. It does not
  evaluate reachability, load, reservation, capacity, or worker-lock policy.
- Engine scheduling enters candidate acquisition through
  `WorkerCandidateRuntime#findWorkerCandidateBatch(...)`; a new source contract
  is not production-relevant unless that mainline consumes it.
- `WorkerManager` reads reachability through `WorkerReachabilityView`. Current
  comments keep route-owner connection leases in transport delivery and do not
  promote them into worker lifecycle truth.
- `WorkerRegistry` / Redis owns slot metadata, heartbeat deadline, dispatch
  gate, removing state, exclusive lease flag, reserve/capacity counters, and
  task-worker active counts. It does not own transport reachability.
- `WorkerSchedulingCandidateEnumerator` currently composes worker scheduling
  evidence per candidate by reading reachability, dispatch gate, exclusive
  lease, WorkerGroup capability, and load. The dispatch-gate read is now
  duplicate evidence for candidates that passed slot lifecycle validation, but
  it remains in place until CES-7 decides whether to remove or classify that
  duplicate read. CES-5 preserves the current per-candidate reachability read.
- `WorkerCandidateRow` is now the candidate-source identity row only. It no
  longer carries worker status, heartbeat, worker-level capability lists,
  capacity, availability, or create/update timestamps.
- `RuleBasedTaskWorkerMatchingStrategy` rejects candidates when dispatch is
  disabled, reachability is not `ONLINE`, or the worker is locked.
- `WorkerRegistry#tryReserve(groupId, workerId, ...)` revalidates slot
  existence, group, removing state, heartbeat freshness, dispatch gate, and
  capacity in the mutation path.
- `WorkerAdmissionRuntime` and `WorkerAdmissionOwner` now expose a
  `WorkerAdmissionTarget` admission lifecycle surface to engine callers.
  Scheduling reserve, binder confirm/claim/final, release, and final accounting
  must carry `workerGroupId`.
- Runtime claim, lease, result apply context, callback draft, final draft, and
  visible result row records now carry `workerGroupId`, so normal result apply
  and result repair can publish group-scoped attempt-closed/final evidence.
- Low-level worker-id default methods remain on `WorkerRegistry` as support SPI.
  They should not be used as the production engine scheduling lifecycle path.
- `Worker.status` / `statusName` is display compatibility in the worker-runtime
  contract. It must not become scheduling truth. Worker state reports affect
  scheduling only when a worker-control policy translates them into dispatch
  gate state.
- `WorkerMatchContext` already separates diagnostic context from rule context;
  live reachability, dispatch gate, lock, load, and admission evidence must not
  become declarative rule input.
- Redis candidate bucket SETs currently represent source membership. They are
  not proof that a worker is reachable or dispatch-eligible. Redis scheduling
  acquisition uses sibling per-bucket lifecycle deadline ZSETs; source guard and
  reserve remain canonical revalidation before dispatch binding.
- Current source search found no main-source engine or worker-runtime imports of
  worker Redis keyspace classes and no worker Redis key-family literals in those
  modules. That absence must become a guard before Redis shape work starts.
- Server assembly currently imports Redis runtime adapters to choose an infra
  implementation by profile/mode. That is an assembly concern, not a worker
  scheduling read surface.
- Transport runtime uses shared Redis queue primitives for transport-owned
  delivery storage. That is not worker Redis key leakage, but it is a separate
  dependency-boundary concern if Redis primitives later move to a neutral infra
  module.

## Owner Review

Worker runtime owns worker lifecycle evidence surfaces and the semantic
composition contract consumed by engine scheduling.

The low-level registry owns only registry facts: worker slot existence,
WorkerGroup membership, heartbeat deadline from slot metadata, dispatch gate,
removing state, reserve/capacity counters, active counts, and exclusive lease
state. Redis may maintain derived indexes over those facts, but it must not
become the owner of transport reachability.

Reachability is currently provided through `WorkerReachabilityView`. A future
reachability projection writer may feed worker-runtime eligibility, but that is
a separate owner decision. Redis implementation slices in this roadmap must not
read transport route-owner keys or reinterpret transport presence as worker
lifecycle truth.

The engine may consume bounded, group-scoped candidate/evidence surfaces and may
rank, reserve, and bind dispatches. It must not define the worker lifecycle
predicate or read Redis worker keys directly.

External modules should not observe Redis keyspace classes, key-family names,
encoded Redis payload shapes, or Redis-only DTOs when reading worker lifecycle
or candidate data. `mass-runtime-api` and `xa-mass-worker-runtime` semantic
contracts are the module boundary. Server assembly may instantiate Redis
adapters, but it must not expose Redis key names as worker scheduling API. This
roadmap enforces that rule first on the current engine / worker-runtime
mainline; broader SDK/server/transport/frontend cleanup is follow-up unless a
slice edits those surfaces.

Worker scheduling policy may declare which worker attributes deserve indexed
candidate buckets. Worker runtime may execute that policy, but should not
pre-bake arbitrary attribute dimensions as permanent scheduling truth.

## Boundary Decision

Do not start with one Redis "composite eligible" set that includes all live
state dimensions.

Split the target into two layers:

1. Registry-owned `slotLifecycleEligible`
   - worker belongs to the selected WorkerGroup
   - slot exists and is not removing
   - heartbeat deadline is still in the future
   - dispatch gate is enabled
   - worker-control readiness affects this layer only after the control policy
     has translated it into dispatch-gate state

2. Worker-runtime `dispatchEligible`
   - `slotLifecycleEligible`
   - reachability is currently `ONLINE`
   - optional exclusive-lock handling, depending on the CES-1 decision

Capacity and final lease admission remain reserve-owned. A worker may be slot
lifecycle eligible and still lose `tryReserve(...)` because of capacity pressure
or a concurrent reservation.

Until a real reachability projection writer exists, the current engine /
worker-runtime reachability read remains part of the composition path. Redis
must not pretend to own reachability.

## Target Shape

The target hot path is staged through the existing production mainline, after
replacing or narrowing any interface that cannot express the new mechanism:

```text
ResolvedWorkerSchedulingPolicy
  -> WorkerTaskSelector(workerGroupIds, adapterNodeId, targetWorkerId, policy bucket keys)
  -> WorkerCandidateRuntime or successor mainline candidate contract
  -> registry-owned group-scoped slotLifecycleEligible source/validator
  -> worker-runtime reachability composition or current reachability read
  -> engine ranking / rule checks
  -> admission using group-scoped lifecycle target evidence
  -> dispatch binding
```

Worker runtime should expose group-scoped operations for hot paths where the
caller has group evidence:

- acquire slot-lifecycle-eligible worker ids or rows for a WorkerGroup,
- read worker scheduling evidence with known `workerGroupId`,
- reserve/confirm/release/claim/final using a lifecycle target that carries
  `(groupId, workerId, taskId)` and permit count when the current engine path has
  or creates that evidence.

Confirm/release/claim/final are part of the same production admission mutation
family. Either dispatch/lease/work records carry `workerGroupId`, or the current
mainline is not yet converged. Worker-id-only admission is allowed only for
diagnostic/control/support paths that are not the engine scheduling lifecycle
mainline.

The worker-id-to-group index remains as a helper for diagnostics, commands, and
target-worker requests that arrive without a group. It should not be the primary
scheduling reserve path after the contract decision lands.

## Redis Slot Eligibility Shape Direction

This section applies only to registry-owned `slotLifecycleEligible`, not to
transport reachability or worker-originated status strings.

Hard predecessor:

- BCA-0 must inventory candidate acquisition callers. Landed for scheduling
  candidate acquisition; node-group maintenance remains BCA-3 follow-up.
- BCA-1 must decide whether candidate acquisition sees complete candidate sets
  or bounded subsets. Landed for scheduling candidate acquisition as bounded
  source batches.

Selected current Redis shape:

- Keep group/node candidate bucket SETs as source membership.
- Add an adapter-internal sibling lifecycle deadline ZSET for each candidate
  bucket.
- ZSET member: `workerId`.
- ZSET score: heartbeat deadline millis.
- Scheduling acquisition receives `nowMillis` and performs bounded
  `ZRANGEBYSCORE` over the lifecycle deadline ZSET for members with score
  greater than `now`.
- Support/source-only acquisition remains bounded `SRANDMEMBER` against the
  source bucket SET.

Performance model:

- heartbeat/upsert is the high-frequency writer and now writes the canonical
  slot, group heartbeat ZSET, source bucket membership, and each derived
  per-bucket lifecycle deadline ZSET for the worker's approved buckets.
- dispatch-gate and removing-slot changes are lower-frequency lifecycle writers;
  they remove or refresh derived ZSET membership without creating new canonical
  truth.
- scheduling acquisition uses one bounded ZSET read per selected source bucket
  and does not materialize the source bucket SET.
- source guard still performs canonical slot validation, and reserve remains
  the final mutation-time authority for capacity and races.
- stale derived members are correctness-neutral only until source guard or
  reserve rejects them; expired heartbeat members are not returned by the
  deadline ZSET read once `deadline <= nowMillis`.

The selected Redis shape must preserve these rules:

- slot truth remains the canonical registry payload,
- eligibility keys are derived indexes,
- Redis key-family names remain adapter-internal and must not appear in engine,
  worker-runtime, SDK, or transport semantic contracts,
- returning stale derived members is allowed only when source guard or reserve
  rejects them before dispatch binding,
- heartbeat refresh updates the deadline projection atomically with the slot
  update or has an idempotent repair path,
- no Redis slice may consume transport route-owner/presence keys directly.

## Candidate And Evidence Shape Decision

Do not put live runtime evidence into a rule-readable candidate shape just to
remove read calls.

CES-1 must separate:

- candidate identity/source row,
- runtime eligibility evidence,
- diagnostic snapshot.

For the engine scheduling mainline, this is a hard contract cleanup, not a
compatibility exercise. The current slice slimmed `WorkerCandidateRow`
in-place; do not reintroduce `statusName`, `lastHeartbeat`, `available`,
capacity/load, or reachability fields in the hot-path row only because the old
row exposed them.
If an engine-external diagnostic or API surface still wants those fields, it
belongs to the follow-up external-surface roadmap.

The full diagnostic context may include live evidence. Declarative rule context
must continue to exclude reachability, dispatch-enabled status, worker lock,
load, capacity, reserve counts, and admission status unless a separate
Scheduling Plane decision creates a named exception.

## Attribute Index Decision

Attribute indexes are not part of the core slot lifecycle eligibility truth.

The current source-index mechanism is policy-owned:

- `WorkerCandidateBucketPolicy` in `mass-runtime-api` is the registry-facing
  bucket declaration SPI.
- `WorkerCandidateBucketPolicies` in worker-runtime is the current platform
  approved-attribute default.
- `maxBucketFanout()` makes write amplification caller-visible.
- Memory and Redis registries execute the injected policy and must not own or
  hardcode attribute dimensions.
- If a policy returns no source bucket, runtime falls back to the `default`
  bucket, bounded lifecycle-aware acquisition, source guard, and
  metadata/rule filtering.

Future policy-catalog owned index declarations should replace or configure this
source policy explicitly; they should not add Redis-local hardcoded buckets.

## Non-Goals

- No public worker API, SDK, or transport protocol change.
- No server inspection/controller/API DTO, frontend console DTO, SDK-facing
  worker view, or transport semantic contract cleanup in the first mainline
  slice. Those inconsistencies are a follow-up roadmap unless a current slice
  directly changes the surface.
- No all-worker scan.
- No Redis-owned transport reachability truth.
- No use of legacy `Worker.status` / `statusName` as scheduling truth.
- No new `worker:meta:{workerId}`, `worker:available:{shard}`, or other
  writable parallel worker truth.
- No attempt to make Stage-1 final admission. Reserve remains authoritative for
  capacity and concurrent mutation races.
- No Redis Cluster/hash-tag or deployment topology decision.
- No compatibility bridge that keeps old and new scheduling hot paths as two
  permanent tracks.
- No wrapper, facade, or bridge that preserves old
  `WorkerCandidateRuntime` / `WorkerAdmissionRuntime` semantics while claiming
  the new lifecycle mechanism exists elsewhere.

## Follow-Up: Engine-External Surface Consistency

After the current engine scheduling mainline is converged, create or update a
separate roadmap for external surface consistency:

- server worker inspection and API DTOs,
- SDK-facing worker/resource views,
- transport semantic contracts,
- frontend console DTOs,
- public or operator documentation that might expose Redis key shape or old
  worker status vocabulary.

That roadmap should reuse the Redis shape exposure inventory and guards, but it
must not block the current mainline mechanism unless the current slice edits one
of those surfaces.

## Do Not Start With

Do not start by adding a Redis composite set that includes reachability. Current
reachability is not registry-owned.

Do not start by adding a proof-only slot lifecycle source that is not consumed
by `WorkerCandidateRuntime` or its chosen successor.

Do not start by naming new Redis key families in cross-module contracts. First
prove the current engine / worker-runtime mainline does not depend on Redis
keyspace classes, key-family literals, or Redis payload shapes. SDK/server/
transport/frontend surface cleanup is follow-up unless directly touched.

Do not treat the bounded candidate bucket read as the slot lifecycle
eligibility implementation. BCA only bounded source membership acquisition;
CES still needs the lifecycle predicate, performance model, and deadline-aware
projection decision before changing Redis lifecycle keys.

Do not add a plain `available` set without deadline semantics. It will either
return stale workers until cleanup catches up, or push correctness back into
per-candidate engine reads.

## CES-P0: Current Mainline Redis Shape Exposure Guard

Current slice status: mainline guard landed. Engine and worker-runtime main
Java sources have no Redis worker keyspace imports, Redis keyspace types, or
worker Redis key-family literals in the scheduling mainline. SDK, server,
transport, frontend, and operator inspection surfaces remain follow-up inputs
unless directly edited by a later slice.

Goal:

Make Redis physical structure invisible to the current engine / worker-runtime
scheduling mainline before changing Redis shape.

Scope:

- Inventory production imports and literals in the current scheduling mainline:
  - `xa-mass-engine/src/main/java`,
  - `xa-mass-worker-runtime/src/main/java`,
  - runtime API contracts directly consumed by those paths.
- Record, but do not block this roadmap on, Redis shape references in SDK,
  transport, server controller/API/DTO, worker inspection, or frontend console
  surfaces. Those are inputs to the follow-up engine-external surface roadmap
  unless a current slice edits them.
- Classify each Redis reference as:
  - allowed adapter/assembly dependency,
  - forbidden scheduling/candidate read-surface dependency,
  - shared Redis primitive dependency,
  - docs/proof-only key manifest reference,
  - test-only fixture.
- Add or update guards that fail if engine or worker-runtime mainline sources:
  - import `com.xa.mass.runtime.redis.*`,
  - reference `RedisWorkerRegistry`, `RedisWorkerRegistryKeyspace`, or other
    Redis keyspace classes,
  - contain worker Redis key-family literals such as `worker:group`,
    `group:{groupId}:slots`, `bucket-membership`, or future slot-eligibility
    key-family names.
- Decide whether transport's dependency on Redis queue primitives is accepted
  for this roadmap or split into a separate infra-primitives roadmap.

Acceptance:

- Inventory says no engine / worker-runtime scheduling mainline surface depends
  on Redis keyspace classes, key-family names, or Redis payload shapes.
- Any SDK/server/transport/frontend Redis shape exposure found during inventory
  is recorded as follow-up external-surface work unless the current slice edits
  that surface.
- Transport Redis queue primitive use is either accepted as unrelated to worker
  key leakage or recorded for a separate infra-primitives owner.
- A guard exists before CES Redis shape slices run.
- No code behavior changes.

## CES-0: Caller And State Inventory

Goal:

Classify every current hot-path read of reachability, dispatch gate, worker
status, exclusive lock, admission, and release/final state.

Scope:

- Inventory production callers of:
  - `WorkerCandidateRuntime#findWorkerCandidateBatch(...)`,
  - `WorkerSchedulingViewRuntime#getWorkerReachability(...)`,
  - `WorkerSchedulingViewRuntime#isWorkerDispatchEnabled(...)`,
  - `WorkerSchedulingViewRuntime#hasWorkerExclusiveLease(...)`,
  - `WorkerAdmissionRuntime` reserve/confirm/release/claim/final methods,
  - worker-id-only `WorkerRegistry` default methods used by scheduling.
- Separate scheduling hot path, binder/dispatch path, release/final path,
  control/command path, diagnostics, tests, and compatibility callers.
- Classify each state dimension as registry-owned, worker-runtime composed,
  transport-provided, engine-owned, or diagnostic-only.
- Decide whether exclusive lease belongs to slot lifecycle eligibility or
  remains admission/occupancy evidence.

Acceptance:

- Inventory states which reads are hot-path and which are diagnostic/support.
- Inventory states the current owner for each state dimension.
- Inventory states that Redis cannot own reachability unless a projection writer
  is designed in a later slice.
- No code behavior changes.

## CES-1: Mainline Eligibility Contract Split

Current slice status: landed for the engine / worker-runtime mainline. The
production candidate path consumes `slotLifecycleStatus(...)`, target-worker
lookup uses the same source guard, the hot-path candidate row is slimmed and
guarded, and admission lifecycle calls carry `WorkerAdmissionTarget`.

Goal:

Define the production contracts before changing runtime implementations.

Scope:

- Define registry-owned `slotLifecycleEligible`.
- Treat `WorkerCandidateRuntime` or its explicit successor as the production
  candidate contract. The slot-lifecycle source/validator must be consumed by
  that mainline contract in the implementation slice; do not create a separate
  source that only proves the idea beside the real scheduling path.
- Name the slot-lifecycle source contract that returns or validates
  registry-owned eligible worker ids for a worker group. If it is a separate
  local type such as `SlotLifecycleCandidateSource` or
  `SlotLifecycleEligibilityRuntime`, CES-1 must also state exactly how
  `WorkerCandidateRuntime` is replaced, narrowed, or backed by it in the same
  production mainline.
- Define the relation to BCA: CES owns the lifecycle predicate and validator;
  BCA owns complete-set versus bounded-subset semantics, sampling order, paging,
  and read budget. Current BCA-1 retargeted
  `WorkerRegistry#acquireCandidates(...)` to a bounded source-batch contract.
- Define worker-runtime `dispatchEligible` as a composition layer that may still
  read `WorkerReachabilityView`.
- Add or refine group-scoped hot-path methods where needed.
- Mark worker-id-only scheduling lifecycle calls as non-mainline support usage
  or remove them from the engine lifecycle path. Do not keep them as a second
  production track.
- Keep target-worker selection group-aware. A target worker must not bypass the
  selected eligibility predicate unless an explicit force/diagnostic path is
  added later. Target-worker lookup must call the same slot-lifecycle
  source/validator as group candidate acquisition; `sourceGuard(...)` alone is
  not enough.
- Define candidate identity/source row, runtime eligibility evidence, and
  diagnostic snapshot as separate shapes.
- Keep the candidate row target shape explicit. Current mainline uses slimmed
  `WorkerCandidateRow` as the hot-path source/static-metadata row carrying
  `workerId`, `workerGroupId`, adapter/node identity, online strategy, agent
  version, and allowed static attributes. Live or diagnostic fields such as
  `statusName`, `lastHeartbeat`, worker-level supported project/event lists,
  `available`, capacity/load/reserve counters, and reachability belong in
  runtime evidence or diagnostic snapshot. Engine mainline has no compatibility
  exception here.
- Define a lifecycle admission target shape for the current engine path. Reserve,
  confirm, release, claim, and final must share the same group-scoped evidence
  model when the dispatch lifecycle creates that evidence.

Acceptance:

- Contract names `workerGroupId` as required for scheduling reserve when the
  match path has group evidence.
- Contract names the slot-lifecycle source/validator and states how
  `WorkerCandidateRuntime` is replaced, narrowed, or backed by it. A source that
  is not consumed by the production candidate runtime does not satisfy CES-1.
- Contract states that `WorkerRegistry#acquireCandidates(...)` is a bounded
  source-membership acquisition contract after BCA-1. Slot lifecycle validation
  still happens through the CES predicate before Stage-2 matching/reserve.
- Contract does not require Redis/registry to own reachability.
- Contract says whether exclusive lease is included in slot lifecycle
  eligibility or checked after candidate acquisition.
- Target-worker lookup is explicitly bound to the same slot-lifecycle
  source/validator as non-target acquisition.
- Contract names the hot-path candidate row shape and where runtime evidence and
  diagnostic snapshot fields live; no engine-mainline compatibility exception
  keeps live/diagnostic fields in the candidate row.
- Contract separates rule-readable candidate/rule context from live runtime
  evidence.
- `WorkerMatchContext#getRuleContext()` still excludes live reachability,
  dispatch gate, lock, load, capacity, reserve, and admission evidence.
- Contract names the group-scoped admission lifecycle target that current engine
  scheduling, binder, release, and final paths will carry.
- Memory and Redis implementations have a shared contract target.

## CES-2: Memory Mainline Slot Lifecycle Prototype

Current slice status: mainline validator landed. The implementation uses the
shared registry predicate `slotLifecycleStatus(groupId, workerId, now)` in the
production `WorkerCandidateIndex` source guard rather than introducing a
separate proof-only projection. A physical eligible-worker source/projection is
still a later Redis/CES-3-dependent phase.

Goal:

Prove the registry-owned predicate through the current engine / worker-runtime
mainline in the simpler implementation before changing Redis key shape.

Scope:

- Maintain or exercise a group-scoped slot lifecycle eligible source/validator
  in the memory registry or worker-runtime owner. A proof-only projection that
  is not consumed by the production candidate path is not sufficient.
- Implement or exercise the named slot-lifecycle source/validator from CES-1.
  Redis candidate acquisition may already use the BCA-1 bounded source-batch
  contract, but CES-2 is satisfied only when the memory/mainline slot lifecycle
  validator is consumed through `WorkerCandidateRuntime` or its selected
  successor.
- Update tests for heartbeat freshness, dispatch disabled, removing slot, and
  target-worker behavior.
- Add a target-worker proof that the fixed-worker path calls the same
  slot-lifecycle source/validator as group acquisition before reserve.
- Keep reachability composition outside the registry-owned projection unless
  CES-1 designed a real projection writer.
- Keep reserve as final admission authority.

Acceptance:

- A worker leaves the slot lifecycle eligible source when heartbeat is stale,
  dispatch is disabled, or the slot is removing.
- A worker does not leave the slot lifecycle eligible source only because
  capacity is full; reserve rejects capacity-full workers.
- `WorkerRegistry#acquireCandidates(...)` remains source-membership acquisition
  under BCA-1 bounded source-batch semantics; it is not the slot lifecycle
  eligibility predicate by itself.
- CES-2 is not complete unless the memory slot-lifecycle source/validator is
  consumed by `WorkerCandidateRuntime` or its selected successor in the matching
  path.
- If reachability is still read separately, tests prove non-ONLINE reachability
  still prevents binding before dispatch.
- Target-worker scheduling still fails when the selected eligibility predicate
  rejects the worker, and this proof does not rely only on later reserve
  rejection.

## BCA Gate: Candidate Acquisition Contract

Goal:

Record the candidate acquisition contract that Redis lifecycle work must build
on, without confusing bounded source membership with slot lifecycle
eligibility.

Scope:

- Use the landed BCA-0/BCA-1 decision: scheduling candidate acquisition sees an
  implementation-provided bounded source batch.
- Treat random sampling, ordered deadline reads, and cleanup thresholds as Redis
  implementation choices that must still satisfy the bounded source-batch
  contract.
- Keep BCA-3 node-group maintenance pagination separate from scheduling
  candidate acquisition unless a later slice touches adapter-node group gate
  mutation.

Acceptance:

- Redis slot lifecycle read-shape replacement slices cite the BCA-1 bounded
  source-batch outcome.
- No Redis slot lifecycle projection lands without CES-3 performance evidence.

## CES-3: Mainline Performance Model And Redis Shape Evidence

Current slice status: landed for the current scheduling candidate path. The
selected shape is source bucket SET plus sibling per-bucket lifecycle deadline
ZSET. Evidence is covered by shared memory/Redis `WorkerRegistry` contract tests,
Redis-specific projection tests, and architecture guards that reject full-bucket
`SMEMBERS` on candidate acquisition and require the Redis scheduling path to
read the lifecycle deadline projection.

Goal:

Establish the production performance model, then select a Redis physical shape
for registry-owned slot lifecycle eligibility using evidence after the BCA gate.

Scope:

- Define the hot-path performance model before choosing Redis keys:
  - expected frequency of heartbeat, dispatch-gate, removing, and reserve/final
    mutations,
  - per-scheduling-attempt budget for registry reads, reachability reads,
    admission reads, and Lua/multi-command mutations,
  - stale member tolerance and the deadline after which stale source rows must be
    rejected before binding,
  - when batch evidence or a projection writer is required instead of
    per-candidate reads,
  - expected write amplification for each candidate Redis shape.
- Implement a focused Redis test/benchmark fixture for:
  - heartbeat refresh write cost,
  - dispatch-gate add/remove cost,
  - bounded slot lifecycle eligible read cost at small, medium, and large group
    sizes,
  - stale/expired member cleanup cost.
- Compare ZSET-only and SET plus deadline ZSET against the current bounded
  source-bucket `SRANDMEMBER` baseline under BCA-1 semantics.
- Decide whether deadline updates need coalescing, such as only writing when
  the deadline bucket changes.

Acceptance:

- Roadmap or inventory records the hot-path read/write budget and stale
  tolerance before a Redis physical shape is selected.
- Roadmap or inventory records the selected Redis shape and why.
- Test evidence states expected complexity and observed cost.
- The selected shape has an explicit cleanup/repair path.
- No production behavior change is required in this slice unless the fixture is
  implemented as tests only.

## CES-4: Redis Slot Lifecycle Implementation

Current slice status: landed for Redis scheduling acquisition. `RedisWorkerRegistry`
maintains lifecycle deadline ZSETs beside group/node source bucket SETs. Slot
upsert/heartbeat refresh, dispatch-gate mutation, removing-slot mutation, and
bucket cleanup update or remove the derived projection. `WorkerCandidateIndex`
passes a single scheduling `nowMillis` into acquisition and source guard.

Goal:

Implement the selected deadline-aware group slot lifecycle source in Redis.

Scope:

- Update `RedisWorkerRegistry` slot upsert, heartbeat refresh, dispatch-gate
  mutation, removing-slot mutation, and cleanup paths to maintain the derived
  slot lifecycle projection.
- Ensure mutations are atomic with the slot write or have an idempotent repair
  path.
- Keep slot truth canonical. The eligibility key is a derived source index.
- Preserve source guard and reserve revalidation.
- Do not write reachability into Redis in this slice.

Acceptance:

- Redis candidate acquisition can read slot-lifecycle-eligible group candidates
  without materializing unrelated or registry-ineligible workers.
- Expired heartbeat members are not returned as accepted slot-lifecycle
  candidates after deadline filtering.
- Dispatch-disabled and removing workers are removed or rejected before engine
  ranking.
- Redis and memory registry contract tests agree.

## CES-5: Reachability Composition Decision

Current slice status: decision landed. Keep the current per-candidate
`WorkerReachabilityView` read after registry-owned slot lifecycle filtering.
No Redis registry reachability ownership is introduced. A future batch
reachability read or projection writer requires a separate worker-runtime owner
decision and proof.

Goal:

Decide how `WorkerReachabilityView` participates after slot lifecycle
eligibility exists.

Allowed outcomes:

- Keep the current per-candidate reachability read after slot lifecycle
  acquisition. This is the selected current mainline outcome.
- Add a worker-runtime reachability projection writer that composes
  `dispatchEligible` without moving transport route-owner truth.
- Add a bounded batch reachability read surface if the owner can prove it
  without leaking transport key shape.

Acceptance:

- The selected approach names the writer/reader owner.
- Transport route-owner leases remain transport truth.
- Redis registry does not read transport presence keys directly.
- Non-ONLINE reachability still prevents dispatch binding.

## CES-6: Group-Scoped Admission Target

Current slice status: engine mainline landed. `WorkerAdmissionTarget` is the
engine-facing admission lifecycle target, runtime work/result evidence carries
`workerGroupId`, and current scheduling/binder/release/final paths use
group-scoped admission calls. Interface docs, worker-runtime owner docs, and
engine architecture guards now lock that boundary. Eventual cleanup of
low-level worker-id support SPI remains only for support paths where group
evidence is genuinely absent.

Goal:

Converge the current engine scheduling, binder, release, and final paths onto a
single group-scoped admission lifecycle target.

Scope:

- Retarget reserve, confirm, release, claim, and final to a shared admission
  target shape such as `(groupId, workerId, taskId, permits)` where the current
  engine lifecycle creates or can carry group evidence.
- Inventory confirm/release/claim/final call sites and decide where group
  evidence must be stored or recovered from runtime work evidence.
- Decide whether `TaskDispatchBinding`, `ClaimedTaskWork`, `ActiveLeaseRecord`,
  or a related runtime work record must carry `workerGroupId`.
- If an event path currently exposes only `workerId`, resolve group evidence
  from runtime-owned dispatch/lease/work evidence; do not rely on a registry
  reverse lookup as the production lifecycle mechanism.
- Worker-id-only admission calls may remain only for diagnostic/control/support
  paths outside the current engine scheduling lifecycle, and must be named as
  such.
- Update `WorkerAdmissionRuntime` interface docs, `WorkerRegistry` javadocs,
  and worker-runtime README/CONTRACTS in the same slice so the hot path no
  longer encourages worker-id-only admission when group evidence is present.
- Add a focused CES-6 guard that fails if the current scheduling lifecycle drops
  known or recoverable `workerGroupId` before admission mutation, or if owner
  docs still describe worker-id-only admission as the preferred scheduling hot
  path.

Acceptance:

- Scheduling reserve no longer drops known `workerGroupId`.
- Current engine scheduling, binder, release, and final paths carry or resolve
  group evidence through runtime-owned dispatch/lease/work records.
- Confirm/release/claim/final in that current engine lifecycle are group-scoped;
  worker-id-only methods are not accepted as the production lifecycle path.
- No release/final path relies on an unstated group reverse lookup assumption.
- Interface docs and worker-runtime owner docs match the implemented admission
  boundary in this slice; CES-9 does not carry this correction for CES-6.
- The CES-6 guard is in place before later engine hot-path retargeting relies on
  group-scoped reserve.

## CES-7: Engine Hot Path Retarget

Current slice status: landed for the current engine mainline. Stage-1
production candidate acquisition now carries scheduling `nowMillis`; memory
filters the source batch by slot lifecycle and Redis reads per-bucket lifecycle
deadline projections before matching/ranking. Engine admission calls are
group-scoped and the hot-path candidate row has been slimmed and guarded.
Stage-2 no longer rereads `isWorkerDispatchEnabled(...)` or rejects candidates
through a duplicate dispatch-gate predicate. Reachability is still read
separately through the current worker-runtime view by the CES-5 decision.

Goal:

Move engine scheduling to consume the selected eligibility surfaces while
preserving engine ownership of ranking and binding.

Scope:

- Retarget `WorkerCandidateRuntime`, `WorkerCandidateSourceOwner`,
  `WorkerCandidateIndex`, or their selected successors so Stage-1 starts from
  slot lifecycle eligibility through the production matching path.
- Apply the CES-5 reachability composition decision.
- Remove per-candidate dispatch-gate reads from the main scheduling path when
  slot lifecycle evidence already carries that fact.
- Retarget worker admission calls according to CES-6.
- Keep ranking, rule evaluation, allocation budget, and dispatch binding in
  engine.

Acceptance:

- The engine matching strategy consumes the selected mainline candidate contract;
  no proof-only slot lifecycle source remains unused beside it.
- Scheduling lifecycle hot path does not call worker-id-only admission mutation
  methods when group evidence is present or recoverable from runtime lifecycle
  evidence.
- Per-candidate dispatch-gate reads are gone from the main scheduling path or
  explicitly classified as diagnostics.
- Reachability handling matches the CES-5 decision.
- Stage-2 reserve still rejects stale, disabled, removing, capacity-full, and
  group-mismatched workers.
- Existing matching tests still prove target worker and WorkerGroup narrowing.

## CES-8: Policy-Owned Attribute Indexing

Current slice status: landed for the current source-bucket mechanism. The
runtime-api `WorkerCandidateBucketPolicy` is the owner for optional candidate
bucket dimensions and now exposes `maxBucketFanout()` so write amplification is
visible. `WorkerCandidateBucketPolicies` remains the current platform default
approved-attribute policy in worker-runtime. Memory and Redis registries execute
the injected policy and are guarded against hardcoded attribute dimensions.
Absent or empty policy output falls back to the `default` source bucket plus
bounded lifecycle-aware acquisition, source guard, and metadata/rule filtering.

Goal:

Separate lifecycle eligibility from optional policy-owned attribute indexing.

Scope:

- Decide whether current `WorkerCandidateBucketPolicies` remains a platform
  default, becomes policy-provided, or is replaced by a policy-owned index
  declaration.
- Define fallback behavior when a policy does not declare an attribute index:
  bounded lifecycle-eligible sampling plus metadata filtering.
- Avoid adding new hardcoded attribute keys.

Acceptance:

- Attribute index dimensions have a named owner and caller-visible cost.
- The absence of an index is supported by bounded fallback behavior.
- Existing approved bucket keys are documented as current implementation detail
  or retargeted to the selected policy owner.

## CES-9: Guards, Docs, And Residue

Goal:

Prevent regression to worker-id-only hot-path calls, rule-visible live evidence,
and stale candidate source semantics.

Scope:

- Add or update architecture guards for:
  - engine code must not call low-level Redis/registry key-shape methods,
  - scheduling reserve/confirm/release/claim/final must pass or resolve
    `workerGroupId` through runtime-owned lifecycle evidence when group evidence
    is present in the current engine path,
  - the engine matching strategy must consume the selected mainline candidate
    contract rather than an unused proof-only source,
  - hot-path candidate source rows must not carry live lifecycle, dispatch,
    load, capacity, capability-list, or diagnostic fields,
  - legacy `statusName` must not become scheduling truth,
  - registry slot eligibility keys are derived indexes, not canonical worker
    truth,
  - rule context must not expose live reachability, dispatch gate, lock, load,
    capacity, reserve, or admission evidence.
- Update `xa-mass-worker-runtime/README.md`,
  `xa-mass-worker-runtime/CONTRACTS.md`,
  `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`, and
  `doc/PROOF_REGISTRY.md` when code lands.
- Run a residue scan for old candidate-source wording before marking this
  roadmap complete.

Acceptance:

- Guards fail if the scheduling lifecycle hot path returns to worker-id-only
  admission mutation when group evidence is present or recoverable from runtime
  lifecycle evidence.
- Guards fail if slot lifecycle eligibility is implemented beside, but not
  consumed by, the production candidate runtime.
- Guards fail if `WorkerCandidateRow` regrows live or diagnostic worker evidence
  instead of remaining a source/static-metadata row.
- Guards fail if live runtime evidence becomes rule-readable.
- Docs describe implemented behavior only after implementation lands.
- No stale active roadmap/doc claims the old bucket source is the lifecycle
  eligibility source.

## Suggested Implementation Order

1. CES-P0 current mainline Redis shape exposure guard - current mainline scan
   and guard landed; external surfaces remain follow-up.
2. CES-0 caller and state inventory - enough inventory completed for the
   current engine mainline; external surfaces remain follow-up.
3. CES-1 mainline eligibility contract split - landed for slot lifecycle
   validator, group-scoped admission, and hot-path candidate row/evidence split.
4. CES-6 group-scoped admission target contract - engine mainline landed.
5. CES-2 memory mainline slot lifecycle prototype - validator proof landed in
   the production candidate path.
6. CES-5 reachability composition decision - landed; current per-candidate
   `WorkerReachabilityView` read is preserved and Redis registry is guarded
   against transport presence ownership.
7. CES-7 engine hot path retarget - landed for the current engine mainline;
   scheduling acquisition now passes `nowMillis` into memory/Redis
   lifecycle-aware acquisition and Stage-2 no longer rereads dispatch gate as a
   candidate predicate. Reachability composition optimization remains a later
   phase.
8. BCA-0/BCA-1 candidate acquisition contract gate for Redis read shape -
   scheduling candidate acquisition landed as bounded source-batch semantics.
   BCA-3 node-group maintenance pagination remains in BCA and is not a blocker
   for CES scheduling candidate work.
9. CES-3 mainline performance model and Redis lifecycle shape evidence -
   landed for source SET plus per-bucket lifecycle deadline ZSET.
10. CES-4 Redis deadline-aware slot lifecycle implementation - landed for
    current scheduling candidate acquisition.
11. CES-8 policy-owned attribute indexing - landed for the current source-bucket
    mechanism with policy-owned dimensions, declared fan-out cost, and
    Redis/memory guards against hardcoded attribute dimensions.
12. CES-9 guards, docs, and residue cleanup - candidate row shape,
    group-scoped admission, current mainline Redis keyshape, scheduling clock,
    Redis lifecycle acquisition, dispatch-gate reread, and attribute-policy
    guards/docs landed. Engine-external surface cleanup remains follow-up.

The remaining active work is no longer about the engine mainline candidate
mechanism. Follow-up should focus on engine-external surface cleanup and, if
needed, a separate reachability-projection optimization decision.

## Verification Candidates

Current-roadmap verification focuses on the engine / worker-runtime mainline.
Server, SDK, transport, worker inspection, and frontend console surface scans are
follow-up roadmap inputs unless the current slice edits those surfaces.

Current slice verification run on 2026-06-12:

```powershell
rg -n "reserveWorkerCapacity\([^)]*,[^)]*\)|releaseWorkerReservation\([^)]*,[^)]*\)|confirmWorkerReservation\([^)]*,[^)]*\)|recordWorkClaimed\([^)]*,[^)]*\)|recordWorkFinal\([^)]*,[^)]*\)" --glob "*.java" xa-mass-worker-runtime xa-mass-engine xa-mass-testing platform_infra sdk xa-mass-server transport
rg -n "com\.xa\.mass\.runtime\.redis|RedisWorkerRegistry|RedisWorkerRegistryKeyspace|Redis.*Keyspace" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob "!**/target/**"
rg -n -F "worker:group" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob "!**/target/**"
rg -n -F "group:{groupId}:slots" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob "!**/target/**"
rg -n -F "bucket-membership" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob "!**/target/**"
rg -n "candidateRow\.(statusName|lastHeartbeat|available|supportedProjects|supportedEventCodes|maxConcurrentWork|createTime|updateTime)\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java xa-mass-engine/src/test/java xa-mass-worker-runtime/src/test/java --glob "*.java"
rg -n "WorkerAdmissionTarget\.(workerLevel|groupScoped)|new\s+WorkerAdmissionTarget|reserveWorkerCapacity\([^\n]*workerId|confirmWorkerReservation\([^\n]*workerId|releaseWorkerReservation\([^\n]*workerId|recordWorkClaimed\([^\n]*workerId|recordWorkFinal\([^\n]*workerId" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java xa-mass-testing/src/main/java --glob "*.java"
.\mvnw.cmd -pl xa-mass-worker-runtime -am "-Dtest=WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,WorkerManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl platform_infra/mass-runtime-memory -am "-Dtest=InMemoryTaskResultRuntimeContractTest,InMemoryTaskWorkRuntimeTest,InMemoryWorkerRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl platform_infra/mass-runtime-redis -am "-Dtest=RedisTaskResultRuntimeContractTest,RedisTaskResultRuntimeTest,RedisWorkerRegistryTest,RedisTaskWorkRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=SimpleTaskDispatchBinderTest,TaskResourceReleaseListenerTest,WorkerDispatchResourceReleaserTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=WorkerMatchContextTest,RuleBasedTaskWorkerMatchingStrategyTest,SimpleTaskDispatchBinderTest,TaskWorkerAssignListenerTest,WorkerDispatchResourceReleaserTest,TaskResourceReleaseListenerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=EngineSchedulingCoreArchitectureGuardTest#workerCandidateRowCarriesOnlySourceIdentityAndStaticMetadata" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=EngineSchedulingCoreArchitectureGuardTest#workerAdmissionMutationSurfaceStaysGroupScoped+engineSchedulingLifecycleBuildsGroupScopedAdmissionTargets" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=EngineSchedulingCoreArchitectureGuardTest#engineAndWorkerRuntimeMainlineDoNotExposeRedisWorkerKeyShape" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=EngineSchedulingCoreArchitectureGuardTest#redisWorkerRegistryDoesNotConsumeTransportPresenceKeys" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "smembers\(bucketKey\)|srandmember\(bucketKey|candidateBucketLifecycleDeadlinesZset|zrangebyscore" platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistry.java
.\mvnw.cmd -pl platform_infra/mass-runtime-memory -am "-Dtest=InMemoryWorkerRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl platform_infra/mass-runtime-redis -am "-Dtest=RedisWorkerRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-worker-runtime -am "-Dtest=WorkerCandidateIndexTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=EngineSchedulingCoreArchitectureGuardTest#redisWorkerRegistryCandidateAcquisitionDoesNotMaterializeFullBucket+workerCandidateIndexPassesSchedulingClockToCandidateAcquisition+engineStageTwoDoesNotRereadDispatchGateAsCandidatePredicate+workerRegistryImplementationsDoNotOwnAttributeBucketDimensions" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,RuleBasedTaskWorkerMatchingStrategyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-testing -am -DskipTests test
```

Notes:

- The admission scan intentionally still reports low-level `WorkerRegistry`
  default methods and tests; it should not report worker-id-only
  `WorkerAdmissionRuntime` calls in the engine scheduling lifecycle.
- The Redis key exposure scans should return no matches for engine and
  worker-runtime main sources; the focused guard now enforces that mainline
  boundary.
- The candidate-row old-field scan should return no matches:
  `rg -n "candidateRow\.(statusName|lastHeartbeat|available|supportedProjects|supportedEventCodes|maxConcurrentWork|createTime|updateTime)\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java xa-mass-engine/src/test/java xa-mass-worker-runtime/src/test/java --glob "*.java"`.
- The admission-target scan may show expected `WorkerAdmissionTarget.groupScoped`
  construction and the target record's own constructor; it must not show
  `WorkerAdmissionTarget.workerLevel(...)` or engine calls that pass only
  `workerId` into reserve/confirm/release/claim/final admission mutations.
- `TaskWorkerEligibilityTest#drainingWorkerIsExcludedFromNewAssignmentsUntilAvailableAgain`
  now proves dispatch-disabled workers are filtered by the Stage-1 slot
  lifecycle source guard before Stage-2 rejection recording; non-ONLINE
  reachability still produces Stage-2 rejection evidence through the current
  `WorkerReachabilityView` composition path.
- Redis scheduling acquisition now proves the production path reads
  `candidateBucketLifecycleDeadlinesZset(bucketKey)` with bounded
  `ZRANGEBYSCORE`, while support/source-only acquisition remains bounded
  `SRANDMEMBER`. The guard rejects `SMEMBERS(bucketKey)` and fails if
  `WorkerCandidateIndex` stops passing scheduling `nowMillis` into acquisition.
- Stage-2 no longer rereads dispatch gate as a candidate predicate; the focused
  guard rejects `WorkerSchedulingCandidateEnumerator` calls to
  `isWorkerDispatchEnabled(...)` and matching-strategy use of
  `dispatchEnabled()`.
- Attribute source-bucket indexing is policy-owned; the focused guard rejects
  memory/Redis registry code that interprets worker attributes or hardcodes
  route-attribute bucket dimensions, and requires `WorkerCandidateBucketPolicy`
  to expose bucket fan-out cost.
- Full `EngineSchedulingCoreArchitectureGuardTest` currently has unrelated
  resolved-scheduling-plane guard failures; do not use that full class as the
  current-slice gate until those adjacent guard rows are repaired or split.

```powershell
rg -n "com\\.xa\\.mass\\.runtime\\.redis|RedisWorkerRegistry|RedisWorkerRegistryKeyspace|Redis.*Keyspace" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n -F "worker:group" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n -F "group:{groupId}:slots" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n -F "bucket-membership" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "getWorkerReachability\\(|isWorkerDispatchEnabled\\(|hasWorkerExclusiveLease\\(|reserveWorkerCapacity\\(|tryReserve\\(" xa-mass-engine xa-mass-worker-runtime platform_infra --glob '!**/target/**'
rg -n "statusName\\(|workerStatusName\\(|WorkerStatus\\." xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "getRuleContext|ruleContext|transportReachability|isWorkerAvailable|workerActiveLeaseCount|workerReservedCount|workerDeclaredCapacity" xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
.\mvnw.cmd -pl xa-mass-worker-runtime -am "-Dtest=WorkerCandidateIndexTest,WorkerManagerTest,WorkerAdmissionOwnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl platform_infra/mass-runtime-memory -am "-Dtest=InMemoryWorkerRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl platform_infra/mass-runtime-redis -am "-Dtest=RedisWorkerRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine "-Dtest=WorkerStateReportSchedulingIntegrationTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,WorkerMatchContextTest,WorkerSchedulingCandidateEnumeratorTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest" test
.\mvnw.cmd -pl xa-mass-engine "-Dtest=EngineSchedulingCoreSuite" test
```

Current CES-3 proof is command-shape and contract-test based. Add a true Redis
latency/throughput benchmark only when deployment sizing needs observed timing
numbers beyond the bounded-command shape.

## Completion Criteria

This roadmap is complete when:

1. Engine and worker-runtime scheduling mainline surfaces do not expose Redis
   keyspace classes, key-family literals, encoded Redis payload shapes, or
   Redis-only DTOs for worker scheduling/candidate data.
2. Guards prevent Redis worker key shape from leaking into that current
   mainline; engine-external surface cleanup is recorded for a follow-up roadmap
   when needed.
3. `WorkerCandidateRuntime` or its selected successor is the production
   slot-lifecycle candidate contract; no proof-only source remains beside the
   real matching path.
4. The slot-lifecycle source/validator contract is named, and its relation to
   BCA is documented; `WorkerRegistry#acquireCandidates(...)` uses the landed
   BCA-1 bounded source-batch contract and is not treated as lifecycle
   eligibility by itself.
5. Target-worker lookup and group candidate acquisition use the same
   slot-lifecycle predicate before reserve.
6. Hot-path candidate identity/source rows are separated from runtime
   eligibility evidence and diagnostic snapshots; old diagnostic/status fields
   are not kept in the engine-mainline candidate row for compatibility.
7. Current engine scheduling, binder, release, and final paths carry or resolve
   group evidence through runtime-owned dispatch/lease/work records.
8. Worker runtime exposes a WorkerGroup-scoped slot lifecycle eligibility source
   without claiming Redis owns reachability.
9. Reachability composition is either preserved through the current
   `WorkerReachabilityView` read or replaced by a named worker-runtime
   projection writer with proof.
10. The source predicate covers registry-owned heartbeat deadline, dispatch gate,
   removing state, and group membership.
11. BCA-1 has selected candidate acquisition semantics before Redis slot
   lifecycle read-shape replacement lands.
12. The hot-path performance model records read/write budget, stale tolerance,
    deadline behavior, and projection/batch thresholds before Redis physical
    shape is selected.
13. Redis implementation uses a deadline-aware derived index for registry-owned
   slot lifecycle eligibility and no full-bucket materialization is required for
   that lifecycle read under the selected BCA contract.
14. Scheduling reserve passes `workerGroupId` when group evidence is present;
   confirm/release/claim/final in the current engine lifecycle are group-scoped.
   Admission interfaces, owner docs, and guards no longer encourage
   worker-id-only admission for that hot path.
15. Attribute indexing is explicitly policy-owned or documented as current
   bounded implementation detail with a fallback path.
16. Memory and Redis implementations satisfy the same contract tests.
17. Docs and guards prevent legacy `statusName`, all-worker scans, Redis-owned
    reachability, rule-readable live evidence, or derived Redis indexes from
    becoming scheduling truth.
