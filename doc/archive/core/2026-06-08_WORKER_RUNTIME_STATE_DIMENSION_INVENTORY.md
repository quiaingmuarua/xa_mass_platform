# Worker Runtime State Dimension Inventory

Status: archived WRSI inventory and residue audit; residual readiness and
physical-split work is owned by
[WORKER_RUNTIME_STATE_READINESS_AND_PHYSICAL_SPLIT_ROADMAP.md](../../../roadmap/WORKER_RUNTIME_STATE_READINESS_AND_PHYSICAL_SPLIT_ROADMAP.md).

Companion roadmap:
[2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md](2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md)

This inventory classifies current worker runtime facts before changing Redis
worker keyspace behavior. It documents current implementation truth, not target
completion.

## Field Classification

### `WorkerMeta`

| Field | Classification | Owner / notes |
| --- | --- | --- |
| `workerId` | identity | worker declaration projected to runtime slot |
| `groupId` | worker universe membership | worker declaration / WorkerGroup boundary |
| `adapterNodeId` | transport/node locality | worker declaration; used for candidate narrowing |
| `adapterId` | diagnostic / transport hint | not scheduling truth by itself |
| `transportHint` | diagnostic / transport hint | not reachability truth |
| `attributes` | candidate hint / scheduling attributes | may feed approved candidate buckets and target-attribute checks |
| `agentVersion` | readiness diagnostic input | not currently admission truth |
| `runtimeVersion` | readiness diagnostic input | not currently admission truth |
| `lastHeartbeatMillis` | reachability evidence | registry heartbeat freshness validates admission |
| `diagnosticStatus` | display-only residue | legacy worker status projection; not scheduling truth |

### `WorkerSlot`

| Field | Classification | Owner / notes |
| --- | --- | --- |
| `meta` | current physical aggregate | current Redis slot payload; not target logical split |
| `declaredCapacity` | occupancy truth | worker registry admission owner |
| `eventBindingCeiling` | eligibility/capability guard | derived from WorkerGroup capability truth |
| `activeLeaseCount` | worker occupancy truth | worker registry counter; task lease lifecycle remains `TaskWorkRuntime` |
| `reservedCount` | worker occupancy truth | worker registry reservation truth |
| `activeLeaseCountByTask` | worker occupancy read index | worker registry counter, not task lease lifecycle owner |
| `disabledSources` | readiness / dispatch-gate truth | worker runtime control/admission owner |
| `exclusiveLeaseHeld` | occupancy / lane-lock truth | worker registry exclusive lease |
| `removing` | readiness / drain-removal truth | registry slot lifecycle |
| `removingReason` | diagnostic evidence | not scheduling truth by itself |

### `WorkerRuntimeStateRecord`

| Field | Classification | Owner / notes |
| --- | --- | --- |
| `statusName` | display-only residue | compatibility read field |
| `lastHeartbeat` | reachability diagnostic | read projection of heartbeat evidence |
| `reachability` | reachability dimension | `UNKNOWN` is observation gap, not reachable |
| `dispatchEnabled` | readiness input | source-scoped dispatch gate projection |
| `removing` | readiness input | drain/removal projection |
| `capacityPermits` | occupancy evidence | read projection of registry capacity |
| `reservedPermits` | occupancy evidence | read projection of registry reservation |
| `exclusiveLeaseHeld` | occupancy evidence | read projection of registry lock |
| `observedAt` | diagnostic timestamp | not scheduling truth |

### `WorkerResourceRecord`

| Field family | Classification | Owner / notes |
| --- | --- | --- |
| `workerId`, group/node/adapter fields | declaration read | worker declaration boundary |
| `statusName` | display-only residue | must not drive scheduling |
| `lastHeartbeat` | reachability diagnostic | read projection only |
| `supportedProjects`, `supportedEventCodes` | compatibility read hints | WorkerGroup capability remains truth |
| `attributes`, `maxConcurrentWork` | declaration / scheduling facts | projected into runtime slot |

### `WorkerSchedulingView`

| Field family | Classification | Owner / notes |
| --- | --- | --- |
| worker identity / group / attributes | static scheduling read | candidate row plus WorkerGroup capability view |
| `reachability` | live reachability evidence | full context diagnostic; prefilter uses it before rule evaluation |
| `dispatchEnabled` | readiness/dispatch-gate evidence | full context diagnostic; prefilter uses it before rule evaluation |
| `workerLocked` | occupancy/lock evidence | full context diagnostic; prefilter uses it before rule evaluation |
| `WorkerLoadSnapshot` | occupancy evidence | derived from worker registry counters |
| `readinessState()` | derived diagnostic dimension | not rule context |
| `occupancyState()` | derived diagnostic dimension | not rule context |

## Redis Worker Registry Key Classification

Current keyspace owner:
`platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistryKeyspace.java`

| Key shape | Classification | Notes |
| --- | --- | --- |
| `...:worker:group` | lookup index | `workerId -> groupId`; supports bounded lookup |
| `...:groups` | group index | supports bounded group-local heartbeat cleanup |
| `...:group:{groupId}:heartbeat:0` | reachability truth/index | score is heartbeat deadline; admission revalidates freshness |
| `...:exclusive-leases` | occupancy index mirror | slot payload also carries `exclusiveLeaseHeld` |
| `...:group:{groupId}:slots` | current physical canonical aggregate | stores encoded `WorkerSlot`; WRSI-2C keeps this canonical for this roadmap |
| `...:group:{groupId}:bucket:{candidateBucketKey}:workers` | candidate hint | stale members must not bind without reservation validation |
| `...:group:{groupId}:buckets` | cleanup discovery hint | not scheduling truth |
| `...:group:{groupId}:node:{adapterNodeId}:bucket:{candidateBucketKey}:workers` | candidate hint | node-scoped bucket |
| `...:group:{groupId}:node-buckets` | cleanup discovery hint | not scheduling truth |
| `...:group:{groupId}:worker:{workerId}:bucket-membership` | cleanup residue/index | tracks bucket membership for bounded removal |
| `...:task:{taskId}:active-workers` | occupancy read index | worker-side active projection; task lease lifecycle remains `TaskWorkRuntime` |
| `...:task:{taskId}:worker-active-count` | occupancy read index | worker-side task count projection |

## Candidate Bucket Vocabulary

Current candidate-bucket vocabulary is a candidate-partition implementation:

| Symbol | Classification |
| --- | --- |
| `WorkerCandidateBucketPolicies` | shared approved-attribute bucket strategy |
| `WorkerCandidateBucketPolicy` | shared candidate bucket protocol |
| `WorkerTaskSelector#candidateBucketKeys` | logical selector field |
| `WorkerCandidateIndex#sourceGuard(...)` | source membership validation |
| `WorkerRegistry#acquireCandidates(... candidateBucketKey ...)` | low-level candidate acquisition |
| Redis `bucket:{candidateBucketKey}:workers` keys | physical candidate hint |

The current owner is `WorkerCandidateBucketPolicy` /
`WorkerCandidateBucketPolicies` across task request, worker membership, and
source guard. Route attributes remain strategy inputs, not keyspace names or
policy truth.

## External Read Fallout

Current external/operator read surfaces still expose one display status:

- `WorkerResourceRecord#statusName`
- `WorkerRuntimeStateRecord#statusName`
- `WorkerResourceOwner` projection from `WorkerMeta#diagnosticStatus`
- server worker/catalog `status` responses
- frontend worker/runtime status badges

These fields may remain as display compatibility, but they must not drive
scheduling decisions or redefine worker runtime truth.

## WRSI-7 Residue Audit

The worker-status residue scan is not expected to be zero in the current
pre-release slice. It is expected to have only classified display/read-model or
test-fixture hits.

Allowed current hit classes:

| Hit class | Examples | Classification |
| --- | --- | --- |
| declaration/read conversion | `WorkerResourceOwner#toWorkerStatus`, `WorkerManager#toWorkerStatus` | display compatibility for `WorkerResourceRecord`; not scheduling truth |
| diagnostic snapshots | `AssignmentRecordService#setWorkerStatus`, monkey `WorkerSnapshot` / `WorkerSchedulingSnapshot` | trace/diagnostic display residue |
| full match diagnostics | `WorkerMatchContext` full context `workerStatus` | diagnostic context only; excluded from rule context |
| debug logging | `RuleBasedTaskWorkerMatchingStrategy` debug log reads `worker.statusName()` | diagnostic log only; not a branch condition |
| test fixtures | tests setting `WorkerStatus.ONLINE` to build legacy worker rows | setup compatibility; runtime outcome proof uses reachability/dispatch/occupancy evidence |
| boundary guard allowlist | `WorkerDeclarationBoundaryGuardTest` mention of `statusName` | guard for declaration/runtime split |
| task final status false positive | `RedisTaskWorkRuntime` task final `statusName` | task work finality vocabulary, not worker status |

Forbidden hit classes:

- matching rules reading `workerStatus` / `statusName`,
- `ResolvedWorkerSchedulingPolicy` carrying status or runtime evidence,
- Redis worker registry branching scheduling behavior on display status,
- docs that describe `ONLINE/READY/BUSY/OFFLINE` as one scheduling axis.

The candidate-bucket residue scan is also not zero by design. The allowed hits
are the converged implementation vocabulary:

- `WorkerCandidateBucketPolicy`,
- `WorkerCandidateBucketPolicies`,
- `WorkerTaskSelector#candidateBucketKeys`,
- `WorkerRegistry#acquireCandidates(... candidateBucketKey ...)`,
- Redis `groupCandidateBucket` / `nodeCandidateBucket` physical hint keys.

The forbidden candidate-bucket hit is old route-bucket vocabulary such as
`WorkerRouteBucket`, `WorkerRoutingPolicy`, `routeBucketKey`, or Redis
`:route:` worker keys.

Allowed old-vocabulary hits are limited to:

- this inventory naming the forbidden residue,
- archived historical roadmaps,
- architecture/source guards that mention a removed old symbol only to reject
  its reintroduction.

The Redis scan guard treats `RedisTaskWorkRuntime#keys(...)` as a Lua script
helper false positive, not a Redis `KEYS` command. Worker registry hot paths
must still avoid Redis `SCAN`, `KEYS`, or scan-iterator fallback.

## WRSI-2C Decision And Residue

The current Redis worker registry keeps `group:{groupId}:slots` as the single
canonical worker aggregate. This is intentional for this roadmap.

The following target keys are not implemented and must not be added as parallel
writable truth:

| Target key / value | Classification | Removal condition |
| --- | --- | --- |
| `worker:meta:{workerId}` | target-only metadata split | successor Redis worker storage rewrite replaces slot metadata in the same slice |
| `worker:occupancy:{workerId}` | target-only occupancy split | successor Redis worker storage rewrite replaces slot occupancy in the same slice |
| `worker:group:{groupId}:available:{shard}` | target-only availability hint | successor rewrite proves cost need and updates hint atomically with canonical occupancy |
| readiness values `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, `HEALTH_UNAVAILABLE` | target-only readiness vocabulary | worker state/report owner maps them to dispatch gate and diagnostics with runtime outcome proof |

Current implemented readiness evidence is limited to dispatch-gate/removing
truth: `READY`, `DRAINING`, and `MAINTENANCE`. Current implemented occupancy
evidence is derived from slot capacity, reservation, active lease counters, and
exclusive lease truth.

## WRSI-0 Acceptance

- No field remains classified as generic status without a dimension.
- `WorkerMeta#diagnosticStatus` and read-model `statusName/status` fields are
  display-only residue.
- Current candidate buckets are candidate hints, not policy, readiness, or capacity
  truth.
- `group:{groupId}:slots` is the current physical aggregate, not the target
  logical split.
- No production behavior changes are required by this inventory.
