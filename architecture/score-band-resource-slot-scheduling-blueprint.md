# Score-Band Resource Slot Scheduling Blueprint

Status: architecture blueprint. This document describes the target scheduling
mechanism and owner boundaries. It is not current implementation truth, not an
implementation roadmap, and not proof that the code already behaves this way.

## Purpose

This blueprint explains the intended long-term scheduling mechanism for XA Mass
Platform:

```text
Score-Band Resource Slot Scheduling
```

The goal is to move the scheduling center of gravity away from task-side worker
enumeration and toward supply-side resource-slot leasing.

Current implementation still largely follows:

```text
task demand
  -> worker group selector
  -> candidate bucket / worker candidate acquisition
  -> worker filters
  -> reserve worker
  -> claim task work
  -> dispatch
```

That line was useful for convergence, but it should not remain the long-term
hot path. It keeps pushing task-side scheduling toward more worker indexes:
group indexes, attribute indexes, reachability indexes, occupancy indexes,
warm pools, route buckets, and recovery projections. As worker/resource count
and state-change frequency grow, those indexes become the source of owner
conflicts.

The target model reverses the lookup:

```text
task = demand
resource slot = supply

engine exposes task demand lanes and attempt lifecycle needs
worker-runtime/resource-runtime maintains schedulable resource slots
matcher claims eligible slots from supply and binds them to demand
```

This is not "just use Redis zset." It is an ownership correction: availability
is maintained by resource owners, while engine owns demand and task lifecycle.

## Core Mechanism

The core primitive is a resource slot with a score. The score places the slot
in one of four bands:

```text
PARKED_BAND       intentionally out of active scheduling and recheck
LOW_RECHECK_BAND  recoverable negative state that deserves owner recheck
ELIGIBLE_BAND     currently schedulable window
FUTURE_BAND       preallocated / occupied / cooldown / attempt interval
```

The important asymmetry:

```text
FUTURE_BAND is time-due by score interpretation.
LOW_RECHECK_BAND is owner-recheck releasable, but it is not time-due by default.
PARKED_BAND is not automatically rechecked or time-releasable.
```

Non-negative unavailable periods must be time-bounded by score. Negative state
must be explicitly reopened by the owning runtime after validation or policy
promotion.

## Why This Direction

The current task-side worker lookup model fails under production pressure
because:

- task count is usually smaller than worker/resource count;
- worker/resource state changes frequently: connection, block, occupancy,
  drain, claim interval, heartbeat, cooldown, and external resource health;
- task-side worker lookup forces the scheduling kernel to understand too many
  worker-state combinations;
- every new filter tends to create another index and another synchronization
  problem;
- state-machine cleanup depends too much on reliable release events.

The score-band model makes resource availability supply-owned. The hot path
does not ask "which workers match this task by scanning a worker universe?" It
asks "which eligible resources can be claimed for this demand lane?"

## Resource Identity

`resourceId` is the schedulable identity inside score-band runtime.

First implementation waves should keep the unit simple:

```text
resourceKind = worker
resourceId = workerId
```

Do not introduce per-permit worker slot ids in the first slice. If a worker has
`maxConcurrentWork > 1`, keep that as worker resource metadata / capacity
until a later proof shows that per-permit resource ids are worth the additional
model cost.

Possible future shape:

```text
worker-a#0
worker-a#1
worker-a#2
```

That future shape is an implementation option, not a first-slice requirement.

Later resource kinds can include:

```text
device
account
proxy
sim
browser
```

Do not start with a generic universal resource envelope. Prove the model with
worker resources first, using `workerId` as `resourceId`.

## Score Bands

### PARKED_BAND

`PARKED_BAND` means the slot is intentionally outside active scheduling and
outside periodic recovery scanning.

Rules:

- score is a negative fixed band code, not a timestamp and not a retry count;
- normal acquire never scans it;
- scheduled recheck should not scan it;
- it does not recover by time;
- it is used when the system deliberately does not want to spend periodic
  recovery budget on the slot;
- only the resource owner or an explicit policy promotion can move it out.

Typical reasons:

```text
operator disabled
drain requested
group disabled
intentionally idle
cold / retired / cleanup threshold reached
maintenance parked
manual quarantine
```

`PARKED_BAND` is not always "bad." It is also the correct place for resources
that are valid but should not burn recheck budget until demand or an explicit
operator/worker event promotes them.

### LOW_RECHECK_BAND

`LOW_RECHECK_BAND` means the slot is not schedulable now, but worker-runtime /
resource-runtime believes bounded recovery attempts are still useful.

Rules:

- it is an owner-defined recheck priority / retry-class range by default;
- network disconnect / no-current-endpoint should write an immediate low-recheck
  score, usually based on retry priority or attempt count, not a recheck
  timestamp;
- delayed low-recheck due-time is an explicit sub-policy for suspicious or
  backoff-worthy states, not the default meaning of the band;
- normal acquire never scans it;
- it is not the fast recovery path;
- it does not recover by time;
- failed recheck count can be encoded as low-recheck priority for simple
  network recovery, or kept as trace/diagnostic for richer policies;
- an owner maintenance recheck that still finds the slot unavailable may rewrite
  priority/count or, for suspicious states only, write a delayed backoff score;
- when owner policy reaches the configured failed-recheck threshold,
  resource-runtime
  may remove the slot or move it to `PARKED_BAND`;
- only the resource owner can move it back after positive recovery validation.

Typical reasons:

```text
confirmed current-session disconnect
worker/device temporarily disconnected
delivery no endpoint, if explicitly allowlisted for worker block
recheck required after stale eligibility evidence
temporary resource health failure
```

### ELIGIBLE_BAND

`ELIGIBLE_BAND` means the slot is currently schedulable.

Conceptual window:

```text
max(TIME_SCORE_FLOOR, now - freshnessWindow) <= score <= now
```

Matcher can acquire only from this window. Final acquire still performs owner
validation because score is an index, not the full truth.

### FUTURE_BAND

`FUTURE_BAND` means the slot is temporarily unavailable until a timestamp.

Typical reasons:

```text
preallocated
occupied
attempt interval
cooldown
bounded unavailable interval
```

Rules:

- it is timestamp semantics;
- when time reaches the score, no write or queue move is needed; the existing
  score is now in the acquire-time range;
- if real work is still executing, the legal owner must extend the future
  score;
- if extension stops because of crash or lost release, time naturally makes the
  score due again. Acquire still performs owner validation before binding.

## Score Setting Rules

Score must have one consistent write model. Otherwise score-band scheduling
becomes another unclear state machine.

Recommended first encoding:

```text
PARKED_BAND score
  negative fixed code range
  example: score < 0
  score identifies broad parked class only
  reasonCode, sourceType, actorId, observedAt, and reopen policy live in
  metadata / trace transition evidence

LOW_RECHECK_BAND score
  reserved owner-defined priority / retry-class range
  example: 0 <= score < TIME_SCORE_FLOOR
  network disconnect/no-endpoint may use score as retry priority or attempt
  count; it is not a timestamp
  delayed backoff, reasonCode, sourceType, actorId, observedAt, nextRecheckAt,
  and backoffPolicy live in trace / diagnostic evidence unless a later roadmap
  proves a runtime maintenance projection

TIME_BAND score
  example: score >= TIME_SCORE_FLOOR
  epochMillis
  score <= now may be eligible after validation
  score > now is future unavailable / occupied / cooldown time
```

The exact numeric constants can be implementation-specific, but the semantics
must stay fixed:

- parked score is not a timestamp;
- parked score is not a retry counter;
- parked score does not by itself explain full reason or reopen policy;
- low-recheck score is not an absolute epoch timestamp;
- low-recheck score is not a relative due-time unless the owning policy
  explicitly selects a delayed-backoff subrange for a suspicious state;
- low-recheck score is not a boolean block bit;
- low-recheck score must stay below `TIME_SCORE_FLOOR`;
- low-recheck score is updated only by resource-runtime according to owner
  recheck policy;
- failed recheck count must not be inferred from score and must not be stored
  in stable scheduling metadata;
- low-recheck cleanup or parking is threshold/policy based, not automatic time
  recovery;
- eligible/future score is time;
- reason is not inferred from score alone;
- score writes are owned by worker-runtime/resource-runtime;
- every score write emits transition evidence through trace or a test evidence
  sink by default. Redis transition streams are optional repair/debug
  infrastructure, not first-slice current-state truth.

Because `PARKED_BAND` and `LOW_RECHECK_BAND` use non-epoch scores,
eligible-time acquisition must use an explicit time-band lower bound and must
not accidentally include parked codes or low-recheck due scores. Tests that use
fake clocks should set `now` above the configured time-score floor or use
explicit band predicates.

Recommended constant shape:

```text
PARKED_BAND:
  score < 0

LOW_RECHECK_BAND:
  0 <= score < TIME_SCORE_FLOOR

ELIGIBLE_BAND:
  TIME_SCORE_FLOOR <= score <= now

FUTURE_BAND:
  score > now
```

The low-recheck and time bands require explicit constants. They must be stable
configuration/code constants, not values inferred from the current clock.

Recommended shape:

```text
LOW_RECHECK_SCORE_FLOOR = 0
TIME_SCORE_FLOOR = a fixed high lower bound for real epochMillis time scores
```

`LOW_RECHECK_BAND` is reserved for owner-defined priority / retry-class scores.
For ordinary network disconnect recovery, the score may be a simple retry
priority or attempt count:

```text
lowRecheckScore = retryPriorityOrAttemptCount
```

If a specific owner policy needs delayed low-recheck backoff, that policy must
define an explicit subrange or maintenance projection. Generic low-recheck
logic must not assume that the score is a timestamp.

`TIME_SCORE_FLOOR` must be large enough that expected low-recheck priority /
retry-class scores never overlap real epochMillis time scores:

```text
0 <= lowRecheckScore < TIME_SCORE_FLOOR
```

This separation is mandatory. `LOW_RECHECK_BAND` uses owner-defined non-time
scores by default;
`ELIGIBLE_BAND` and `FUTURE_BAND` use real epochMillis scores.

If an owner computes a low-recheck score outside that range, it must not write
the value into the ZSET. It should either clamp through an explicit owner
policy, move the slot to `PARKED_BAND`, or fail the transition with diagnostic
evidence. Silent overflow into `ELIGIBLE_BAND` or `FUTURE_BAND` is forbidden.

Redis ZSET query shape:

```text
eligible acquire:
  ZRANGE key max(TIME_SCORE_FLOOR, now - freshnessWindow) now BYSCORE LIMIT 0 N

future due / no freshness-window acquire:
  ZRANGE key TIME_SCORE_FLOOR now BYSCORE LIMIT 0 N

low-recheck inventory:
  ZRANGE key 0 (TIME_SCORE_FLOOR BYSCORE LIMIT 0 N

parked inventory:
  ZRANGE key -inf -1 BYSCORE LIMIT 0 N
```

Hot-path acquire should use bounded `LIMIT`. Parked and low-recheck scans are
maintenance paths, not task hot paths.

### Score Write Table

| Trigger | Owner | New score | Band | Notes |
| --- | --- | --- | --- | --- |
| Slot declared and owner validates schedulable | worker-runtime/resource-runtime | `now` | `ELIGIBLE_BAND` | Initial open is owner validation, not transport connect. |
| Slot declared but intentionally idle or disabled | worker-runtime/resource-runtime | parked code, for example `PARKED_IDLE` or `PARKED_OPERATOR_DISABLED` | `PARKED_BAND` | No periodic recheck budget should be spent until explicit promotion or demand policy. |
| Slot declared but requires bounded readiness recheck | worker-runtime/resource-runtime | owner-defined low-recheck priority/count or delayed score if explicitly required | `LOW_RECHECK_BAND` | Reason can be `INIT_REQUIRED` or `RECHECK_REQUIRED`; failed count may be score for simple network recovery or trace/test evidence for richer policy. |
| Fresh network evidence validated while in `LOW_RECHECK_BAND` | worker-runtime, after point-reading transport freshness | `now` | `ELIGIBLE_BAND` | Network freshness is the minimum positive eligibility evidence. It cannot clear parked/platform block state. |
| Explicit unpark / resume / enable command validated | worker-runtime/resource-runtime | `now` or low-recheck due score | `ELIGIBLE_BAND` or `LOW_RECHECK_BAND` | Required to unpark `PARKED_BAND`; `AVAILABLE` / `READY` alone is insufficient. |
| Explicit worker `OFFLINE` / unavailable report | worker-runtime | owner-defined low-recheck priority/count or parked code, depending on policy | `LOW_RECHECK_BAND` or `PARKED_BAND` | Negative business-state report closes eligibility and may be recoverable only if owner policy allows it. |
| Explicit worker `DRAINING` report | worker-runtime | parked code, for example `PARKED_DRAIN` | `PARKED_BAND` | Drain should not burn periodic recheck budget. |
| Operator disable / drain | worker-runtime/resource-runtime | parked code, for example `PARKED_OPERATOR_DISABLED` | `PARKED_BAND` | Operator policy is not time-releasable. |
| Confirmed current-session disconnect | worker-runtime via allowlisted transport evidence | owner-defined low-recheck priority/count | `LOW_RECHECK_BAND` | Transport proves hard dispatch blocker; worker-runtime owns the transition. Do not encode a default recheck timestamp for ordinary disconnect. |
| Maintenance recheck still unavailable | worker-runtime/resource-runtime | updated low-recheck priority/count, or delayed score only for explicit suspicious/backoff policy | `LOW_RECHECK_BAND` | Failed count may be score for simple network recovery or trace/test evidence for richer policies. |
| Low-recheck failed count reaches threshold | worker-runtime/resource-runtime | parked code or remove/cold transition | `PARKED_BAND` or outside normal acquire | Cleanup/parking/removal is policy-owned and must emit transition evidence. |
| `WORKER_HEARTBEAT` / `TRANSPORT_REFRESH` / `SESSION_KEEPALIVE` / `CONNECTED` | transport | no score write | unchanged | Freshness evidence stays transport-local unless point-read during an allowed owner recheck. |
| Preallocation acquire | matcher through resource-runtime | `now + preallocUntilMillis` | `FUTURE_BAND` | Claim owner is demand lane / matcher. |
| Dispatch / attempt starts | engine through resource-runtime | `attemptUntilMillis` | `FUTURE_BAND` | Converts or extends preallocation into attempt interval. |
| Attempt interval extension | engine through resource-runtime | new `attemptUntilMillis` | `FUTURE_BAND` | Only valid when owner preconditions still allow extending current work. |
| Cooldown | resource-runtime policy | `now + cooldownMillis` | `FUTURE_BAND` | Time-releasable policy interval. |
| Claim close | engine through resource-runtime | validated score transition | unchanged, `ELIGIBLE_BAND`, or `FUTURE_BAND` | `release`, `final`, and `cancel` are reason names for the same close transition. Claim close cannot directly reopen from parked or low-recheck; owner validation decides. |
| Future score due | no writer required | existing timestamp is now within acquire range | time-due candidate | This is read-time interpretation, not an event. Acquire still validates current slot metadata before binding. |
| Attempt timeout | engine through resource-runtime | task/attempt evidence only | unchanged, or owner-classified negative state | Timeout is not positive claim close, is not required for future score due, and must not write an eligible score. |

### Eligible Score Freshness

The value in `ELIGIBLE_BAND` is not "heartbeat time." It is the last time the
resource owner verified that this slot may compete, or the timestamp of an
expired non-negative interval.

If an implementation uses a sliding freshness window:

```text
max(TIME_SCORE_FLOOR, now - freshnessWindow) <= score <= now
```

then an old eligible score eventually ages out of acquisition and needs
resource-runtime maintenance or an allowed recovery check to be refreshed.
Transport heartbeat still must not refresh that score directly.

If a resource type does not require a sliding freshness window, the owner may
use a wider eligible lower bound. That is a resource-runtime policy decision,
not a transport decision.

### Parked Band Codes

Use parked codes as broad index classes only. They should not encode the full
state machine.

Example:

```text
PARKED_IDLE = -1
PARKED_DRAIN = -2
PARKED_OPERATOR_DISABLED = -3
PARKED_GROUP_DISABLED = -4
PARKED_COLD = -5
PARKED_MANUAL_QUARANTINE = -6
```

The exact codes are implementation-owned, but the rule is fixed:

```text
score < 0
  -> not acquired
  -> not periodically rechecked
  -> reopened only by explicit owner event or explicit policy promotion
```

### Low-Recheck Priority And Optional Delay

The value in `LOW_RECHECK_BAND` is not an age and not an absolute timestamp. It
is an owner-defined recheck priority or retry-class value. For ordinary network
disconnect / no-current-endpoint, the simplest interpretation is an immediate
priority/count score:

```text
score = lowRecheckPriorityOrAttemptCount
```

Delayed low-recheck due-time is reserved for suspicious or backoff-worthy
states such as repeated handler failures. If added, the delay policy must be
explicit and must not be assumed by generic network recovery:

```text
failedRecheckCount
lastRecheckAtMillis
nextRecheckAtMillis
backoffPolicy
```

Example:

```text
worker-a initially eligible
  score = now

transport current-session disconnect accepted
  score = 1
  band = LOW_RECHECK_BAND
  transitionType = RECOVERABLE_CLOSE
  reasonCode = CURRENT_SESSION_DISCONNECTED
  failedRecheckCount = 0

first maintenance recheck still unavailable
  score = 2
  band = LOW_RECHECK_BAND
  failedRecheckCount = 1

second maintenance recheck still unavailable
  score = 3
  band = LOW_RECHECK_BAND
  failedRecheckCount = 2

failedRecheckCount reaches cleanup threshold
  worker-runtime/resource-runtime removes the slot or moves it to PARKED_COLD
```

Recheck cadence is therefore represented by the low-recheck score itself. Do
not add a second low-recheck due index unless a later proof shows this score
encoding is insufficient.

If recovery succeeds at any recheck:

```text
score = now
band = ELIGIBLE_BAND
```

Only worker-runtime/resource-runtime may perform that transition.

### Low-Recheck Recovery Contract

`LOW_RECHECK_BAND` exists to keep recoverable negative state out of normal
acquire while still making it available to bounded owner recheck. It should not
be modeled as a second time queue by default.

Network recovery path:

```text
worker-runtime selects worker for recheck
  -> point-read transport freshness
  -> validate declaration / group / platform block / hold
  -> owner may reopen slot into ELIGIBLE_BAND
```

Fallback recovery path:

```text
slot is in LOW_RECHECK_BAND
  -> bounded owner maintenance may recheck it
  -> failed recheck updates priority/count or explicit delayed backoff policy
  -> threshold moves slot to PARKED_BAND or removes it
```

Rules:

- scheduling hot path never scans `LOW_RECHECK_BAND`;
- `LOW_RECHECK_BAND` should not be used to chase response speed;
- worker heartbeat, session keepalive, and transport reconnect update
  transport-local freshness;
- worker-runtime may use that freshness through a narrow point-read during
  recheck; no per-worker `RecoveryMode` is required;
- a worker-provided `nextRecheckAt` or recovery hint, if added later, is only a
  hint to worker-runtime/resource-runtime and never a direct score write;
- owner must validate and bound any worker-provided recheck hint before
  computing a low-recheck score;
- owner maintenance should be bounded, backoff-aware, and allowed to be
  demand-triggered rather than continuously scanning every low-recheck slot.

### Atomicity Rule

Changing score and changing any stable scheduling metadata in the same owner
operation is one resource-runtime transition from the caller's perspective.

For Redis this usually means a small Lua script or equivalent atomic primitive
for:

```text
validate current band and owner preconditions
validate current worker metadata and recovery/admission preconditions
write score
write stable scheduling metadata only when this transition changes it
```

After a successful current-state transition, the owner emits transition
evidence to trace or a test evidence sink. That evidence emission is not part of
the Redis current-state atomic unit unless a later repair/debug roadmap proves
the need for a Redis transition stream. If a `transitionId` is generated, it is
evidence-only. It must not be supplied by callers or used to drive a future
worker-runtime transition.

Do not put ranking, lane policy, or task scheduling strategy into that atomic
primitive. Lua or CAS protects the resource transition invariant; it does not
own scheduling policy.

## Score Transition Log

Score is a hot-path index, not the full explanation. Every meaningful score
change should emit transition evidence.

The state machine must be driven by a finite `transitionType`, not by raw
business event names. Business events are open-ended and will grow with
adapters, domains, operators, and worker implementations. Transition types are
the stable mechanism vocabulary.

Owner flow:

```text
raw event / command / lifecycle callback
  -> owner classification
  -> transitionType
  -> precondition validation
  -> atomic score + metadata transition
  -> transition evidence
```

`eventName` is allowed only as evidence. It must not be the state-machine key.

Suggested transition types:

```text
PARK
UNPARK_TO_RECHECK
RECOVERABLE_CLOSE
RECHECK_FAILED_RESCHEDULE
RECHECK_VERIFIED_REOPEN
VERIFIED_REOPEN
LEASE_ACQUIRE
LEASE_RENEW
LEASE_RELEASE
LEASE_EXPIRE
COOLDOWN_HOLD
REMOVE_SLOT
```

Suggested fields:

```text
resourceId
resourceKind
oldScore
oldBand
newScore
newBand
transitionType
reasonCode
sourceType
ownerAction
rawEventName
actorId
claimRef
attemptId
correlationRef
observedAt
transitionId
```

Field roles:

```text
transitionType
  finite mechanism type; drives legal state transition

reasonCode
  finite or semi-finite owner reason; explains why the transition happened

sourceType
  broad evidence source such as WORKER_REPORT, TRANSPORT_EVIDENCE,
  OPERATOR_COMMAND, ENGINE_LIFECYCLE, or RESOURCE_MAINTENANCE

ownerAction
  owner decision such as VERIFIED_REOPEN, CLOSE_RECOVERABLE, PARK_RESOURCE,
  RESCHEDULE_RECHECK, ACQUIRE_LEASE, or RELEASE_LEASE

rawEventName
  original event name or command name for trace/debug only

transitionId
  runtime-generated evidence id for trace / evidence correlation only; it must
  not be accepted from callers, passed into worker-runtime as a control
  parameter, or used as a transition precondition
```

Example transitions:

```text
worker-a
  ELIGIBLE_BAND -> FUTURE_BAND
  transitionType = LEASE_ACQUIRE
  reasonCode = PREALLOCATED
  sourceType = MATCHER
  ownerAction = ACQUIRE_LEASE
  claimRef = prealloc-xxx

worker-a
  FUTURE_BAND -> LOW_RECHECK_BAND
  transitionType = RECOVERABLE_CLOSE
  reasonCode = CURRENT_SESSION_DISCONNECTED
  sourceType = TRANSPORT_EVIDENCE
  ownerAction = CLOSE_RECOVERABLE
  rawEventName = WEBSOCKET_SESSION_CLOSED

worker-a
  ELIGIBLE_BAND -> PARKED_BAND
  transitionType = PARK
  reasonCode = OPERATOR_DRAIN
  sourceType = OPERATOR_COMMAND
  ownerAction = PARK_RESOURCE
  rawEventName = WORKER_DRAIN_COMMAND

device-001
  ELIGIBLE_BAND -> FUTURE_BAND
  transitionType = LEASE_ACQUIRE
  reasonCode = EXCLUSIVE_DEVICE_ATTEMPT_LEASE
  sourceType = ENGINE_LIFECYCLE
  ownerAction = ACQUIRE_LEASE
  attemptId = attempt-xxx
```

Transition evidence explains slot movement. It is not scheduling truth and must
not become a second state machine. The default first-slice sink is trace/test
evidence, not Redis.

## Demand Lane

A demand lane is the task-side scheduling input consumed by matcher. It is not
a worker index.

It may include:

```text
laneId
project/workload
workerGroup scope
resource requirements
priority / weight
desiredWarmSlots
maxWarmSlots
task intake and ready counts
```

WorkerGroup may remain part of lane scope, but it should not be the primary
hot-path worker enumeration model.

## Demand-Guided Sparse Acquire

First implementation should focus on demand-guided acquire. Supply-push /
worker-runtime proactively matching ready supply to demand lanes is a later
optimization mode and should not be part of the first score-band slice.

Demand-guided acquire is not task-side worker search. The boundary is:

```text
Task declares demand
  -> scheduling policy compiles an acquire plan
  -> resource-runtime uses approved placement tags as supply-side acceleration
  -> resource-runtime validates and claims slots
```

The task must not query workers, choose bucket keys, or create placement tag
specs at runtime.

### Task Demand Shape

Task demand should split matching requirements into explicit categories:

```text
exactRequirements
  fixed equality requirements that may be eligible for placement-tag
  acceleration after policy approval

rangePredicates
  interval or predicate requirements that must be validated by resource-runtime
  or an owner-approved validation function

desiredSlots / maxCandidates / maxAttempts
  bounded acquire budget, not an instruction to enumerate workers
```

Examples:

```text
exactRequirements:
  region = SG
  fingerprintProfile = fp-alpha

rangePredicates:
  battery >= 60
  networkLatencyMillis < 200

desiredSlots:
  100
```

Range predicates are allowed in demand, but they are not placement tags by
default. They remain validation unless a long-lived owner-approved
`PlacementTagSpec` projects them into finite buckets.

### PlacementTagSpec

`PlacementTagSpec` is a long-lived supply-side acceleration config. It is not
a task API field and not a per-task index.

Suggested shape:

```text
PlacementTagSpec
  name
  sourceAttributes
  projectionRule
  allowedValues
  maxValues
  fallbackPolicy
  owner
```

Placement tags should usually be composite tags for sparse matching, not a new
index for every single worker attribute.

Example:

```text
name = sg_fp_profile
sourceAttributes = [region, fingerprintProfile]
projectionRule = region + ":" + fingerprintProfile
allowedValues = ["SG:fp-alpha", "SG:fp-beta", "US:fp-alpha"]
maxValues = 100
fallbackPolicy = FALLBACK_TO_GROUP_SAMPLE
```

The runtime may maintain a bucket for:

```text
sg_fp_profile:SG:fp-alpha
```

It must not automatically create buckets for arbitrary task expressions or
unbounded attribute combinations.

Single-attribute tags should be exceptional. They are reasonable only when the
attribute is itself a strong partition, safety boundary, or high-selectivity
management dimension, such as tenant shard or resource pool. Ordinary
attributes should either be part of an approved composite tag or remain
Stage-2 validation.

### AcquirePlan

Scheduling policy compiles task demand into an acquire plan:

```text
AcquirePlan
  laneId
  resourceKind
  workerGroupScope
  placementTagBucketCandidates
  fallbackBuckets
  validationRef
  desiredSlots
  maxCandidates
  maxAttempts
  noMatchBehavior
```

Resource-runtime executes the plan:

```text
try approved placement tag buckets
  -> bounded sample eligible slots
  -> run exact/range validation
  -> claim validated slots
  -> if insufficient, apply fallback policy
```

Tag hit is only a coarse supply-side narrowing step. It is not correctness
proof. Final eligibility still requires score-band eligibility, owner
validation, and owner claim.

### Sparse Matching Guards

- Task does not query workers.
- Task does not select placement buckets.
- Task does not create `PlacementTagSpec`.
- `PlacementTagSpec` is owned by scheduling policy / resource-runtime owner.
- Placement tags are acceleration evidence, not worker correctness proof.
- Range predicates are validation by default, not bucket truth.
- Runtime must not auto-index arbitrary worker attributes.
- Runtime must not create buckets beyond `allowedValues` / `maxValues`.
- Fallback must be explicit and bounded; no implicit full WorkerGroup scan.
- First slice implements demand-guided acquire only; supply-push remains future
  direction.

## Worker Runtime Redis Shape

Concrete Redis runtime shape is split into a separate reference:
[Score-Band Worker Runtime Redis Shape](./score-band-worker-runtime-redis-shape.md).

The mechanism-level boundary remains:

```text
Eligibility Index
  score-band acquire index

Scheduling Metadata
  low-frequency owner-approved matching / validation projection
```

First-slice constraints:

- `resourceKind = worker`
- `resourceId = workerId`
- each `resourceId` has exactly one `homeBucketId`
- first worker slice uses `homeBucketId = workerGroupId`
- score and metadata share the same `homeBucketId`
- auxiliary placement indexes, if added later, do not own score or metadata
  truth
- transport/session/freshness evidence stays out of worker scheduling metadata

## Future Score Model

Non-negative unavailable periods are encoded by `FUTURE_BAND` score:

```text
preallocation interval
attempt active interval
cooldown interval
bounded unavailable interval
```

Release/final events are useful latency hints, but they are not required for
correctness and they must not directly reopen eligibility:

```text
release event arrives
  -> worker-runtime may request or perform owner-validated recheck

release event is lost
  -> FUTURE_BAND score becomes due by time
  -> slot becomes a due candidate
  -> acquire still validates owner state before binding
```

Preallocation should prefer lane binding before task binding:

```text
preallocation:
  lane -> resourceId

dispatch:
  task + resourceId -> attempt interval
```

This avoids complicated rollback when a task is canceled, reprioritized, or no
longer ready after a resource was warmed.

## Owner Boundaries

### Engine

Engine owns demand and task lifecycle.

Engine owns:

- task shell and intake;
- task-ready lane exposure;
- task priority / weight / desired warm-slot demand;
- assignment and attempt lifecycle;
- attempt timeout, retry, reassignment, pause, cancel, terminal convergence;
- result convergence;
- attempt interval extension while work is executing.

Engine must not own:

- worker/resource eligibility;
- resource slot score truth;
- worker candidate indexes;
- resource reopen after positive evidence;
- transport connection/session truth.

### Worker-Runtime / Resource-Runtime

Worker-runtime and future resource-runtime own supply.

They own:

- resource slot declaration and lifecycle;
- score-band index maintenance;
- external negative fast-close into `LOW_RECHECK_BAND` or `PARKED_BAND`;
- external positive evidence intake without direct score write;
- verified reopen from `LOW_RECHECK_BAND`;
- explicit unpark / resume / enable from `PARKED_BAND`;
- resource claims and future score intervals;
- score transition evidence emission;
- final slot validation before acquire/claim;
- resource-specific recovery policy.

They must not own:

- task terminal policy;
- task result finality;
- engine attempt retry policy;
- transport session mechanics.

### Matcher

Matcher binds demand to supply.

It owns:

- choosing demand lanes by engine-provided policy input;
- acquiring eligible slots from score-band supply;
- creating preallocation or attempt intervals;
- all-or-nothing multi-resource claim in later waves;
- returning selected resource handles to engine.

Matcher must not own:

- raw transport session state;
- task result convergence;
- worker capability truth mutation;
- long-term resource lifecycle cleanup outside resource-runtime.

### Transport

Transport remains a final-hop delivery and network-evidence subsystem.

Transport owns:

```text
delivery queues
endpoint/session/freshness evidence
mailbox and endpoint feasibility
dispatch outcome carrier facts
```

Transport must not:

- reopen resource slots;
- choose replacement workers;
- decide resource lifecycle;
- own score bands;
- turn heartbeat/connected into schedulable truth.
- let workers opt into schedulability policy through a recovery-mode attribute.

Transport is often the carrier for worker-originated reports, but carrier does
not mean owner. If a worker explicitly reports business availability,
unavailability, drain, or offline through a transport protocol, transport
normalizes/carries that report to the worker-runtime report/control owner. That
report is not a transport freshness event.

## Cross-Module Interaction Blueprint

Score-band scheduling should be read as three cooperating lanes, not one
monolithic scheduler.

```text
worker eligibility event lane
  explicit worker report / operator command / allowlisted negative session loss
  -> worker-runtime/resource-runtime
  -> score-band transition or verified recovery request

transport-local freshness lane
  WORKER_HEARTBEAT / TRANSPORT_REFRESH / SESSION_KEEPALIVE / CONNECTED
  -> transport endpoint/session/freshness evidence
  -> worker-runtime point-read during bounded recheck

demand and claim lane
  engine task demand lanes
  -> matcher
  -> resource-runtime eligible slot acquire
  -> preallocation interval or attempt interval
  -> engine attempt lifecycle

delivery and result lane
  engine selected execution identity
  -> transport selected-worker delivery
  -> worker executes
  -> result ingest
  -> engine result convergence
```

These lanes are allowed to exchange narrow evidence. They must not collapse
into one owner.

### Eligibility Event Routing Rule

An event crosses into the worker-runtime eligibility lane only when it directly
changes scheduling eligibility intent or proves a hard dispatch blocker.

Send to worker-runtime/resource-runtime when the event says:

```text
this worker/resource should now be schedulable after owner validation
this worker/resource should now be blocked or drained
this already-selected worker cannot currently receive dispatch because the
current session is gone
this task/attempt lifecycle changed resource claim ownership
```

Keep in the owning evidence store when the event only says:

```text
network path exists
transport/session heartbeat refreshed
mailbox may be reachable
transport endpoint freshness refreshed
diagnostic freshness changed
```

Examples:

```text
CONNECTED
  means network path exists
  does not mean business-ready, not drained, capacity-free, or policy-eligible
  stays transport-local

worker explicit AVAILABLE / READY report
  means the worker is asserting scheduling readiness
  enters worker-runtime report/control owner
  may request verified reopen from LOW_RECHECK_BAND
  cannot unpark PARKED_BAND

current-session disconnect
  proves the selected worker's current transport path is gone
  enters worker-runtime as allowlisted negative evidence
  may move slot to LOW_RECHECK_BAND

item final / assignment release / cancel
  means engine-owned attempt/resource claim is closing
  enters resource-runtime owner
  may close a FUTURE_BAND claim after owner validation

attempt timeout
  means the attempt exceeded engine-owned time budget
  is task/attempt evidence only
  does not positively close the claim into eligibility
  is not required for FUTURE_BAND score due
```

### Transport To Worker-Runtime

Transport is not a generic positive/neutral/negative evidence owner. Its
cross-boundary behavior depends on whether the event directly affects worker
scheduling eligibility intent or proves a hard dispatch blocker.

Transport-local freshness events stay local:

```text
WORKER_HEARTBEAT
TRANSPORT_REFRESH
SESSION_KEEPALIVE
CONNECTED
```

In this blueprint, `WORKER_HEARTBEAT` is treated as transport/session
freshness. It is not business availability and must not be used as a worker
readiness report. If the platform needs a worker to assert business-level
scheduling readiness, use an explicit worker-runtime report/control event such
as:

```text
WorkerStateReport(AVAILABLE)
WorkerAvailabilityReport
WorkerReadinessReport
```

These update transport endpoint/session/freshness evidence. They should not be
published as worker-runtime events, should not request worker-runtime recheck,
and should not move a resource slot to `ELIGIBLE_BAND`, because they only prove
network or session freshness, not scheduling eligibility.

Explicit worker reports are different:

```text
worker reports AVAILABLE / READY / business-available
  -> transport carries the report
  -> worker-runtime report/control owner validates it
  -> worker-runtime treats it as positive evidence
  -> if current band is LOW_RECHECK_BAND, owner may reopen to ELIGIBLE_BAND
  -> if current band is PARKED_BAND, owner records evidence but keeps parked

operator / owner sends explicit unpark / resume / enable
  -> worker-runtime/resource-runtime validates parked reason and policy
  -> slot may move to LOW_RECHECK_BAND or ELIGIBLE_BAND after validation

worker reports OFFLINE / DRAINING / unavailable
  -> transport carries the report
  -> worker-runtime report/control owner validates it
  -> slot may move to LOW_RECHECK_BAND or PARKED_BAND, depending on reason
```

Confirmed current session loss is an allowlisted transport negative signal:

```text
adapter observes current session disconnect
  -> transport validates current session identity
  -> starter / assembly bridge emits negative evidence
  -> worker-runtime maps evidence to resource slot
  -> slot score moves to LOW_RECHECK_BAND
  -> trace transition evidence records transitionType = RECOVERABLE_CLOSE
     and reasonCode = CURRENT_SESSION_DISCONNECTED
```

Mailbox availability, endpoint feasibility, and dispatch outcomes are delivery
facts. They primarily feed transport delivery or engine delivery-failure
handling. They become worker-runtime negative eligibility input only when a
separate owner decision explicitly allowlists that failure class.

Do not generalize delivery failure into worker blocking. `NO_ENDPOINT`, mailbox
unavailable, backpressure, invalid dispatch input, shutdown, and generic
delivery failure are different failure classes. Each one needs an explicit
owner decision before it may move a resource slot to `LOW_RECHECK_BAND` or
`PARKED_BAND`.

Transport must not do this:

```text
WORKER_HEARTBEAT / TRANSPORT_REFRESH / SESSION_KEEPALIVE / CONNECTED
  -> mark worker schedulable
  -> move slot to ELIGIBLE_BAND
```

Transport freshness may be point-read by worker-runtime during an already
allowed recovery check. It is not itself an eligibility event and is not a
recheck trigger.

### Worker Report / Command To Worker-Runtime

Worker business-state reports and operator commands directly express scheduling
eligibility intent and therefore belong to worker-runtime.

Examples:

```text
worker explicit AVAILABLE / READY report
operator enable
worker explicit OFFLINE / DRAINING / unavailable report
operator disable / drain command
```

These belong to the worker-runtime report/control owner. Positive reports may
request verified recovery. Negative reports may close eligibility. Transport
may carry these reports, but does not interpret them as transport freshness.

### Engine To Worker-Runtime

Task item and attempt lifecycle evidence is produced by engine, not transport.

Examples:

```text
item claimed
claim close
  reason = final | release | cancel

future score due
  no event and no writer; acquire can now see the existing score

attempt timeout
  task/attempt evidence only; not positive close
```

Claim close may close or shorten `FUTURE_BAND` after owner validation. Future
score due is not a claim close; it is read-time score interpretation. Attempt
timeout must not act as positive claim close. None of these paths may clear
parked or low-recheck blocks directly.

### Worker-Runtime To Matcher

Worker-runtime exposes supply, not task-specific worker search.

Target flow:

```text
worker/resource owner maintains score-band slots
  -> matcher requests eligible slots for a demand lane
  -> runtime acquire reads ELIGIBLE_BAND only
  -> runtime validates slot declaration, block sources, current score, and scope
  -> runtime writes preallocation or attempt interval
  -> matcher receives selected resource handle
```

The matcher should not receive raw reachability/readiness/occupancy state and
rebuild eligibility itself. It should acquire a bounded set of already eligible
slots and rely on runtime validation at claim time.

### Engine To Matcher

Engine supplies demand and lifecycle constraints.

Target flow:

```text
task runtime has ready demand
  -> engine exposes lane, priority, required resource scope, and desired count
  -> matcher claims eligible slots
  -> engine binds task work to selected resource handle
  -> engine owns attempt timeout, retry, pause, cancel, final convergence
```

Engine may create or extend attempt intervals because engine owns attempt
lifecycle. It must not reopen `LOW_RECHECK_BAND` / `PARKED_BAND` slots or
decide resource eligibility.

### Matcher To Engine

Matcher returns selected resource handles, not worker lists.

Target shape:

```text
SelectedResourceHandle
  resourceId
  resourceKind
  workerId or execution identity when resourceKind = worker
  claimRef
  claimUntil
  demandLaneId
  scoreTransitionRef
  dispatch evidence needed by engine
```

Early implementation may keep the current `SelectedWorkerHandle` external seam,
but internally it should represent a claimed resource id, not a candidate row
that survived task-side filtering.

### Engine To Transport

Engine dispatches only after a concrete execution identity is selected.

Target flow:

```text
engine binds task work to selected resource handle
  -> selected worker identity is already decided
  -> transport receives opaque payload + selectedWorkerId + correlation
  -> transport resolves final-hop mailbox/session through delivery evidence
  -> adapter sends to that selected worker
```

Transport does not choose a replacement worker when delivery is infeasible.
Delivery failure becomes delivery outcome or negative evidence, and engine
attempt timeout/retry/reassignment remains engine-owned.

### Result And Claim Closure

Result convergence and resource claim closure are separate but related.

Target flow:

```text
worker result arrives
  -> transport carries result ingress
  -> engine applies stable-final result convergence
  -> engine stops extending the attempt interval or asks resource-runtime to
     recheck early
  -> resource-runtime may close/shorten FUTURE_BAND only after owner validation
```

If result, release, or process shutdown evidence is lost, the slot is not stuck
forever because the existing `FUTURE_BAND` score becomes due by time.

### Interaction Matrix

| Producer | Signal / call | Consumer | Allowed effect | Forbidden effect |
| --- | --- | --- | --- | --- |
| Transport | current-session disconnect | Worker-runtime | move slot to `LOW_RECHECK_BAND` after validation | reopen eligibility |
| Transport | `WORKER_HEARTBEAT` / `TRANSPORT_REFRESH` / `SESSION_KEEPALIVE` / `CONNECTED` | Transport evidence store | update endpoint/session/freshness evidence only | publish worker-runtime event, request recheck, or imply business availability |
| Worker via transport | explicit AVAILABLE / READY / business-available report | Worker-runtime report/control owner | positive evidence; may request verified reopen only from `LOW_RECHECK_BAND` | transport directly moves slot to `ELIGIBLE_BAND` or clears `PARKED_BAND` |
| Worker via transport | explicit OFFLINE / unavailable report | Worker-runtime report/control owner | move slot to `LOW_RECHECK_BAND` after validation | transport owns block policy |
| Worker via transport / operator | explicit DRAINING / disable / intentional idle | Worker-runtime report/control owner | move slot to `PARKED_BAND` after validation | transport owns parked policy |
| Transport | mailbox / endpoint feasibility | Transport evidence store, delivery handoff, optional point-read view | prove final-hop feasibility or delivery failure | choose replacement worker, reopen slot, or block worker without allowlist |
| Transport | dispatch outcome | Engine / evidence sink / future allowlisted negative bridge | delivery failure evidence; maybe fast-close only if separately allowlisted by failure class | mutate task result, resource score, or all worker failures directly |
| Worker report / command | state or command evidence | Worker-runtime eligibility owner | block or request verified reopen | bypass resource owner |
| Engine | task-ready lane demand | Matcher | ask for eligible slots | enumerate workers |
| Matcher | slot claim request | Resource-runtime | move eligible slot to `FUTURE_BAND` interval | mutate task result |
| Engine | attempt active / renew | Resource-runtime | extend attempt interval | clear parked or low-recheck block |
| Engine | claim close, reason = final / release / cancel | Resource-runtime | same-claim close validation; may close or shorten `FUTURE_BAND` | decide positive recovery from parked/low-recheck band |
| Engine | attempt timeout | Resource-runtime | task/attempt evidence only; optional owner-classified negative state | write eligible score as positive close |

## Relationship To Existing Concepts

### Negative Signal Dispatch Eligibility

The negative-signal eligibility direction does not conflict with score-band
scheduling.

It defines the semantic invariant:

```text
negative evidence may fast-close eligibility
positive evidence is evidence-only and may only request owner recheck
PARKED_BAND requires explicit unpark / resume / enable
only the owner may reopen eligibility
```

Score-band score transition is the lower-level mechanism:

```text
recoverable negative evidence -> move slot to LOW_RECHECK_BAND
intentional stop / disable / idle -> move slot to PARKED_BAND
positive evidence while LOW_RECHECK -> owner recheck -> maybe ELIGIBLE_BAND
positive evidence while PARKED -> record evidence, remain PARKED_BAND
explicit unpark / resume / enable -> owner validation -> LOW_RECHECK_BAND or ELIGIBLE_BAND
non-negative unavailable interval -> FUTURE_BAND score
```

### WorkerGroup

WorkerGroup does not need to be removed in early waves.

It can remain:

- capability namespace;
- policy scope;
- tenant/isolation boundary;
- management view;
- migration bridge;
- slot namespace or lane scope.

The target is not "delete WorkerGroup." The target is "do not use
WorkerGroup-first worker enumeration as the hot-path scheduling model."

### Current WorkerRegistry

The current `WorkerRegistry` owns important facts: worker metadata, dispatch
disable sources, reservation, active work, exclusive lock, and candidate
bucket acquisition.

Under score-band direction, it should converge toward a resource-slot registry.
Current candidate APIs are migration surfaces, not the final shape.

### Current WorkerSelectionRuntime

`WorkerSelectionRuntime` remains a useful engine-facing seam.

Early migration should preserve the external seam where possible while changing
its internals:

```text
old internals:
  candidate batch -> filter -> rank -> reserve

target internals:
  demand lane -> acquire eligible slots -> claim -> selected handle
```

## Program Map

This blueprint should produce multiple executable roadmaps. It should not be
implemented as one giant roadmap.

### Program 1: Worker Runtime Score-Band Slot Registry

Goal:

Introduce worker resource score-band registry inside worker-runtime /
runtime-api, with memory and Redis implementations sharing contract tests.

First scope:

- model worker resource with `resourceKind=worker` and `resourceId=workerId`;
- map recoverable negative block to `LOW_RECHECK_BAND`;
- map intentional idle/disable/drain/cold lifecycle to `PARKED_BAND`;
- map preallocation/reserve/active attempt/cooldown to `FUTURE_BAND`;
- allow time-due acquire visibility from `FUTURE_BAND` without a writer;
- require owner validation before reopening from `LOW_RECHECK_BAND`;
- require explicit unpark / resume / enable before leaving `PARKED_BAND`;
- emit score transition evidence to trace/test evidence by default.

Do not change engine assignment semantics in the first slice.

Proof:

- memory and Redis contract tests;
- recoverable negative signal moves to low-recheck band and cannot
  time-recover;
- parked slot is not acquired and is not periodically rechecked;
- low-recheck slot is not acquired by the hot path and does not promise fast
  recovery;
- future score naturally becomes due when `now >= score` and still requires
  validation;
- stale claim-close evidence does not directly reopen eligibility or corrupt
  score;
- positive connected/heartbeat cannot reopen low-recheck or parked slot;
- explicit worker availability/readiness report can request owner-validated
  reopen;
- transition evidence records `transitionType`, `reasonCode`, owner action,
  source type, and raw event evidence.

### Program 2: Matcher Slot Acquire

Goal:

Move worker selection internals from candidate/filter/reserve to eligible slot
acquire and owner claim.

First scope:

- keep `WorkerSelectionRuntime` as the engine-facing seam;
- add a demand-lane request shape behind or beside current
  `WorkerSelectionRequest`;
- compile task demand into an `AcquirePlan`;
- use approved composite `PlacementTagSpec` buckets only as bounded
  acceleration;
- keep range predicates in owner validation;
- acquire bounded eligible slots from score-band;
- perform owner revalidation at claim time;
- produce selected handles compatible with current dispatch binding.

Retire:

- `WorkerCandidateIndex` as hot-path candidate owner;
- group-first candidate enumeration as the primary source;
- warm pool as a task-specific pseudo-candidate truth unless redefined as
  demand-lane preallocation evidence.

Proof:

- no full worker group scan on hot path;
- no task-side worker enumeration;
- task cannot create placement tag specs or choose bucket keys;
- placement tag hit still requires validation and owner claim;
- fallback is explicit and bounded;
- bounded slot acquire;
- selected handle still binds one execution identity;
- existing task dispatch correctness remains unchanged externally.

### Program 3: Engine Attempt Interval Integration

Goal:

Make engine assignment and attempt lifecycle extend resource unavailable
intervals explicitly.

First scope:

- preallocation interval may exist before concrete task binding;
- dispatch converts preallocation to attempt active interval;
- engine extends the attempt interval while work is active;
- claim close with reason final/release/cancel may close early;
- crash or lost release falls back to the future score becoming due by time.

Proof:

- task final/release/cancel closes or stops interval extension;
- engine crash does not orphan a resource indefinitely;
- attempt timeout/retry remains engine-owned;
- due attempt interval makes the slot acquire-visible by score; binding still
  requires owner validation and no negative block.

### Program 4: Multi-Resource Slot Claims

Goal:

Extend from worker execution slot to all-or-nothing multi-resource claims.

Later resource kinds:

```text
device
account
proxy
sim
browser
```

First scope:

- define resource requirement expression;
- acquire worker resource plus one additional exclusive resource;
- all-or-nothing claim;
- failure releases all partial claims;
- transition evidence shows each resource claim.

Proof:

- worker + device atomic claim;
- device conflict blocks competing task even if worker is eligible;
- partial failure leaves no leaked future-band slots;
- future score due makes non-negative unavailable intervals acquire-visible;
  binding still requires owner validation.

## Required Invariants

- Score is the hot-path eligibility index, not the full truth.
- `PARKED_BAND` never recovers by time and is not periodically rechecked.
- `LOW_RECHECK_BAND` never recovers by time; it recovers only through owner
  recheck or explicit owner event.
- `FUTURE_BAND` becomes time-due by score interpretation unless renewed or
  moved to parked / low-recheck band; no timeout event or writer is required.
- Negative evidence may fast-close eligibility.
- Positive evidence may only request owner recheck.
- Positive evidence cannot unpark `PARKED_BAND`.
- Fast recovery from low-recheck requires explicit worker report or owner event;
  heartbeat/reconnect is not enough.
- Scheduling hot path never scans parked or low-recheck bands.
- State-machine proof must assert `transitionType + oldBand + newBand + owner`;
  raw event names are evidence only.
- Only resource owner may reopen eligibility.
- Every non-negative unavailable interval is time-bounded by score.
- Preallocation is a claim interval, not durable assignment.
- Actual dispatch extends or converts a claim interval into an attempt interval.
- Multi-resource claims are all-or-nothing.
- Engine does not enumerate workers for a task.
- Task demand does not create placement indexes, select bucket keys, or query
  workers directly.
- Placement tags are approved long-lived supply-side acceleration indexes, not
  task-level correctness truth.
- WorkerGroup may scope lanes or slots, but must not be the primary hot-path
  worker enumeration model.

## Do Not Start With

- A generic resource abstraction that tries to model every future resource kind.
- A large rewrite of engine assignment and worker-runtime in one phase.
- A Redis-only zset implementation without memory contract parity.
- New task-side worker indexes.
- Per-task placement tag specs or automatic bucket creation from arbitrary
  worker attributes.
- Rebranding current candidate buckets as score-band without changing claim
  semantics.
- Making heartbeat or connected events reopen slots.
- Treating transition evidence as scheduling truth.
- Removing WorkerGroup before the slot model is proven.

## First Useful Slice

The first useful implementation slice should be small and owner-bounded:

```text
worker execution slot score-band registry
  memory + Redis contract
  PARKED_BAND / LOW_RECHECK_BAND / ELIGIBLE_BAND / FUTURE_BAND semantics
  recoverable negative block -> LOW_RECHECK_BAND
  intentional idle/disable/drain/cold -> PARKED_BAND
  future score -> time-due candidate
  positive evidence cannot directly reopen
```

The existing engine-facing `WorkerSelectionRuntime` can stay unchanged for this
slice. The goal is to prove the slot lifecycle model before replacing matcher
internals.

## Open Decisions

- Exact `TIME_SCORE_FLOOR`, parked score constants, eligible time window, and
  future interval timestamps.
- Whether multi-capacity worker first slice uses one slot per permit or a
  hybrid slot plus permit count.
- Whether preallocation binds lane only or can bind task in special cases.
- How to represent demand lane identity and priority without turning it into a
  task-side worker index.
- Which composite `PlacementTagSpec` dimensions are worth approving, and what
  bounded fallback policies they use.
- Whether a later runtime repair/debug owner needs a Redis transition stream.
  First-slice transition evidence is trace/test evidence only.
- How to archive or supersede existing worker-candidate roadmaps once the first
  score-band roadmap is accepted.
