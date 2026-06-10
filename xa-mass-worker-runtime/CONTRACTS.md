# Worker Runtime Contracts

Status: current module contract after WRX and WRA.

`xa-mass-worker-runtime` owns worker-plane lifecycle and evidence above the
low-level registry SPI. Engine owns scheduling decisions. Transport owns
protocol/session mechanics. The boundary is:

```text
xa-mass-engine       -> xa-mass-worker-runtime -> mass-runtime-api
transport/*         -> xa-mass-worker-runtime -> mass-runtime-api
sdk/xa-mass-embedded-sdk/server  -> xa-mass-worker-runtime -> mass-runtime-api

platform_infra/mass-runtime-memory -> mass-runtime-api
platform_infra/mass-runtime-redis  -> mass-runtime-api
platform_infra/mass-storage-memory -> xa-mass-worker-runtime
```

Memory and Redis registry implementations must not depend on this module.

## Ownership

Worker runtime owns:

- WorkerGroup, AdapterNode, NodeGroupBinding, and event binding declarations.
- Worker row to registry slot projection.
- Worker capability report application and bounded state projection.
- Worker reachability, load, and group capability evidence exposed to engine.
- Worker admission, capacity permits, dispatch gates, and exclusive leases.
- Worker command lifecycle truth: request records, status transitions,
  worker-pulled claims, delivery-attempt bookkeeping, expiry, and command
  lifecycle value contracts.
- Stage-1 worker candidate source, source guard, and warm/cold merge.
- Task-local warm candidate hint storage after engine observes useful Stage-2
  evidence.
- Platform-approved worker candidate bucket policy composition.

Worker runtime does not own:

- Task lifecycle, task runtime queue state, delayed availability, or item lease
  state.
- Dispatch binding, transport delivery, or result convergence.
- Rule evaluation, worker ranking, allocation budget, or terminal policy.
- Engine dispatch-control side effects produced from worker command lifecycle
  results.
- Transport protocol sessions, adapter-specific connection state, or SDK wire
  semantics.

## Contract Families

### Resource

Package: `com.xa.mass.worker.runtime.resource`

Owned contracts:

- `WorkerResourceRuntime`
- `WorkerResourceQueryRuntime`
- `WorkerResourceDeclarationRuntime`
- `WorkerNodeBindingRuntime`
- `WorkerResourceRecord`
- `WorkerRuntimeStateRecord`
- `WorkerGroupRecord`
- `AdapterNodeRecord`
- `NodeGroupBindingRecord`
- `EventBinding`

Role split:

- `WorkerDeclarationRecord` and `WorkerDeclarationStore` live in this module
  because worker declaration is worker-runtime lifecycle/control-plane
  ownership. The record contains stable worker identity, group/node membership,
  adapter hints, static attributes, max concurrency, and timestamps. It does
  not contain heartbeat, online/offline state, dispatch gates, reservations,
  leases, or worker-level supported project/event capability hints.
- `WorkerRuntimeStateRecord` is a current runtime-state view assembled from
  registry, reachability, heartbeat freshness, dispatch gate, and admission
  evidence. It exposes reachability/readiness diagnostics and occupancy
  evidence fields, but it is not persisted as declaration truth.
- `WorkerResourceRecord` is the current composite resource read model used by
  SDK/server/operator views and compatibility mutation surfaces. Because it
  contains status, heartbeat, and compatibility capability hints, it must not
  be described as declaration-store truth.

Allowed callers:

- Engine control/resource paths.
- SDK/server worker shell assembly.
- Transport lookup paths through `WorkerResourceQueryRuntime` only.
- Worker-runtime implementation.

Transport must not use declaration or binding mutation contracts.

### Candidate

Package: `com.xa.mass.worker.runtime.candidate`

Owned contracts:

- `WorkerCandidateRuntime`
- `WorkerCandidateBatch`
- `WorkerCandidateRow`
- `WorkerTaskSelector`

Allowed callers:

- Engine matching strategy.
- Worker-runtime implementation.
- Test support that proves candidate-source behavior.

Candidate acquisition must start from an explicit WorkerGroup selector. It must
not reintroduce all-worker scans.

### Evidence

Package: `com.xa.mass.worker.runtime.evidence`

Owned contracts:

- `WorkerSchedulingViewRuntime`
- `WorkerReachabilityView`
- `WorkerReachabilityState`
- `WorkerReadinessState`
- `WorkerOccupancyState`
- `WorkerGroupCapabilityView`
- `WorkerLoadSnapshot`

Allowed callers:

- Engine matching and diagnostics.
- SDK/server assembly when exposing worker state.
- Worker-runtime implementation.

Evidence is read-only scheduling input. It must not mutate worker lifecycle
truth.

Reachability, readiness, and occupancy are separate dimensions. `UNKNOWN`
reachability is an observation gap; only `ONLINE` is reachable for scheduling.
Readiness and occupancy states are diagnostic views derived from current
worker-runtime facts unless a later owner decision makes a stored field
canonical. Legacy `statusName` / worker `status` fields are display-only
compatibility and must not become scheduling truth.

`WorkerOccupancyState` is not an admission predicate. `OCCUPIED` may still be
schedulable when declared capacity remains; `WorkerAdmissionRuntime` and the
underlying `WorkerRegistry` reserve result own the binding decision.

Current engine scheduling proof treats worker state report `DRAINING` as
dispatch-gate unavailability from the worker-control owner path. A distinct
`DRAINING` label in `WorkerSchedulingView` is not current scheduling truth.

### Admission

Package: `com.xa.mass.worker.runtime.admission`

Owned contracts:

- `WorkerAdmissionRuntime`
- `WorkerAdmissionResult`
- `WorkerAdmissionStatus`
- `WorkerAvailabilityWakeupRuntime`
- `WorkerWarmHintRuntime`

Allowed callers:

- Engine matching and release paths.
- Engine lifecycle wakeup wiring.
- SDK/server assembly.
- Worker-runtime implementation.

Admission translates registry reserve/release primitives into worker-plane
results. Engine strategy must not consume `ReserveResult` or `ReserveStatus`
directly.

### Report

Package: `com.xa.mass.worker.runtime.report`

Owned contracts:

- `WorkerReportRuntime`
- `WorkerCapabilityReport`
- `WorkerCapabilityReportResult`
- `WorkerCapabilityReportStatus`
- `WorkerStateProjectionRuntime`
- `WorkerStateProjection`
- `WorkerStateProjectionResult`
- `WorkerStateProjectionStatus`
- `WorkerStateReport`

Allowed callers:

- Engine control/report paths.
- SDK/server worker reporting paths.
- Worker-runtime implementation.

Report contracts update worker-plane resource and projection truth. They do not
create task scheduling decisions.

`WorkerStateReport` is currently an open diagnostic string. Default scheduling
side effects are limited to an explicit dispatch-gate allowlist:

| State report value | Classification |
| --- | --- |
| `DRAINING` | dispatch-gate input; disables `WORKER_STATE` |
| `AVAILABLE` | dispatch-gate input; clears only `WORKER_STATE` |
| `DEGRADED` | diagnostic-only projection |
| `OFFLINE` | diagnostic-only projection; reachability owns offline scheduling evidence |
| `READY` | diagnostic-only projection |
| `INIT_REQUIRED` | target-only readiness vocabulary |
| `VERSION_MISMATCH` | target-only readiness vocabulary |
| `ACCOUNT_UNAVAILABLE` | target-only readiness vocabulary |
| `HEALTH_UNAVAILABLE` | target-only readiness vocabulary |

`AVAILABLE` must not clear `WORKER_COMMAND`; worker command drain remains a
separate dispatch-gate source.

### Control

Package: `com.xa.mass.worker.runtime.control`

Owned contract:

- `WorkerDispatchGateRuntime`

Allowed callers:

- Engine control paths.
- SDK/server worker control paths.
- Worker-runtime implementation.

### Command

Package: `com.xa.mass.worker.runtime.command`

Owned contracts:

- `WorkerCommandLifecycleOwner`
- `WorkerCommandRequest`
- `WorkerCommandAcknowledgement`
- `WorkerCommandRecord`
- `WorkerCommandStatus`
- `WorkerCommandLifecycleResult`
- `WorkerCommandLifecycleResultCode`
- `WorkerCommandDeliveryPort`
- `WorkerCommandDeliveryResult`
- `WorkerCommandDeliveryStatus`

Allowed callers:

- Engine worker-control paths and worker-command delivery coordination.
- SDK/server worker command APIs.
- Worker-runtime implementation and tests.

Worker command lifecycle truth is worker-scoped runtime/control truth. Engine
may translate lifecycle results into dispatch-gate side effects, trace events,
and delivery retries, but engine must not own the command record/status store.

### Routing

Package: `com.xa.mass.worker.runtime.routing`

Owned contract:

- `WorkerCandidateBucketPolicies`

`WorkerCandidateBucketPolicies` is the platform approved-attribute candidate-bucket policy.
The registry-level candidate-bucket SPI remains in `mass-runtime-api`.

## Online And Heartbeat Semantics

Worker registration may create or refresh a registry slot so the runtime can
route current work, but declaration persistence is not the source of active
online state. Online state comes from transport reachability, heartbeat
freshness in current registry metadata, dispatch gates, and admission evidence.
Declaration-store writes must keep projecting to `WorkerDeclarationRecord`
before persistence so heartbeat or online/offline churn never becomes durable
worker declaration truth.

## Registry SPI Below This Boundary

These low-level types stay in
`platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker`:

- `WorkerRegistry`
- `WorkerSlot`
- `WorkerMeta`
- `ReserveResult`
- `ReserveStatus`
- `CleanupSummary`
- `DispatchAvailabilitySource`
- `EventKey`
- `WorkerCandidateSamplingPolicy`
- `WorkerCandidateSamplingContext`
- `RandomWorkerCandidateSamplingPolicy`
- `WorkerCandidateBucketPolicy`
- `DefaultWorkerCandidateBucketPolicy`

`EventKey` is a project-scoped worker capability key for registry binding. It
is not a globally unique business event identity.

Public `eventCode` is handler/capability identity. It validates that a selected
WorkerGroup can execute the requested handler and tells the worker which local
handler to run. It is not a worker selector and must not cause engine matching
to scan all workers from item payload.

WorkerGroup owns project/event capability truth. Worker registration and
runtime state provide execution identity, group/node membership, reachability,
load, admission, draining, lease, and other scheduling evidence for selection
inside the selected group.

Engine matching code should not import registry primitives except where the
module assembly explicitly wires a registry implementation into
`WorkerManager`.

## Implementation-Only Owners

The root package contains implementation owners such as `WorkerManager`,
`WorkerResourceOwner`, `WorkerAdmissionOwner`, `WorkerCandidateSourceOwner`,
`WorkerCandidateIndex`, `WorkerRegistrySnapshot`, `WorkerReportOwner`, and
`TaskCandidateWarmPool`.

These are not contract families. Cross-module callers should prefer the
contract packages above.

## Verification

Runtime outcome proof:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,TaskCandidateWarmPoolTest" test

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,WorkerStateReportSchedulingIntegrationTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest,EngineSchedulingCoreSuite" test
```

Boundary residue sanity:

```powershell
rg -n "WorkerRegistry|WorkerSlot|WorkerMeta|ReserveResult|ReserveStatus" `
  xa-mass-engine/src/main/java --glob '!**/target/**'
```

The completed WRX/WRA convergence records are historical archive entries. This
contract is the current worker-runtime boundary source.
