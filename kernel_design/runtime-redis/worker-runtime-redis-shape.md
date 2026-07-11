# Worker Runtime Redis Shape

Status: new-kernel design reference. This document is not current Java
implementation truth and is not an implementation roadmap.

Artifact role: Redis shape reference for the Python executable spec.

This document replaces the older score-band Redis draft. Do not reintroduce
old time-coordinate terminology, worker lifecycle tags, owner-mirrored
assignment-hold vocabulary, stale-fence revision fields, or a generic worker
scheduling metadata hash from that draft.

## Purpose

Define the first worker-runtime Redis structures before implementing the
worker-runtime executable spec.

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
full capacity/admission model
generic cross-round assignment hold records
```

## Owner Decisions

```text
workerGroupId == homeBucketId
one worker belongs to exactly one workerGroupId in v0
score ZSET is acquisition truth only
descriptor hashes are resource declaration truth
dynamic attribute keys are handler-owned projections / indexes
transport evidence is not stored in worker catalog or score keys
worker registration is complete only after score-first registration writes the descriptor
resource metadata updates do not require a worker score lease
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
availability proof.

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
  "systemMetadata": {},
  "staticAttributes": {},
  "dynamicAttributeNames": ["battery", "load"]
}
```

`dynamicAttributeNames` is an allowlist of updateable dynamic attribute names.
It is not the current value of those attributes.

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
runtime acquisition truth
```

Only `WorkerScoreCore` writes this key. Resource catalog, dynamic attribute
handlers, transport, trace, result routing, and query projections must not write
score directly.

Score absence is not a normal unavailable state. It means the score has not been
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
  only source for worker hot acquisition

score < 0
  RECOVERY_RECHECK
  only source for worker recovery validation

score == 0
  invalid / reserved
```

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
list[(workerId, observedScore)]
```

`observedScore` is the complete signed score. It is an opaque stale fence for
exact-CAS operations. Callers must not trim, decode, construct, or persist it as
public worker lifecycle truth.

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
rewrite_current_score(workerGroupId, workerId, targetTimeMillis, targetLaneRank?)
acquire_due_hot_score_lease(workerGroupId, workerId, observedScore, targetTimeMillis)
renew_active_hot_score_lease(workerGroupId, workerId, observedScore, targetTimeMillis)
mark_current_lease_dirty(workerGroupId, workerId)
toggle_current_polarity(workerGroupId, workerId, observedScore, targetLaneRank)
exhaust_recovery_recheck(workerGroupId, workerId, observedScore)
release_score_hold(workerGroupId, workerId, observedScore, releaseTimeMillis)
```

Rules:

```text
same-polarity rewrite never lowers timeSlot
release may lower timeSlot only through exact observedScore CAS
polarity move preserves timeSlot and dirty
polarity move uses exact observedScore CAS
RECOVERY_RECHECK cannot be hot leased
active hot lease renewal returns STALE on dirty
due hot lease acquisition may clear dirty only after current validation
dirty mark only sets dirty = 1
```

`rewrite_current_score` is monotonic and does not need `observedScore`.
Lowering operations and polarity moves do need exact `observedScore`.

## Worker Registration And Catalog Operations

First worker registration belongs to `WorkerRuntime`, because descriptor-only
registration creates an unschedulable worker. `WorkerResourceCatalog` remains
the worker-group declaration, descriptor-read, and low-frequency metadata
surface.

Required first-slice operations:

```text
WorkerRuntime.register_worker_descriptor(descriptor, laneRank)

register_worker_group_descriptor(descriptor)
get_worker_group_descriptors(workerGroupIds)
get_worker_descriptors(workerGroupId, workerIds)
update_worker_system_metadata(workerGroupId, workerId, metadata)
refresh_worker_static_attributes(workerGroupId, workerId, attributes)
```

Register worker group:

```text
HSET wr:{prefix}:groups workerGroupId descriptorJson
```

Register worker:

```text
require workerGroupId exists
validate worker descriptor against group event-code promise / platform policy
WorkerScoreCore.initialize_hot_acquire_score(workerGroupId, workerId, laneRank)
  -> ZADD NX score first
require score initialization succeeded
HSET wr:{prefix}:workers:{workerGroupId} workerId descriptorJson second
```

The score is the registration/existence fence for scheduling. A descriptor row
without a score is incomplete residue and must not make the worker discoverable
or block later score-owned repair. An existing score rejects repeated
registration before descriptor replacement.

This registration score is not a metadata-update lease. After registration,
`update_worker_system_metadata`, `refresh_worker_static_attributes`, and dynamic
attribute handlers update their owner data directly. Dirty marking, when a
validated assignment continuation actually depends on a changed field, is a
separate score-fence concern; it is not a prerequisite for resource updates.

Registration does not use a cross-key Lua script or Redis transaction. Score
and descriptor remain separate owner keys. A score with a missing descriptor
fails closed during bounded descriptor validation and can be repaired by an
owner command in a later slice.

If a worker changes group in a later slice, that is a remove/register
transition owned by worker runtime and score owner together. Do not implement
multi-group movement in v0.

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
WorkerCandidateMatcher receives bounded workerIds and a candidate constraint map
each WorkerCandidateConstraint carries priority, limit, and match_rules
match_rules is a structured map compiled by the independent constraint DSL
worker matcher preparation derives dynamic fields from match_rules
missing declared dynamic handler is a configuration error
descriptors read workers:{workerGroupId} once
candidate order is priority descending, then candidateId ascending
declared acquire fields are deduplicated in resolved candidate order
each dynamic handler batch-reads descriptor-supported bounded workerIds once
for each workerId, build one temporary context
skip candidates whose per-call limit is full
evaluate remaining constraints in resolved priority order; first match consumes that worker
missing / unsupported / unresolved handler rows fail closed when read
matcher returns each workerId in at most one candidate result
result shape is insertion-ordered candidateId -> workerIds map in resolved priority order
assignment-dispatch keeps observedScore sidecar from score acquire
```

`WorkerCandidateMatcher` is a storage-independent worker-runtime mechanism. It
consumes `WorkerResourceCatalog` for one descriptor batch and
`WorkerDynamicAttributeRuntime` for bounded dynamic reads. The matcher filters
descriptor-supported worker ids from the loaded batch; the dynamic runtime
hides query handlers and does not reread descriptors. Redis-specific code owns
descriptor persistence and handler storage only. Do not introduce
storage-specific matcher subclasses.

Dynamic attribute updates do not automatically write worker score. They may mark
score dirty only after a later executable spec introduces a real persisted
assignment plan or hot score lease continuation that can consume dirty.

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
acquire_due_hot_score_lease after current worker descriptor / dynamic metadata
validation
```

Forbidden:

```text
standalone clear_dirty
metadata-owner clears dirty
trace/read model clears dirty
dynamic attribute handler clears dirty directly
active hot lease renewal clears dirty
```

`validationDependencySet` is conceptual until the assignment executable spec
introduces a persisted continuation. It is not a Redis key in this first slice.

## Deferred Structures

Do not create these in the first worker-runtime Redis slice:

```text
wr:{prefix}:hold:{workerGroupId}
wr:{prefix}:session:{workerGroupId}
wr:{prefix}:heartbeat:{workerGroupId}
wr:{prefix}:transition
wr:{prefix}:capacity:{workerGroupId}
wr:{prefix}:assignment-continuation:{workerGroupId}
task:{taskId}:candidate-workers
worker:{workerId}:score
attribute:{name}:{value}:workers
```

Reasons:

```text
hold is represented by future timeSlot inside current polarity
session / heartbeat belongs to transport or dynamic attribute handlers
transition evidence belongs to trace or deterministic tests until repair needs it
capacity is policy / dynamic attribute / later admission owner, not score truth
assignment continuation is not first-slice truth
per-task candidates create a second assignment-dispatch mainline
per-worker score keys break home-bucket acquisition
attribute fanout is deferred until candidate discovery needs it
```

## Consistency Rules

Use atomic operations only where stale state can produce wrong admission:

```text
score exact-CAS for release, polarity move, cold park, hot lease acquire/renew
capacity + score update only after a later capacity owner proves the invariant
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
- Do not let heartbeat / reconnect / raw positive session evidence move
  RECOVERY_RECHECK to HOT_ACQUIRE directly.
- Do not add worker lifecycle tags.
- Do not add PARKED, FUTURE, or MANUAL_DISABLED bands.
- Do not use score absence as worker unavailability.
- Do not use RECOVERY_RECHECK scores as assignment leases.
- Do not create per-task worker candidate keys.
- Do not fan out score across placement-tag buckets in v0.
- Do not make dirty a metadata hash, counter, audit sequence, priority, or
  lifecycle reason.
- Do not expose Redis score encoding details as public API parameters.
