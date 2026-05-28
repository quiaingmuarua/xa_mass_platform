# Worker Runtime Contracts

Status: current module contract after WRX and WRA.

`xa-mass-worker-runtime` owns worker-plane lifecycle and evidence above the
low-level registry SPI. Engine owns scheduling decisions. Transport owns
protocol/session mechanics. The boundary is:

```text
xa-mass-engine       -> xa-mass-worker-runtime -> mass-runtime-api
transport/*         -> xa-mass-worker-runtime -> mass-runtime-api
xa-mass-sdk/server  -> xa-mass-worker-runtime -> mass-runtime-api

platform_infra/mass-runtime-memory -> mass-runtime-api
platform_infra/mass-runtime-redis  -> mass-runtime-api
```

Memory and Redis registry implementations must not depend on this module.

## Ownership

Worker runtime owns:

- WorkerGroup, AdapterNode, NodeGroupBinding, and event binding declarations.
- Worker row to registry slot projection.
- Worker capability report application and bounded state projection.
- Worker reachability, load, and group capability evidence exposed to engine.
- Worker admission, capacity permits, dispatch gates, and exclusive leases.
- Stage-1 worker candidate source, source guard, and warm/cold merge.
- Task-local warm candidate hint storage after engine observes useful Stage-2
  evidence.
- Platform-approved worker route bucket policy composition.

Worker runtime does not own:

- Task lifecycle, task runtime queue state, delayed availability, or item lease
  state.
- Dispatch binding, transport delivery, or result convergence.
- Rule evaluation, worker ranking, allocation budget, or terminal policy.
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
- `WorkerDeclarationRecord`
- `WorkerRuntimeStateRecord`
- `WorkerGroupRecord`
- `AdapterNodeRecord`
- `NodeGroupBindingRecord`
- `EventBinding`

Role split:

- `WorkerDeclarationRecord` is the target declaration-store row shape. It
  contains stable worker identity, group/node membership, adapter hints,
  static attributes, max concurrency, and timestamps. It does not contain
  heartbeat, online/offline state, dispatch gates, reservations, leases, or
  worker-level supported project/event capability hints.
- `WorkerRuntimeStateRecord` is a current runtime-state view assembled from
  registry, reachability, heartbeat freshness, dispatch gate, and admission
  evidence. It is not persisted as declaration truth.
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
- `WorkerGroupCapabilityView`
- `WorkerLoadSnapshot`

Allowed callers:

- Engine matching and diagnostics.
- SDK/server assembly when exposing worker state.
- Worker-runtime implementation.

Evidence is read-only scheduling input. It must not mutate worker lifecycle
truth.

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

### Control

Package: `com.xa.mass.worker.runtime.control`

Owned contract:

- `WorkerDispatchGateRuntime`

Allowed callers:

- Engine control paths.
- SDK/server worker control paths.
- Worker-runtime implementation.

### Routing

Package: `com.xa.mass.worker.runtime.routing`

Owned contract:

- `WorkerRouteBucketPolicies`

`WorkerRouteBucketPolicies` is the platform approved-attribute route policy.
The registry-level route-bucket SPI remains in `mass-runtime-api`.

## Online And Heartbeat Semantics

Worker registration may create or refresh a registry slot so the runtime can
route current work, but declaration persistence is not the source of active
online state. Online state comes from transport reachability, heartbeat
freshness in current registry metadata, dispatch gates, and admission evidence.
TWH-3B must remove declaration-store writes that persist heartbeat or
online/offline churn as durable worker truth.

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
- `WorkerRouteBucketPolicy`
- `DefaultWorkerRouteBucketPolicy`

`EventKey` is a project-scoped worker capability key for registry binding. It
is not a globally unique business event identity.

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

Boundary guard:

```powershell
mvn -pl xa-mass-engine -am '-Dtest=EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Worker-runtime focused regression:

```powershell
mvn -pl xa-mass-worker-runtime,xa-mass-engine -am '-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,TaskCandidateWarmPoolTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Related direction docs:

- `xa-mass-engine/doc/roadmap/WORKER_RUNTIME_MODULE_EXTRACTION_ROADMAP.md`
- `xa-mass-engine/doc/roadmap/WORKER_RUNTIME_MODULE_EXTRACTION_INVENTORY.md`
- `xa-mass-engine/doc/roadmap/WORKER_RUNTIME_API_SLIMMING_ROADMAP.md`
- `xa-mass-engine/doc/roadmap/WORKER_RUNTIME_API_SLIMMING_INVENTORY.md`
