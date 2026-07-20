# Worker Runtime Redis Shape

Status: active new-kernel Redis shape; Python executable spec implemented;
policy coverage partial.

This document records the current executable Redis shape. Worker lifecycle
tags, owner-mirrored assignment holds, revision fields, and a generic worker
scheduling metadata hash are outside this owner model.

## Purpose

Define the Worker resource, score, and dynamic-attribute structures used by the
current executable spec.

Worker-runtime Redis has four first-slice responsibilities:

```text
resource catalog lookup
worker score acquisition index
dynamic attribute handler-owned query storage
bounded owner-local stale fences
```

It does not own:

```text
transport session truth
task assignment truth
task item claim truth
task result truth
trace / audit truth
worker lifecycle tags
global worker state blob
per-Worker capacity pool or parallel assignment model
generic cross-round assignment hold records
```

## Owner Decisions

```text
workerGroupId == homeBucketId
one worker belongs to exactly one workerGroupId in v0
score ZSET is kernel-owned TaskItem scheduling-serviceability polarity plus
acquisition/recovery timing truth
descriptor hashes are resource declaration truth
dynamic attribute keys are handler-owned projections / indexes
endpointManagerId is a stable post-selection endpoint-owner locator
live transport evidence is not stored in worker catalog or score keys
Worker upsert establishes immutable declaration identity before ensuring score presence
reconnect supplies trusted serviceability evidence, replaces Worker attributes,
preserves timeSlot/laneRank, converges to HOT_ACQUIRE, and writes dirty=1
without releasing a hold
platform and dynamic attribute updates do not require a worker score lease
```

`homeBucketId` is a worker-runtime partition key. It is not a placement tag,
not a transport mailbox, and not a task-created candidate bucket.

## Namespace

Use a single runtime namespace prefix:

```text
wr:{prefix}:...
```

`prefix` is a deployment/runtime namespace. It is not a worker group, adapter,
project, or policy id.

## First-Slice Keys

### Worker Groups

```text
wr:{prefix}:groups
  HASH field = workerGroupId
  value = WorkerGroupDescriptor json
```

Role:

```text
resource declaration truth for worker group metadata and event-code promise
```

Value shape:

```json
{
  "workerGroupId": "image-workers",
  "attributes": {},
  "eventCodes": ["image.generate"]
}
```

`eventCodes` is a group promise. It validates worker-group capability after a
task has already selected a group. It is not a worker selector and not runtime
scheduling-serviceability proof.

### Worker Descriptors

```text
wr:{prefix}:workers:{workerGroupId}
  HASH field = workerId
  value = WorkerDescriptor json
```

Role:

```text
resource declaration truth for worker identity, group membership, and
low-frequency metadata
```

Value shape:

```json
{
  "workerId": "worker-1",
  "workerGroupId": "image-workers",
  "endpointManagerId": "endpoint-manager-a",
  "platformAttributes": {},
  "attributes": {},
  "dynamicAttributeNames": ["battery", "load"]
}
```

`dynamicAttributeNames` is an allowlist of updateable dynamic attribute names.
It is not the current value of those attributes.

`endpointManagerId` locates the physical endpoint manager only after a Worker
has been selected. It is required declaration metadata, not a matcher field,
score dimension, live endpoint, session, mailbox, or reachability fact.

`workerGroupId` is a required logical locator on worker descriptor read and
update operations. The current Redis executable spec uses it directly in the
hash key, but that is only a first-slice storage layout. A later implementation
may resolve `(workerGroupId, workerId)` to a group bucket, worker-id hash bucket,
or another physical partition without changing the catalog interface.

There is no worker-to-group reverse lookup key in the runtime mainline. Global
worker lookup, cross-group diagnostics, or global-id uniqueness would require a
separate named read/index invariant; they must not make ordinary catalog reads
rediscover information already supplied by the caller.

### Worker Score

```text
wr:{prefix}:score:{workerGroupId}
  ZSET member = workerId
  score = signed worker score
```

Role:

```text
kernel-owned TaskItem scheduling-serviceability polarity
runtime acquisition / recovery timing truth
```

Only `WorkerScoreCore` writes this key. Resource catalog, dynamic attribute
handlers, transport, trace, result routing, and query projections must not write
score directly.

These resource/evidence owners also do not acquire a worker score lease before
writing their own keys. HOT admission scheduling is the only routine writer of
acquired positive scores, and recovery scheduling is the only routine writer of
acquired negative scores. Registration initializes the first score; explicit
hold/release or verified polarity commands are narrow control transitions, not
generic metadata-update hooks.

Score absence is not a RECOVERY_RECHECK classification. It means the score has not been
initialized, the worker was removed, or the index is orphaned and needs
owner-local repair.

### Dynamic Attributes

There is no universal dynamic attribute key.

Each dynamic attribute handler owns its own Redis shape:

```text
wr:{prefix}:dyn:{attributeName}:...
```

Examples:

```text
wr:{prefix}:dyn:battery:{workerGroupId}
  ZSET member = workerId
  score = battery level or policy-owned normalized coordinate

wr:{prefix}:dyn:network:{workerGroupId}
  HASH field = workerId
  value = normalized network type

wr:{prefix}:dyn:load:{workerGroupId}
  HASH / ZSET / bitmap, depending on handler policy
```

Dynamic attribute handlers own:

```text
payload validation
normalization
point-write behavior
query/index storage shape
optional read timestamp
```

They do not own worker score, worker lifecycle, transport truth, or task
assignment truth.

## Score Encoding

Worker score uses the current worker-score model:

```text
timeSlot = floor(timeMillis / SLOT_MILLIS)
score = polarity * base
base = timeSlot * SLOT_FACTOR + laneRank * DIRTY_FACTOR + dirty
```

Polarity:

```text
score > 0
  HOT_ACQUIRE
  scheduling-available for ordinary allocation
  only source for worker hot acquisition

score < 0
  RECOVERY_RECHECK
  scheduling-unavailable for ordinary allocation
  only source for worker recovery validation

score == 0
  invalid / reserved
```

The sign is kernel scheduling-serviceability truth after owner validation; it
is not physical connection or transport-session truth. `timeSlot` is
independent: a HOT_ACQUIRE Worker may still be non-due because it is leased,
held, disabled, draining, or cooling down. Polarity movement preserves
`timeSlot`, so reconnect or recovery evidence cannot escape an existing hold.

Constants:

```text
TIME_SCALE = 10
SLOT_MILLIS = 100
DIRTY_FACTOR = 2
LANE_RANK_FACTOR = 100
SLOT_FACTOR = LANE_RANK_FACTOR * DIRTY_FACTOR
MAX_LANE_RANK = 99
MAX_DIRTY = 1
MAX_TIME_SLOT = 99_999_999_999
PAUSE_TIME_SLOT = MAX_TIME_SLOT
PAUSE_TIME_MILLIS = PAUSE_TIME_SLOT * SLOT_MILLIS
MIN_BASE = 1
```

Decode:

```text
absScore = abs(score)
timeSlot = floor(absScore / SLOT_FACTOR)
slotRemainder = absScore % SLOT_FACTOR
laneRank = floor(slotRemainder / DIRTY_FACTOR)
dirty = slotRemainder % DIRTY_FACTOR
```

The public interface uses `timeMillis`. Redis implementation converts to
`timeSlot` internally. Do not expose score ranges, polarity signs, base values,
dirty bit values, or `SLOT_FACTOR` to callers.

## Acquire Ranges

Hot acquire:

```text
dueTimeSlot = nowTimeSlot - 1
ZRANGEBYSCORE wr:{prefix}:score:{workerGroupId}
  MIN_BASE
  base(dueTimeSlot, MAX_LANE_RANK, MAX_DIRTY)
  WITHSCORES
  LIMIT 0 limit
```

Return:

```text
map[workerId, observedScore]
```

The query is read-only. The returned score is an opaque observation retained
only by the allocation pacer for a later batched exact-score lease. The pacer
passes only independent lease-CAS successes to matcher. Redis scan order may remain
visible through the concrete dict insertion order, but it is not part of the
public contract. Concurrent scans may return the same Worker; the later
compare-and-write decides the single lease winner before matching.

Recovery recheck:

```text
recoveryLookbackSlots = ceil(recoveryLookbackMillis / SLOT_MILLIS)
windowStartTimeSlot = nowTimeSlot - recoveryLookbackSlots
dueTimeSlot = nowTimeSlot - 1

ZREVRANGEBYSCORE wr:{prefix}:score:{workerGroupId}
  -base(windowStartTimeSlot, MIN_LANE_RANK, MIN_DIRTY)
  -base(dueTimeSlot, MAX_LANE_RANK, MAX_DIRTY)
  WITHSCORES
  LIMIT 0 limit
```

RECOVERY_RECHECK is not a worker selection lane. It may only feed
worker-runtime recovery validation. Assignment-dispatch must not use negative
score candidates as selected workers.

Scores older than the recovery lookback window are cold parked by coordinate.
There is no PARKED band or PARKED key.

## Score Write Primitives

Redis scripts should stay score-axis only. They may validate score shape,
polarity, due/future bounds, and exact observed-score CAS. They must not parse
worker descriptors, task demand, dynamic attribute payloads, transport session
data, or business event names.

Required first-slice primitives:

```text
initialize_hot_acquire_score(workerGroupId, workerId, laneRank)
reconcile_worker_hot_acquire(workerGroupId, workerId)
rewrite_current_scores(workerGroupId, workerIds, targetTimeMillis, targetLaneRank?)
acquire_hot_acquire_candidates(workerGroupId, limit)
acquire_observed_hot_score_leases(
  workerGroupId, observedScores, targetTimeMillis
)
renew_active_hot_score_leases(workerGroupId, observedScores, targetTimeMillis)
demote_observed_worker_leases_to_recovery(workerGroupId, observedScores)
mark_current_lease_dirty(workerGroupId, workerId)
toggle_current_polarity(workerGroupId, workerId, observedScore, targetLaneRank)
exhaust_recovery_recheck(workerGroupId, workerId, observedScore)
release_score_holds(workerGroupId, observedScores, releaseTimeMillis)
```

`release_score_holds` is not an allocation or dispatch compensation primitive.
Result routing uses the published opaque lease after `200/1xxx` evidence.
Allocation unmatched, matcher failure, candidate publication failure, dispatch
rejection, and queue ambiguity leave the score untouched and recover through
lease expiry unless trusted `3xxx` evidence requests exact RECOVERY_RECHECK
demotion.
The full cross-pacer owner sequence is defined by
[Worker HOT_ACQUIRE Lease Protocol](../scheduling/worker-hot-acquire-lease-protocol.md);
this Redis note owns only storage and atomic primitive behavior.

Rules:

```text
same-polarity rewrite never lowers timeSlot
release requires currentSlotStartMillis <= releaseTimeMillis and
abs(releaseSlotBase) < abs(observedScore)
release preserves polarity, laneRank, and dirty low bits and writes only through
exact observedScore CAS
polarity move preserves timeSlot and dirty
polarity move uses exact observedScore CAS
RECOVERY_RECHECK cannot be hot leased
active hot lease renewal returns STALE on dirty; a covered future target returns
NOOP only after exact observedScore validation
bounded hot acquire is a read-only positive due-score query
observed hot lease batch validates due/future shape outside Lua, then pipelines
one generic exact-score CAS per Worker while preserving laneRank and clearing dirty
dirty mark only sets dirty = 1
HOT_ACQUIRE reconciliation preserves timeSlot/laneRank, writes positive polarity,
and sets dirty = 1
observed recovery demotion accepts only clean positive lease scores and writes
-abs(observedScore) through exact CAS
```

`rewrite_current_scores` is monotonic and does not need `observedScore`.
Lowering operations and polarity moves do need exact `observedScore`.

Batch score writes accept one WorkerGroup/ZSET key and shared target parameters.
Redis uses `pipeline(transaction=false)` around the existing single-Worker Lua
primitives. The batch reduces network round trips but is not transactional;
each Worker returns an independent transition result.

## Worker Upsert And Catalog Operations

Worker upsert belongs to `WorkerRuntime` because first appearance must ensure a
score and reconnect evidence may restore HOT_ACQUIRE polarity. The caller supplies
`WorkerDeclaration`; the runtime owns `platformAttributes`, initial laneRank,
score initialization, and polarity handling. `WorkerResourceCatalog` remains
the WorkerGroup upsert, descriptor-read, and platform-attribute surface.

Required first-slice operations:

```text
WorkerRuntime.upsert_worker(declaration)

upsert_worker_group(descriptor)
get_worker_group_descriptors(workerGroupIds)
get_worker_descriptors(workerGroupId, workerIds)
update_worker_platform_attributes(workerGroupId, workerId, attributes)
```

Upsert WorkerGroup:

```text
HSETNX establishes workerGroupId + eventCodes
existing eventCodes mismatch -> CONFLICT
compatible repeat -> HSET complete replacement attributes
```

Upsert Worker:

```text
require workerGroupId exists
require endpointManagerId is non-empty
HSETNX establishes workerId + workerGroupId + endpointManagerId + dynamicAttributeNames
existing immutable declaration mismatch -> CONFLICT
compatible repeat -> replace attributes, preserve platformAttributes

score absent
  -> initialize HOT_ACQUIRE using runtime-owned initialLaneRank
score positive
  -> no score write
score negative
  -> exact observed-score CAS flips only polarity to positive
```

The score remains the scheduling truth. A descriptor row without a score is
not schedulable and a later idempotent upsert initializes the missing score. A
score without a descriptor fails closed during matcher descriptor validation;
a later upsert recreates the descriptor without resetting the existing score.

The score is not a metadata-update lease. `update_worker_platform_attributes`
and dynamic attribute handlers update their owner data directly. Dirty marking, when a
validated assignment continuation actually depends on a changed field, is a
separate score-fence concern; it is not a prerequisite for resource updates.

Upsert uses no cross-key Lua script or Redis transaction. `HSETNX` establishes
one immutable descriptor field; score operations continue through
`WorkerScoreCore`. Partial completion is bounded and converges through retry.

`workerId` is unique only inside one `workerGroupId`. The runtime does not keep
a global Worker home index; the same Worker id in different groups represents
different resources. Do not infer or implement cross-group movement in v0.

## Dynamic Attribute Operations

Dynamic attribute update flow:

```text
receive workerGroupId + workerId
read the implementation-owned descriptor bucket
require descriptor.workerGroupId == requested workerGroupId
require attrName in descriptor.dynamicAttributeNames
resolve concrete runtime _updateHandlers[attrName]
handler validates payload
handler writes its own dyn key
```

Dynamic attribute query flow for matching:

```text
WorkerCandidateMatcher receives workerGroupId, bounded workerId-to-opaque-lease-score map, and a candidate constraint map
each WorkerCandidateConstraint carries priority, limit, and match_rules
match_rules is a structured map compiled by the independent constraint DSL
worker matcher preparation derives dynamic fields from match_rules
missing declared dynamic handler is a configuration error
assignment-dispatch reads bounded due HOT worker observations before matcher
allocation pacer keeps opaque observed scores in a private sidecar
descriptors read workers:{workerGroupId} once
candidate order is priority descending, then candidateId ascending
declared acquire fields are deduplicated in resolved candidate order
each dynamic handler batch-reads descriptor-supported bounded workerIds once
for each workerId, build one temporary context
skip candidates whose per-call limit is full
evaluate remaining constraints in resolved priority order; first match consumes that worker
missing / unsupported / unresolved handler rows fail closed when read
matcher returns each workerId in at most one candidate result
result shape is insertion-ordered candidateId -> CandidateWorkerEntry values
each entry carries descriptor endpointManagerId and the unchanged opaque lease score
pacer leaves unmatched leases untouched; lease expiry restores hot visibility
```

`WorkerCandidateMatcher` is a storage-independent worker-runtime mechanism. It
consumes `WorkerResourceCatalog` for one descriptor batch and
`WorkerDynamicAttributeRuntime` for bounded dynamic reads. The matcher filters
descriptor-supported worker ids from its supplied batch; the dynamic runtime
hides query handlers and does not reread descriptors. Redis-specific code owns
descriptor persistence and handler storage only. Do not introduce
storage-specific matcher subclasses.

Dynamic attribute updates do not automatically write worker score. They may
invoke the implemented dirty marker only when platform policy identifies a
changed match dependency and a real persisted assignment plan or active hot
score lease continuation will consume that fence. The current assembly does not
yet wire that end-to-end invocation/revalidation policy.

First slice should not create a dynamic-attribute global query service. Use
bounded handler-owned batch reads during candidate matching. Add attribute
fanout indexes only when a concrete executable spec proves that candidate
discovery needs them.

## Dirty Boundary

`dirty` is not a global worker state.

It is a one-bit stale hint for a real persisted assignment continuation:

```text
dirty = 0
  clean relative to current hot score lease / assignment continuation

dirty = 1
  a validation dependency used by that continuation may have changed enough to
  invalidate cached match facts
```

If there is no persisted assignment plan or hot score lease continuation, dirty
has no first-slice consumer. Do not invent a cross-round assignment hold store
just to justify dirty.

Allowed dirty write:

```text
mark_current_lease_dirty
  set dirty = 1
  preserve polarity
  preserve timeSlot
  preserve laneRank
```

Allowed dirty clear:

```text
acquire_observed_hot_score_leases after bounded score candidate scan and before
bounded descriptor / dynamic metadata matching
```

Forbidden:

```text
standalone clear_dirty
metadata-owner clears dirty
trace/read model clears dirty
dynamic attribute handler clears dirty directly
active hot lease renewal clears dirty
```

`validationDependencySet` remains conceptual until an explicit persisted
continuation owner consumes it. It is not a Redis key in the current
executable spec.

## Deferred Structures

Do not create these in the first worker-runtime Redis slice:

```text
wr:{prefix}:hold:{workerGroupId}
wr:{prefix}:session:{workerGroupId}
wr:{prefix}:heartbeat:{workerGroupId}
wr:{prefix}:transition
wr:{prefix}:capacity:{workerGroupId}
wr:{prefix}:assignment-continuation:{workerGroupId}
wr:{prefix}:task:{taskId}:candidate-workers
worker:{workerId}:score
attribute:{name}:{value}:workers
```

Reasons:

```text
hold is represented by future timeSlot inside current polarity
session / heartbeat belongs to transport or dynamic attribute handlers
transition evidence belongs to trace or deterministic tests until repair needs it
one WorkerId is one execution slot; physical concurrency is represented by
multiple logical WorkerIds, not a capacity key behind one score
assignment continuation is not first-slice truth
worker-runtime-owned per-candidate evidence creates a second
assignment-dispatch mainline. The separate `CandidateWorkerCache` may own
transient `ad:{prefix}:candidate:{candidateId}:workers` ZSETs;
worker-runtime and worker-score must not read, write, or reinterpret that
protocol.
per-worker score keys break home-bucket acquisition
attribute fanout is deferred until candidate discovery needs it
```

## Consistency Rules

Use atomic operations only where stale state can produce wrong admission:

```text
score exact-CAS for release, polarity move, cold park, hot lease acquire/renew
```

Accept bounded eventual consistency for:

```text
dynamic attribute indexes
read projections
diagnostic parked inventory
trace evidence
```

Do not add a broad Redis lock, global worker scanner, background repair loop,
or transaction wrapper before an executable spec names the invariant it
protects.

## Guardrails

- Do not store score inside worker descriptors.
- Do not store decoded polarity, timeSlot, laneRank, or dirty in descriptor
  hashes.
- Do not store dynamic attribute current values inside `WorkerDescriptor`.
- Do not let transport write worker score keys directly.
- Do not let heartbeat or raw session evidence move RECOVERY_RECHECK to
  HOT_ACQUIRE. Only validated Worker upsert may invoke HOT_ACQUIRE
  reconciliation.
- Do not add worker lifecycle tags.
- Do not add PARKED, FUTURE, or MANUAL_DISABLED bands.
- Do not use score absence as Worker scheduling-unavailability.
- Do not use RECOVERY_RECHECK scores as assignment leases.
- Do not create per-task worker candidate keys.
- Do not fan out score across placement-tag buckets in v0.
- Do not make dirty a metadata hash, counter, audit sequence, priority, or
  lifecycle reason.
- Do not expose Redis score encoding details as public API parameters.
