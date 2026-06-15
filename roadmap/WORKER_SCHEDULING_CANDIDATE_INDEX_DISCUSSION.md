# Worker Scheduling Candidate Index Discussion Note

Status: discussion note, not an implementation roadmap.

This note records the current owner discussion for large-scale worker matching.
It is direction-setting only. Do not cite it as proof of current implementation
behavior; verify current behavior from code, worker-runtime owner docs, and
runtime proof.

Related:

- `xa-mass-worker-runtime/README.md`
- `roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_COMPOSITE_ELIGIBILITY_SET_ROADMAP.md`
- `roadmap/RUNTIME_WORKER_SELECTION_RESIDUE_CONVERGENCE_ROADMAP.md`

## Problem

The platform target is not a small demo pool. A realistic deployment may have:

- millions of workers,
- multiple WorkerGroups,
- multiple projects with different business routing needs,
- multiple active tasks competing inside the same WorkerGroup,
- task-specific worker requirements based on stable worker attributes.

The scheduling mainline cannot rely on:

- scanning all workers in a WorkerGroup,
- using `eventCode` as a worker selector,
- maintaining a full candidate set per task,
- letting transport delivery buckets become scheduling truth.

The scalable source shape should be WorkerGroup-scoped candidate indexes derived
from worker attributes.

## Core Position

Worker candidate indexes should be organized like this:

```text
WorkerGroup
  -> CandidateBucketSpec / WorkerIndexProfile
      -> candidateBucketKey = groupId + indexSpecId + normalized(worker attributes)
          -> workerIds
```

Task scheduling then uses:

```text
Task
  -> explicit workerGroupId / workerGroupIds
  -> project/task policy resolves candidateBucketKeys
  -> WorkerRegistry bounded acquisition
  -> Stage-2 residual filter/rank/reserve
```

Important distinctions:

- `WorkerGroup` is the candidate index namespace.
- bucket membership is derived from worker attributes, not task state.
- project policy may choose which bucket dimensions to use, but it does not own
  a worker pool.
- task resolves bucket keys at scheduling time, but it does not own a full
  candidate set.
- transport `deliveryBucketId` is delivery partitioning only and must not become
  a worker scheduling bucket.

## Naming

Prefer names that make the scheduling owner visible:

- `candidateBucketKey`
- `workerCandidateBucket`
- `CandidateBucketSpec`
- `WorkerIndexProfile`

Avoid names such as `workerBucketId` when the context may confuse it with
transport `deliveryBucketId`.

## Example Shape

WorkerGroup:

```text
groupId = phone-device-probe
indexes:
  default
  region
  region+carrier
  fingerprintProfile
  region+fingerprintProfile
```

Worker attributes:

```json
{
  "workerId": "phone-device-probe-poll-sg-004",
  "workerGroupId": "phone-device-probe",
  "attributes": {
    "region": "SG",
    "carrier": "Singtel",
    "fingerprintProfile": "fp-sg-alpha"
  }
}
```

Possible memberships:

```text
phone-device-probe:index:default
phone-device-probe:index:region:SG
phone-device-probe:index:region+carrier:SG:Singtel
phone-device-probe:index:fingerprintProfile:fp-sg-alpha
phone-device-probe:index:region+fingerprintProfile:SG:fp-sg-alpha
```

Task shared config:

```json
{
  "workerGroupId": "phone-device-probe",
  "targetWorkerAttributes": {
    "region": "SG",
    "fingerprintProfile": "fp-sg-alpha"
  }
}
```

Resolved candidate source:

```text
primary:
  phone-device-probe:index:region+fingerprintProfile:SG:fp-sg-alpha
fallback:
  phone-device-probe:index:region:SG
  phone-device-probe:index:default
```

## Mechanism And Policy Boundary

Cross-module owner split:

| Concern | Owner | Must not own |
| --- | --- | --- |
| WorkerGroup capability and candidate index namespace | worker runtime / WorkerGroup owner | task-specific candidate membership |
| bucket membership from worker attributes | worker runtime / WorkerRegistry implementation | project-owned worker pool |
| project-specific index selection and fallback | project / scheduling policy owner | worker slot lifecycle or capacity truth |
| task bucket-key resolution | scheduling plane policy resolution | full candidate set |
| Stage-2 residual filter/rank/reserve | engine runtime worker selection plus worker-runtime admission | Stage-1 bucket membership truth |
| delivery partition and consumer lease | transport | worker matching or scheduling eligibility |

Mechanism:

- WorkerRegistry stores and acquires bounded candidates from bucket indexes.
- Worker runtime computes bucket membership from approved WorkerGroup/index
  configuration and worker attributes.
- Stage-2 validates current worker state, residual attributes, rank, and reserve.
- Reserve remains the final admission truth.

Policy:

- which worker attributes are approved for indexing,
- which project may use which index profile,
- bucket fallback order,
- bounded acquisition size,
- sampling/ranking strategy,
- warm candidate hint preference.

Policy may be simple in the first slice, but it must remain replaceable. Do not
hardcode business dimensions into the registry implementation.

## Filter Classification

Use Stage-1 indexes for stable, high-selectivity, high-frequency dimensions:

- `workerGroupId` is mandatory Stage-1 namespace.
- `adapterNodeId` can be Stage-1 locality when explicitly requested.
- `region`, `country`, `devicePool`, `carrier`, `accountType`,
  `fingerprintProfile`, or similar stable approved attributes may be Stage-1
  candidate buckets.

Keep Stage-2 for:

- residual worker attributes that are not indexed,
- complex rules,
- ranking,
- reachability revalidation,
- dispatch gate revalidation,
- capacity and reservation admission.

Do not put these into bucket membership:

- occupancy/capacity counters,
- active lease state,
- raw IP when it changes frequently,
- battery level or transient health readings unless a specific owner converts
  them into a coarse approved scheduling attribute,
- task-local experiment state.

## Warm Pool Relationship

A task-local warm pool may still be useful, but only as a bounded hint:

```text
candidate buckets -> source batch
warm hint -> priority merge / sampling bias
Stage-2 -> validation/rank/reserve
```

Warm pools must not become task-owned full candidate truth. A warm worker can be
stale, occupied, no longer matching residual attributes, or unreachable, so it
must always pass Stage-2 and reserve.

## Redis Shape Direction

The target Redis shape should avoid one large global worker set and avoid
per-task candidate sets:

```text
worker group scoped bucket:
  worker:{groupId}:candidate-bucket:{bucketKey}:members

optional lifecycle projection:
  worker:{groupId}:candidate-bucket:{bucketKey}:lifecycle-deadlines
```

The exact key names are not a decision in this note. The important shape is:

- bucket keys are group-scoped,
- membership is derived from worker attributes,
- acquisition is bounded,
- stale entries are correctness-neutral because source guard and reserve
  revalidate.

## Guardrails

- Task must not match workers without explicit WorkerGroup selection.
- Event code must remain handler/capability evidence, not worker selector.
- Project may select index profile, but must not own worker membership truth.
- Candidate bucket membership must be approved and bounded.
- Worker attribute changes may temporarily leave stale bucket entries; Stage-2
  and reserve must reject stale candidates.
- Do not create per-task full candidate sets.
- Do not let transport `deliveryBucketId` influence worker matching.
- Do not move capacity/occupancy into candidate bucket membership.

## Open Questions

- Should `CandidateBucketSpec` live on WorkerGroup, project policy, or a
  dedicated worker scheduling policy owner?
- What is the first allowed list of indexed worker attributes?
- How many bucket memberships may one worker have before fan-out is considered
  too high?
- Should fallback order be static per project or resolved dynamically from task
  policy?
- How should operator diagnostics explain why a task used a specific bucket?

## First Roadmap Candidate

If this becomes a roadmap, start with a narrow slice:

1. inventory current `WorkerCandidateBucketPolicy`,
   `ResolvedWorkerSchedulingPolicy.candidateBucketKeys`, and Redis/memory bucket
   behavior;
2. define `CandidateBucketSpec` / `WorkerIndexProfile` as policy vocabulary;
3. prove one stable attribute index such as `region` for memory and Redis;
4. keep Stage-2 residual validation and reserve unchanged;
5. add proof that no task-owned candidate set and no transport delivery bucket
   enters scheduling truth.
