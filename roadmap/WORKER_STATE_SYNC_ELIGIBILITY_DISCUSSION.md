# Worker State Sync And Eligibility Discussion Note

Status: discussion note, not an implementation roadmap.

This note records the current owner discussion for worker state dimensions,
transport presence, candidate-set synchronization, and scheduling eligibility.
It is direction-setting only. Do not cite it as proof of current implementation
behavior.

Related:

- `xa-mass-worker-runtime/README.md`
- `transport/AGENTS.md`
- `roadmap/TRANSPORT_CONSUMER_LEASE_CONVERGENCE_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_COMPOSITE_ELIGIBILITY_SET_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_TRANSPORT_SESSION_PRESENCE_INGRESS_ROADMAP.md`

## Problem

Worker status is not one boolean. The platform needs to reason about at least
three independent dimensions:

- transport/network observation,
- dispatch-in-principle readiness,
- capacity/occupancy/admission.

If these are collapsed into one `available` flag, unrelated owners overwrite
each other:

- worker state `AVAILABLE` could accidentally clear command drain,
- node/group drain clear could reopen a worker still draining,
- transport reconnect could be mistaken for business readiness,
- capacity changes could churn candidate-set membership on every reserve/final.

The target direction is independent state dimensions plus explicit projection
into bounded candidate acquisition and final reserve.

## State Dimensions

Current conceptual dimensions:

| Dimension | Examples | Primary owner | Scheduling role |
| --- | --- | --- | --- |
| reachability | `ONLINE`, `STALE`, `OFFLINE`, `UNKNOWN` | worker runtime from transport / heartbeat observation | candidate lifecycle and source guard |
| readiness | `READY`, `DRAINING`, `MAINTENANCE` | worker runtime dispatch gate policy | candidate lifecycle and source guard |
| occupancy | `FREE`, `RESERVED`, `OCCUPIED`, `CAPACITY_FULL` | worker registry admission counters | final reserve/admission |

These dimensions are related but not interchangeable.

`OCCUPIED` is not automatically unschedulable. A worker with capacity greater
than one may have active work and still accept more work. `CAPACITY_FULL` or an
exclusive lease is what blocks new admission.

## Cross-Module Ownership

State synchronization is a cross-module boundary. A module may own raw evidence
without owning scheduling truth.

| Dimension | Raw evidence owner | Scheduling truth owner | Engine usage |
| --- | --- | --- | --- |
| reachability | transport session / heartbeat ingress | worker-runtime reachability projection and slot lifecycle | read candidate lifecycle evidence; never read transport session directly |
| readiness | worker report, worker command, node-group binding, operator control | worker-runtime source-scoped dispatch gate | read dispatch eligibility; never directly clear unrelated gate sources |
| occupancy | engine dispatch/result lifecycle emits reserve/release/final intent | WorkerRegistry / WorkerAdmissionRuntime admission counters | call reserve/confirm/release/final; never keep a second occupancy truth |

The owner split should be read as:

```text
transport
  -> observes connection / consumer lease / heartbeat delivery evidence
  -> emits presence observations

worker-runtime
  -> projects reachability for scheduling
  -> owns readiness dispatch gates
  -> owns worker slot lifecycle and candidate source indexes
  -> owns admission counters through WorkerRegistry

engine
  -> owns task scheduling decisions
  -> consumes worker-runtime candidate/admission/evidence surfaces
  -> does not own worker state truth

task runtime / result runtime
  -> owns work lease and result convergence
  -> emits release/final signals back to worker admission path
```

This prevents two common mistakes:

- transport observed `connected` does not mean transport owns worker `ONLINE`;
- engine triggered release/final does not mean engine owns worker load truth.

## Transport Versus Worker Runtime

Transport owns delivery feasibility evidence:

- consumer connection,
- selected-worker delivery lease,
- transport heartbeat,
- connection generation,
- disconnect/reconnect stale protection.

Worker runtime owns scheduling reachability evidence:

- last worker presence observation consumed by worker runtime,
- worker heartbeat deadline,
- reachability state,
- dispatch gate sources,
- slot lifecycle validation,
- reserve/capacity counters.

Transport may emit presence observations. Worker runtime decides how those
observations affect scheduling reachability. Transport lease expiry does not
directly mean worker scheduling finality, and worker runtime heartbeat expiry
does not authorize transport to pick another worker for an already bound
dispatch.

## Candidate Set Synchronization

A candidate bucket is an acceleration index, not complete scheduling truth.

Candidate bucket membership should generally reflect:

- WorkerGroup membership,
- approved stable worker attributes,
- coarse lifecycle eligibility such as non-removing, heartbeat-fresh, and
  dispatch-enabled evidence when the runtime backend can maintain that
  projection cheaply.

Candidate bucket membership should not reflect:

- active lease count,
- reserved count,
- per-task occupancy,
- result state,
- transport connection record payload,
- arbitrary unapproved worker attributes.

The reason is update frequency. Occupancy changes on every reserve, claim,
dispatch, result, expiry, and release. Putting occupancy into bucket membership
would make every task execution mutate multiple Redis sets and would still need
atomic reserve revalidation.

## Synchronization Model

The preferred model is eventual candidate projection plus strict reserve:

```text
worker attribute / lifecycle update
  -> update canonical worker slot
  -> update affected candidate bucket membership or lifecycle projection
  -> stale bucket entries are tolerated

scheduling acquisition
  -> bounded candidate read
  -> source guard validates current slot lifecycle
  -> Stage-2 residual filter/rank
  -> tryReserve performs final atomic admission
```

This model accepts short windows of stale or duplicate candidates. Correctness
is preserved because source guard and reserve reject candidates that are no
longer eligible.

## Attribute Change Handling

Stable indexed attributes should update bucket membership:

```text
worker reports region changes SG -> MY
  -> worker runtime validates approved attribute update
  -> canonical slot attributes update
  -> remove worker from old region bucket
  -> add worker to new region bucket
  -> old stale entries are tolerated until bounded cleanup
```

For high-frequency observations such as battery, IP, raw network quality, or
account health, the platform should avoid direct high-cardinality indexing.
Instead, a worker-state owner or policy should convert them into coarse
approved scheduling attributes only when they are genuinely needed:

```text
raw battery=17%
  -> optional policy projection
  -> batteryClass=LOW
  -> dispatch gate or coarse candidate attribute if approved
```

Do not let arbitrary report fields become Redis index dimensions.

## Readiness Gate Sources

Readiness should remain source-scoped:

```text
WORKER_STATE
WORKER_COMMAND
NODE_GROUP_BINDING
OPERATOR_CONTROL
```

Clearing one source must not clear another source. For example:

- `AVAILABLE` state report clears only `WORKER_STATE`.
- `DRAIN` command keeps `WORKER_COMMAND` closed until its owner clears it.
- node-group drain clear does not reopen a worker command-drained worker.

This is why a single `dispatchEnabled` boolean is insufficient as owner truth.
It may be a derived read value, not the write model.

## Transport Consumer Lease Fields

Transport-side consumer lease records should prefer stable identity fields in
the record and high-frequency timestamps in separate structures:

Stable or low-frequency record fields:

- `deliveryBucketId`
- `selectedWorkerId`
- `adapterId`
- `transportInstanceId`
- `connectionId`
- `consumerEvidenceId`
- `generation`

High-frequency transport indexes:

- consumer heartbeat timestamp,
- consumer lease deadline,
- last delivery observation.

Rationale:

- reconnect and disconnect need generation checks to avoid stale writes,
- heartbeat and lease deadlines update frequently,
- repeatedly rewriting a large owner record for every heartbeat is unnecessary,
- transport timestamps are not worker scheduling truth.

## Delivery Bucket Versus Candidate Bucket

These concepts must stay separate:

| Concept | Owner | Meaning |
| --- | --- | --- |
| `candidateBucketKey` | worker runtime / scheduling policy | source index for worker matching |
| `deliveryBucketId` | transport | delivery partition for selected-worker dispatch |
| `selectedWorkerId` | engine scheduling result carried by transport | already selected execution identity |

`deliveryBucketId + selectedWorkerId` is appropriate for transport consumer
lease and delivery lookup. It is not a replacement for WorkerGroup-scoped
scheduling candidate indexes.

## Cleanup And Compensation

Foreground correctness should not depend on a large compensation service.

Preferred order:

1. keep foreground writes idempotent and generation-checked,
2. tolerate stale candidate entries,
3. reject stale entries at source guard or reserve,
4. perform bounded cleanup on miss, heartbeat expiry, removing slot, or periodic
   low-priority maintenance.

Compensation exists for crash and abnormal exit residue. It should not be the
normal path for keeping scheduling correct.

## Guardrails

- Do not collapse reachability, readiness, and occupancy into one owner flag.
- Do not let transport session/lease records become worker runtime truth.
- Do not put capacity or active lease state into candidate bucket membership.
- Do not let worker reports directly mutate scheduling buckets without worker
  runtime validation and approved attribute policy.
- Do not build a periodic scan that recomputes all bucket memberships for all
  workers as the normal path.
- Do not use task-local candidate pools as correctness truth.
- Do not let stale cleanup become required for immediate dispatch correctness.

## Open Questions

- Which worker attributes are approved for first-slice index projection?
- Should lifecycle projection be physically separate from membership for both
  memory and Redis, or only for Redis?
- What is the acceptable stale-candidate miss rate before adding another
  bucket dimension?
- Should coarse business state such as account health be a dispatch gate source,
  an indexed attribute, or Stage-2 residual policy?
- What diagnostics should explain candidate rejection: stale heartbeat,
  dispatch gate, residual attribute mismatch, capacity full, or exclusive lease?

## First Roadmap Candidate

If this becomes a roadmap, start with a narrow slice:

1. inventory current reachability, dispatch gate, candidate bucket, transport
   lease, and worker report update paths;
2. define the approved indexed attribute list and owner;
3. prove one attribute-change bucket update path in memory and Redis;
4. prove stale bucket entry rejection by source guard and reserve;
5. prove transport `deliveryBucketId` and consumer lease records do not enter
   worker matching truth.
