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
- `homeBucketId` is the worker resource's primary runtime partition
- `homeBucketId` is not a placement tag bucket and not a task-created index
- score, metadata, and hold state all use the same `homeBucketId`

Worker runtime data splits into three lanes:

```text
Eligibility Index
  where a worker resource currently sits in score-band acquire space

Scheduling Metadata
  stable worker facts used by placement projection and validation

Lease / Hold State
  current lease token, hold reason, low-recheck counters, and reopen policy
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

wr:{prefix}:hold:{homeBucketId}
  HASH field = workerId
  value = WorkerHoldState
```

Optional evidence sink:

```text
wr:{prefix}:transition
  STREAM or trace sink
```

The transition sink is not current state truth and must not drive hot-path
acquire.

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
low-recheck due
parked inventory
```

The ZSET value is the score index. Do not duplicate `band` in worker metadata;
derive band from score. Do not duplicate `score` in worker metadata as another
truth.

Do not fan out score across placement buckets in the first slice. If auxiliary
placement indexes are added later, they may map tag values to worker ids, but
they must not own score, scheduling metadata, or lease/hold truth. Acquire must
return to the resource's `homeBucketId` to read score and validate hold state.

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

## Lease / Hold State

Lease and hold state contains the minimal dynamic state that cannot be encoded
by ZSET score alone.

Target shape:

```text
wr:{prefix}:hold:{homeBucketId}
  HASH field = workerId
  value = WorkerHoldState
```

First-slice fields:

```text
leaseToken
leaseOwnerType
leaseOwnerId
holdReasonCode
failedRecheckCount
reopenPolicy
```

Field roles:

```text
leaseToken
  prevents stale release or renew from affecting a newer lease

leaseOwnerType / leaseOwnerId
  identifies preallocation, attempt, cooldown, or owner hold

holdReasonCode
  explains the current non-eligible reason when score alone is not enough

failedRecheckCount
  LOW_RECHECK failed fallback count; score remains due-time only

reopenPolicy
  defines whether explicit worker report, owner command, or policy promotion
  can reopen or unpark the worker resource
```

Prefer deriving these from score instead of duplicating them:

```text
band
score
leaseUntilMillis
nextRecheckAtMillis
```

If an implementation temporarily denormalizes `leaseUntilMillis` or
`nextRecheckAtMillis` for diagnostics, it must be rewritten by the same owner
transition as the ZSET score and must not become transition truth.

## Transition Evidence

Transition evidence is for proof, trace, and repair. It is not current state
truth and must not drive hot-path acquire.

Possible sink:

```text
wr:{prefix}:transition
  STREAM or trace sink
```

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
leaseToken
attemptId
observedAt
```

`transitionId`, if generated, is log evidence only.

## Guardrails

- `band` is derived from ZSET score; do not store it as independent truth.
- `score` lives in score ZSETs; do not copy it into metadata as current truth.
- Worker metadata is for matching and validation, not transport/session proof.
- Lease token and owner identity live in hold state, not placement metadata.
- `homeBucketId` is primary runtime partition, not a placement tag bucket.
- A resource has exactly one `homeBucketId` in the first slice.
- Bucket membership is long-lived policy output, not task-created state.
- Placement tag values are bounded and owner-approved.
- Auxiliary placement indexes, if added later, must not own score / metadata /
  hold truth.
- Stale auxiliary candidates may be rejected by hold/lease validation and
  cleaned opportunistically; double lease and parked release are not allowed.
- No per-task worker candidate keys.
- No Redis LIST for worker current metadata.
