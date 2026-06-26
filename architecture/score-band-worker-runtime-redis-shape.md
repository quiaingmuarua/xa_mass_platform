# Score-Band Worker Runtime Redis Shape

Status: target runtime design reference, not current implementation truth.

This document is the concrete Redis structure reference for the
[Score-Band Resource Slot Scheduling Blueprint](./score-band-resource-slot-scheduling-blueprint.md).
It exists separately because Redis runtime shape is implementation-specific
enough to evolve without making the mechanism blueprint harder to read.

## Purpose

Keep worker-runtime Redis structures small, queryable, and owner-separated.

First-slice assumptions:

- `resourceKind = worker`
- `resourceId = workerId`
- each `resourceId` has exactly one `homeBucketId`
- first worker slice uses `homeBucketId = workerGroupId`
- `homeBucketId` is the worker resource's primary runtime partition
- `homeBucketId` is not a placement tag bucket and not a task-created index
- score and scheduling metadata use the same `homeBucketId`

Worker runtime data splits into two lanes:

```text
Eligibility Index
  where a worker resource currently sits in score-band acquire space

Scheduling Metadata
  stable worker facts used by placement projection and validation
```

Do not collapse these lanes into one large worker runtime blob.

## Key Shape

First-slice keys:

```text
wr:{prefix}:score:{homeBucketId}
  ZSET member = workerId
  score = score-band score

wr:{prefix}:meta:{homeBucketId}
  HASH field = workerId
  value = WorkerSchedulingMetadata
```

Transition evidence is not part of the first-slice Redis runtime shape. The
default sink is trace plus focused contract-test assertions. A Redis transition
stream may be added later only for an explicit repair/debug requirement, and it
must not become current state truth or drive hot-path acquire.

## Eligibility Index

Use a bounded home-bucket ZSET as the acquire index:

```text
wr:{prefix}:score:{homeBucketId}
  ZSET member = workerId
  score = score-band score
```

The ZSET answers:

```text
eligible acquire
future due
low-recheck priority/inventory
parked inventory
```

`future due` means the existing `FUTURE_BAND` score is now `<= now` and can be
seen by acquire. It is not a timeout event, queue move, or background writer.

The ZSET value is the score index. Do not duplicate `band` in worker metadata;
derive band from score. Do not duplicate `score` in worker metadata as another
truth.

Do not fan out score across placement buckets in the first slice. If auxiliary
placement indexes are added later, they may map tag values to worker ids, but
they must not own score or scheduling metadata truth. Acquire must return to
the resource's `homeBucketId` to read score and validate owner-approved
metadata.

Do not create:

```text
task:{taskId}:candidate-workers
worker:{workerId}:score
attribute:{name}:{value}:workers
```

unless a later proof shows a specific owner and bounded lifecycle.

## Scheduling Metadata

Scheduling metadata is not full worker state. It is the stable, low-frequency,
owner-approved projection used for placement tags and Stage-2 validation.

Target shape:

```text
wr:{prefix}:meta:{homeBucketId}
  HASH field = workerId
  value = WorkerSchedulingMetadata
```

First-slice fields:

```text
workerGroupId
capacityLimit
approvedSchedulingAttributes
placementTagValues
metadataVersion
```

Field roles:

```text
workerGroupId
  capability / management scope for the worker resource

capacityLimit
  first-slice capacity metadata when resourceKind=worker and resourceId=workerId

approvedSchedulingAttributes
  low-frequency attributes allowed to participate in demand validation

placementTagValues
  compact approved composite tag values used for oldTags/newTags diff and
  operator diagnostics

metadataVersion
  optional owner version for metadata replacement and diagnostics; it is not a
  transport session generation
```

A field belongs in `WorkerSchedulingMetadata` only if it can participate in
task demand validation or approved `PlacementTagSpec` projection.

Do not store these in scheduling metadata:

```text
connectionId
sessionId
routeKey
deliveryBucketId
lastHeartbeatAt
transportEndpointLeaseId
websocketChannelId
polling cursor
raw online flag from transport
raw IP
raw latency
raw battery percent when high-frequency
last error detail
rawEventName
transitionId
```

Transport/session/freshness evidence stays in transport-owned stores or trace.
If a transport-derived fact must affect scheduling, it must first become
owner-approved scheduling evidence and flow through a worker-runtime transition.

## Dynamic State Boundary

The score ZSET is the first-slice dynamic scheduling truth. It carries parked,
low-recheck, eligible, and future/unavailable state through the score value.

Do not add a first-slice `hold` hash, `WorkerHoldState`, lease token, session
generation, or current hold owner record. Worker is the schedulable unit; a
delayed close/release must not be able to make an unavailable worker available.
Positive events such as release, final, heartbeat, connected, or freshness may
only request worker-runtime recheck. Worker-runtime may write an eligible score
only after validating declaration, group membership, gates, capacity, recovery
mode, and owner-approved metadata.

Reason, owner action, failed recheck count, and reopen policy are trace or
diagnostic evidence in the first slice. If a later proof needs a repair/debug
projection for these fields, add it through a separate roadmap and keep it out
of hot-path acquire truth.

## Transition Evidence

Transition evidence is for proof, trace, and repair. It is not current state
truth and must not drive hot-path acquire.

Default sink:

```text
trace event / test evidence sink
```

Deferred optional sink:

```text
wr:{prefix}:transition
  Redis Stream for explicit repair/debug needs only
```

The first score-band Redis slice does not need this stream. Do not include it in
Redis completion criteria unless a later roadmap proves a concrete repair or
debug owner.

Suggested evidence fields:

```text
transitionType
resourceId = workerId
oldBand
newBand
reasonCode
sourceType
ownerAction
rawEventName
ownerRef
attemptId
observedAt
```

`transitionId`, if generated, is log evidence only.

## Guardrails

- `band` is derived from ZSET score; do not store it as independent truth.
- `score` lives in score ZSETs; do not copy it into metadata as current truth.
- Worker metadata is for matching and validation, not transport/session proof.
- Do not create `wr:{prefix}:hold:{homeBucketId}` in the first slice.
- Do not introduce `WorkerHoldState`, lease tokens, or session generation as
  Redis runtime decision truth in the first slice.
- `homeBucketId` is primary runtime partition, not a placement tag bucket.
- A resource has exactly one `homeBucketId` in the first slice.
- Bucket membership is long-lived policy output, not task-created state.
- Placement tag values are bounded and owner-approved.
- Auxiliary placement indexes, if added later, must not own score or metadata
  truth.
- Stale auxiliary candidates may be rejected by owner validation and cleaned
  opportunistically; stale positive/release evidence must not reopen a worker
  directly.
- No per-task worker candidate keys.
- No Redis LIST for worker current metadata.
