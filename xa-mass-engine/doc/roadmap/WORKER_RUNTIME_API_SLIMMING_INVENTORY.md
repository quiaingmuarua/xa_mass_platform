# Worker Runtime API Slimming Inventory

Status: WRA-0 baseline for
[`WORKER_RUNTIME_API_SLIMMING_ROADMAP.md`](./WORKER_RUNTIME_API_SLIMMING_ROADMAP.md).

This inventory is the authoritative WRA-0 classification for the current
`platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker`
package. It is based on the current source file list plus production import
and reference scans.

Post-WRA-1a note: resource records and resource runtime contracts have moved
to `xa-mass-worker-runtime` under `com.xa.mass.worker.runtime.resource`. This
file remains the WRA-0 baseline and disposition record.

Post-WRA-1b note: candidate, scheduling-evidence, admission, wakeup, and warm
hint contracts have moved to `xa-mass-worker-runtime` under
`com.xa.mass.worker.runtime.candidate`, `com.xa.mass.worker.runtime.evidence`,
and `com.xa.mass.worker.runtime.admission`.

Post-WRA-1c note: report, state projection, and dispatch-gate contracts have
moved to `xa-mass-worker-runtime` under `com.xa.mass.worker.runtime.report`
and `com.xa.mass.worker.runtime.control`.

Post-WRA-2 note: `WorkerRouteBucketPolicies` is split. Memory/Redis keep only
the `WorkerRouteBucketPolicy` SPI plus `DefaultWorkerRouteBucketPolicy`;
platform approved-attribute routing lives in `xa-mass-worker-runtime`.

Post-WRA-3 note: `WorkerAdmissionContext`, `WorkerAdmissionPolicy`, and
`WorkerCleanupPolicy` have been deleted because they had no production
consumers and were not active registry extension points.

Consumer abbreviations:

```text
mem      platform_infra/mass-runtime-memory
redis    platform_infra/mass-runtime-redis
wr       xa-mass-worker-runtime
eng      xa-mass-engine
sdk      xa-mass-sdk
server   xa-mass-server
tx       transport/*
testing  xa-mass-testing
api      same mass-runtime-api package references
```

## Decisions

1. `EventKey` stays in `mass-runtime-api` for this roadmap because
   `WorkerRegistry.upsertSlot(...)` uses it as the low-level event binding
   ceiling. It must be documented as a project-scoped worker capability key,
   not as a globally unique event identity.
2. `WorkerAdmissionContext`, `WorkerAdmissionPolicy`, and `WorkerCleanupPolicy`
   had no production consumers outside their own declarations. WRA-3 deleted
   them instead of leaving unused policy seams in `mass-runtime-api`.
3. `WorkerRouteBucketPolicies` must be split. The low-level default-bucket
   helper remains in `mass-runtime-api`; approved worker attribute policy moves
   to `xa-mass-worker-runtime`.
4. `ReserveResult` / `ReserveStatus` remain registry primitives. WRA-0.5 added
   `WorkerAdmissionResult` / `WorkerAdmissionStatus`, and engine strategy now
   receives the worker-plane result rather than registry reserve primitives.
5. `WorkerResourceRuntime` remains the full composite resource surface for
   current assembly, but WRA-0.5 split narrow caller contracts:
   `WorkerResourceQueryRuntime`, `WorkerResourceDeclarationRuntime`, and
   `WorkerNodeBindingRuntime`.
6. Engine-side `WorkerRoutingPolicy` is task-side only after WRA-0.5; worker
   metadata bucket computation remains below the worker-runtime boundary.

## Inventory Table

| Type | Classification | Disposition | Production consumers | Allowed caller family |
| --- | --- | --- | --- | --- |
| `AdapterNodeRecord` | worker resource contract | move to worker-runtime resource | eng, sdk, wr | engine control/resource, SDK/server shell, worker-runtime impl |
| `CleanupSummary` | registry primitive | keep in runtime-api | mem, redis, api | memory/Redis, worker-runtime impl |
| `DispatchAvailabilitySource` | registry primitive | keep in runtime-api | eng, mem, redis, api, wr | memory/Redis, worker-runtime impl, engine control |
| `EventBinding` | worker resource contract | move to worker-runtime resource | eng, sdk, wr, api | engine control/resource, SDK/server shell, worker-runtime impl |
| `EventKey` | registry capability key | keep in runtime-api | eng, mem, redis, api, wr | memory/Redis, worker-runtime impl; project-scoped semantics only |
| `NodeGroupBindingRecord` | worker resource contract | move to worker-runtime resource | eng, sdk, wr, api | engine control/resource, SDK/server shell, worker-runtime impl |
| `RandomWorkerCandidateSamplingPolicy` | registry default policy | keep in runtime-api | mem, redis, api, wr | memory/Redis, worker-runtime impl |
| `ReserveResult` | registry primitive | keep in runtime-api | mem, redis, wr, api | memory/Redis, worker-runtime impl; not an engine match contract after WRA-0.5 |
| `ReserveStatus` | registry primitive | keep in runtime-api | mem, redis, wr, api | memory/Redis, worker-runtime impl; not an engine match contract after WRA-0.5 |
| `WorkerAdmissionContext` | unused registry extension residue | deleted in WRA-3 | api | none |
| `WorkerAdmissionPolicy` | unused registry extension residue | deleted in WRA-3 | none | none |
| `WorkerAdmissionResult` | worker admission result | move to worker-runtime admission | eng, testing, wr | engine match, worker-runtime impl, testing support |
| `WorkerAdmissionRuntime` | worker admission contract | move to worker-runtime admission | eng, sdk, testing, wr | engine match/resource release, SDK assembly, worker-runtime impl |
| `WorkerAdmissionStatus` | worker admission status | move to worker-runtime admission | eng, testing, wr | engine match, worker-runtime impl, testing support |
| `WorkerAvailabilityWakeupRuntime` | worker lifecycle/admission contract | move to worker-runtime admission | eng, sdk, wr | engine lifecycle wiring, SDK assembly, worker-runtime impl |
| `WorkerCandidateBatch` | worker candidate value | move to worker-runtime candidate | eng, wr, api | engine match, worker-runtime impl |
| `WorkerCandidateRow` | worker candidate value | move to worker-runtime candidate | eng, sdk, testing, wr, api | engine match, worker-runtime impl, testing support |
| `WorkerCandidateRuntime` | worker candidate source contract | move to worker-runtime candidate | eng, sdk, wr | engine match, SDK assembly, worker-runtime impl |
| `WorkerCandidateSamplingContext` | registry sampling primitive | keep in runtime-api | mem, redis, api | memory/Redis |
| `WorkerCandidateSamplingPolicy` | registry sampling extension point | keep in runtime-api | mem, redis, api | memory/Redis |
| `WorkerCapabilityReport` | worker report contract | move to worker-runtime report | eng, sdk, wr, api | engine control/report path, SDK/server shell, worker-runtime impl |
| `WorkerCapabilityReportResult` | worker report result | move to worker-runtime report | eng, sdk, wr, api | engine control/report path, SDK/server shell, worker-runtime impl |
| `WorkerCapabilityReportStatus` | worker report status | move to worker-runtime report | eng, wr, api | engine control/report path, worker-runtime impl |
| `WorkerCleanupPolicy` | unused registry extension residue | deleted in WRA-3 | none | none |
| `WorkerDispatchGateRuntime` | worker control/gate contract | move to worker-runtime control | eng, sdk, wr | engine control, SDK assembly, worker-runtime impl |
| `WorkerGroupCapabilityView` | scheduling evidence value | move to worker-runtime evidence | eng, wr, api | engine match, worker-runtime impl |
| `WorkerGroupRecord` | worker resource contract | move to worker-runtime resource | eng, sdk, testing, wr, api | engine control/resource, SDK/server shell, worker-runtime impl |
| `WorkerLoadSnapshot` | scheduling evidence value | move to worker-runtime evidence | eng, wr, api | engine match, worker-runtime impl |
| `WorkerMeta` | registry primitive | keep in runtime-api | mem, redis, wr, api | memory/Redis, worker-runtime impl; not an engine strategy contract after WRA-0.5 |
| `WorkerReachabilityState` | scheduling evidence enum | move to worker-runtime evidence | eng, sdk, testing, wr, api | engine match, SDK public shell, worker-runtime impl |
| `WorkerReachabilityView` | scheduling evidence contract | move to worker-runtime evidence | eng, sdk, wr | engine match, SDK assembly, worker-runtime impl |
| `WorkerRegistry` | registry SPI | keep in runtime-api | eng, mem, redis, sdk, server, wr, api | memory/Redis, worker-runtime impl, documented low-level assembly |
| `WorkerReportRuntime` | worker report contract | move to worker-runtime report | eng, sdk, wr | engine control/report path, SDK assembly, worker-runtime impl |
| `WorkerNodeBindingRuntime` | worker node/group binding mutation contract | move to worker-runtime resource | wr, api | worker-runtime impl; narrow mutation callers only |
| `WorkerResourceDeclarationRuntime` | worker resource declaration mutation contract | move to worker-runtime resource | wr, api | worker-runtime impl; SDK/server declaration paths |
| `WorkerResourceQueryRuntime` | worker resource lookup contract | move to worker-runtime resource | eng, tx, wr, api | engine control lookup, transport lookup, worker-runtime impl |
| `WorkerResourceRecord` | worker resource value | move to worker-runtime resource | eng, sdk, testing, tx, wr, api | engine control/resource, SDK/server shell, transport, worker-runtime impl |
| `WorkerResourceRuntime` | composite worker resource contract | move to worker-runtime resource | sdk, testing, wr | SDK/server assembly, worker-runtime impl; avoid for lookup-only transport/control callers |
| `WorkerRouteBucketPolicies` | mixed registry default and platform routing policy | split in WRA-2 | eng, mem, redis, wr | memory/Redis default helper; worker-runtime platform policy |
| `WorkerRouteBucketPolicy` | registry route-bucket SPI | keep in runtime-api | eng, mem, redis, wr, api | memory/Redis, worker-runtime impl; engine strategy import is WRA-2 route-policy residue |
| `WorkerSchedulingViewRuntime` | scheduling evidence contract | move or rename to worker-runtime evidence | eng, sdk, testing, wr | engine match, SDK assembly/testing, worker-runtime impl |
| `WorkerSlot` | registry primitive | keep in runtime-api | mem, redis, wr, api | memory/Redis, worker-runtime impl; not an engine match contract after WRA-0.5 |
| `WorkerStateProjection` | worker report/projection value | move to worker-runtime report | eng, sdk, wr, api | engine control/report path, SDK/server shell, worker-runtime impl |
| `WorkerStateProjectionResult` | worker report/projection result | move to worker-runtime report | eng, sdk, wr, api | engine control/report path, SDK/server shell, worker-runtime impl |
| `WorkerStateProjectionRuntime` | worker report/projection contract | move to worker-runtime report | eng, sdk, wr | engine control/report path, SDK assembly, worker-runtime impl |
| `WorkerStateProjectionStatus` | worker report/projection status | move to worker-runtime report | eng, wr, api | engine control/report path, worker-runtime impl |
| `WorkerStateReport` | worker report value | move to worker-runtime report | eng, sdk, wr, api | engine control/report path, SDK/server shell, worker-runtime impl |
| `WorkerTaskSelector` | task-side candidate selector | move to worker-runtime candidate/routing | eng, wr, api | engine match, worker-runtime impl |
| `WorkerWarmHintRuntime` | warm candidate hint contract | move to worker-runtime candidate/admission | eng, sdk, testing, wr | engine match/listener, SDK assembly/testing, worker-runtime impl |

## WRA-0 Acceptance Evidence

- Every current worker type has one owner classification in the table above.
- Every current worker type has an explicit disposition.
- Memory/Redis required types are identified from production imports:
  `CleanupSummary`, `DispatchAvailabilitySource`, `EventKey`,
  `DefaultWorkerRouteBucketPolicy`,
  `RandomWorkerCandidateSamplingPolicy`, `ReserveResult`, `ReserveStatus`,
  `WorkerCandidateSamplingContext`, `WorkerCandidateSamplingPolicy`,
  `WorkerMeta`, `WorkerRegistry`, `WorkerRouteBucketPolicy`, and
  `WorkerSlot`. At WRA-0 this list included `WorkerRouteBucketPolicies`; WRA-2
  split that class so the memory/Redis production dependency is now the
  registry-neutral default helper only.
- Public worker-runtime contracts now have allowed caller families.
- WRA-0.5 adds `WorkerAdmissionResult`, `WorkerAdmissionStatus`,
  `WorkerResourceQueryRuntime`, `WorkerResourceDeclarationRuntime`, and
  `WorkerNodeBindingRuntime`; they are worker-plane contracts and must move out
  of `mass-runtime-api` in WRA-1.
- WRA-0 introduces no code behavior change.
