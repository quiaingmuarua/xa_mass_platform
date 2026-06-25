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
- Worker-runtime dispatch eligibility evidence used by worker-runtime selection
  and explicit diagnostics: reachability observations, heartbeat freshness,
  load, admission, capacity permits, dispatch gates, and exclusive leases.
- Runtime worker selection: worker-fact predicates, ordering, bounded
  score-band acquire/claim, selected handles, selected claim authorization, and
  selected-worker evidence.
- Worker command lifecycle truth: request records, status transitions,
  worker-pulled claims, delivery-attempt bookkeeping, expiry, and command
  lifecycle value contracts.
- Stage-1 bounded worker candidate source, source guard, and platform-approved
  worker candidate bucket policy composition where still used as
  migration/source projection support.

Worker runtime does not own:

- Task lifecycle, task runtime queue state, delayed availability, or item lease
  state.
- Dispatch binding, transport delivery, or result convergence.
- Task-side policy/rule intent, allocation budget, or terminal policy.
  Worker-runtime may own worker-fact predicate and ranking mechanics only
  behind the minimal selection contract.
- Engine dispatch-control side effects produced from worker command lifecycle
  results.
- Transport protocol sessions, adapter-specific connection state, or SDK wire
  semantics.

## Contract Families

### Resource

Package: `com.xa.mass.worker.runtime.resource`

Owned contracts:

- `WorkerResourceQueryRuntime`
- `WorkerResourceDeclarationRuntime`
- `WorkerHeartbeatRuntime`
- `WorkerNodeBindingRuntime`
- `WorkerDeclarationRecord`
- `WorkerResourceRecord`
- `WorkerGroupRecord`
- `AdapterNodeRecord`
- `NodeGroupBindingRecord`
- `EventBinding`

Role split:

- `WorkerDeclarationRecord` and `WorkerDeclarationStore` live in this module
  because worker declaration is worker-runtime lifecycle/control-plane
  ownership. The record contains stable worker identity, WorkerGroup
  membership, transport hint, static attributes, and max concurrency. It does
  not contain adapter-node topology ids, heartbeat, online/offline state,
  dispatch gates, reservations, leases, raw timestamps, or worker-level
  supported project/event capability hints.
- Broad runtime-state/readiness records are not current worker-runtime
  contracts. Registry heartbeat, dispatch gate, admission, and load evidence
  remain on their narrow owners instead of being copied into a composite DTO.
- `WorkerResourceRecord` is a minimal lookup read model for worker-owned
  identity/declaration facts. It does not carry runtime status, heartbeat,
  adapter topology ids, raw timestamps, or compatibility capability hints.
- `WorkerHeartbeatRuntime` is the narrow runtime-evidence port used to refresh
  registry heartbeat evidence from explicit worker/runtime report paths. It is
  separate from declaration mutation, and transport session connected/heartbeat
  presence is not a caller for registry heartbeat refresh or dispatch wakeup.
- `WorkerNodeBindingRuntime` is topology/admin. It may manage adapter node and
  node-group binding metadata, but it does not own scheduling selection or
  worker dispatch eligibility.

Allowed callers:

- Engine control/resource paths.
- SDK/server worker shell assembly.
- Transport lookup paths through `WorkerResourceQueryRuntime` only.
- Worker-runtime implementation.

Transport must not use declaration or binding mutation contracts.

### Selection

Package: `com.xa.mass.worker.runtime.selection`

Owned contracts:

- `WorkerSelectionRuntime`
- `WorkerSelectionIntent`
- `WorkerSelectionRequest`
- `WorkerSelectionResult`
- `SelectedWorkerHandle`
- `SelectedWorkerEvidence`

Allowed callers:

- Engine assignment orchestration.
- SDK/server assembly.
- Worker-runtime implementation.

Selection is the default engine-facing runtime worker-selection boundary.
Engine provides task-side worker-universe intent and requested counts; it does
not provide worker-runtime source keys, candidate rows, scheduling views,
worker attributes, load snapshots, dispatch-gate evidence, or transport
topology ids.

Worker-runtime selection composes candidate source, dispatch eligibility,
load, WorkerGroup capability, routing/attribute
matching, admission, and exclusive lease evidence internally. These inputs feed
one worker-runtime dispatch eligibility decision; they are not parallel
readiness/reachability/occupancy scheduling owners. It returns selected handles
only after worker-runtime has selected and reserved workers. Engine may read
`workerId`, `workerGroupId`, selection token, and selected accounting/claim
operations from the handle. Claim authorization remains a worker-runtime
package-private bridge used by the selected handle to build the current
task-runtime claim target; engine must not inspect capability lists or
authorization internals. Any additional worker fact belongs in an explicit
server/SDK diagnostic view, not in the engine hot path.

`SelectedWorkerEvidence` is the recovery/release shape for persisted dispatch
bindings. It carries selected identity and selection scope only.

### Candidate

Package: `com.xa.mass.worker.runtime.candidate`

Owned contracts:

- `WorkerCandidateRuntime`
- `WorkerCandidateRow`
- `WorkerTaskSelector`

Allowed callers:

- Worker-runtime implementation.
- Test support that proves candidate-source behavior.

Candidate acquisition is an internal worker-runtime selection mechanism.
Engine scheduling must use `WorkerSelectionRuntime`, not
`WorkerCandidateRuntime`. Candidate acquisition must start from an explicit
WorkerGroup selector and must not reintroduce all-worker scans.
`WorkerCandidateRuntime#findWorkerCandidates(...)` returns row-only source
candidates; warm/cold diagnostic wrappers and task-local warm mutation ports
are not current contracts.

Production scheduling candidate acquisition carries the scheduling clock into
`WorkerRegistry#acquireCandidates(...)` so registry implementations may use
deadline-aware slot lifecycle projections before the source guard. The source
guard and reserve mutation still revalidate canonical slot truth; Redis
deadline indexes remain adapter-internal derived indexes and are not exposed as
worker-runtime or engine contracts.

Candidate bucket keys are source-index hints selected by `WorkerTaskSelector`
and validated against the runtime-api `WorkerCandidateBucketPolicy`. They are
not lifecycle eligibility truth. The policy owns optional attribute dimensions
and declares max bucket fan-out cost; registry implementations execute the
policy and fall back to the `default` bucket when it returns no keys.

### Evidence

Package: `com.xa.mass.worker.runtime.evidence`

Owned contracts:

- `WorkerSchedulingViewRuntime`
- `SelectedWorkerDeliveryTargetEvidence`
- `WorkerReachabilityState`
- `WorkerOccupancyState`
- `WorkerGroupCapabilityView`
- `WorkerLoadSnapshot`

Allowed callers:

- Worker-runtime selection implementation for `WorkerSchedulingViewRuntime`.
- SDK/server assembly when exposing worker state diagnostics through
- worker-runtime reachability point reads.
- SDK/starter delivery integration when resolving selected-worker delivery
  target evidence after assignment.
- Worker-runtime implementation.

Evidence is read-only worker-runtime input and diagnostic output. Engine
scheduling must not read it directly as a worker-selection input; selected
handles are the engine hot-path contract. Evidence must not mutate worker
lifecycle truth.

`WorkerSchedulingViewRuntime` is the selection-facing read surface. It exposes
group capability, dispatch gate, lock, and load facts, but it must not expose
`getWorkerReachability`. Reachability stays as a diagnostic point read from the
explicit diagnostic or freshness provider so diagnostic/freshness observations
cannot quietly return as a selection gate.

Selected-worker delivery target resolution is the post-selection delivery
evidence contract. It resolves an already selected worker to an opaque adapter
mailbox target. SDK/starter delivery integration may consume it after
assignment; engine selection must not read adapter mailboxes, endpoint leases,
route keys, connection ids, session handles, transport node ids, or adapter ids
as worker selection truth. The resolver is point lookup only and must not grow
list, count, stats, snapshot, or inspection APIs.

Reachability, readiness, and occupancy are evidence/diagnostic vocabularies
inside worker-runtime, not three independent scheduling truth owners. `UNKNOWN`
reachability is an observation gap; worker-runtime must not treat it as
reachable for scheduling. Readiness and occupancy labels are derived from
current worker-runtime facts unless a later owner decision makes a stored field
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
- `WorkerAdmissionTarget`
- `WorkerAdmissionResult`
- `WorkerAdmissionStatus`
- `WorkerAvailabilityWakeupRuntime`

Allowed callers:

- Worker-runtime selection implementation.
- Engine lifecycle wakeup wiring.
- SDK/server assembly.
- Worker-runtime implementation.

Admission translates registry reserve/release primitives into worker-plane
results. Engine strategy must not consume `ReserveResult`, `ReserveStatus`, or
`WorkerAdmissionRuntime` directly for scheduling. The engine-facing mutation
surface for selection, confirm, release, claim, and final accounting is
`WorkerSelectionRuntime` with `SelectedWorkerHandle` or
`SelectedWorkerEvidence`.

`WorkerAdmissionTarget` carries `workerGroupId + workerId + taskId + permits`
below the selection boundary. Worker-id reverse lookup belongs below this
contract as low-level registry support for diagnostics, commands, or genuinely
group-less paths.

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
| `AVAILABLE` | dispatch-gate input; requests worker-runtime recovery for `WORKER_STATE` |
| `DEGRADED` | diagnostic-only projection |
| `OFFLINE` | diagnostic-only projection; worker-runtime-owned reachability evidence decides dispatch eligibility |
| `READY` | diagnostic-only projection |
| `INIT_REQUIRED` | target-only readiness vocabulary |
| `VERSION_MISMATCH` | target-only readiness vocabulary |
| `ACCOUNT_UNAVAILABLE` | target-only readiness vocabulary |
| `HEALTH_UNAVAILABLE` | target-only readiness vocabulary |

`AVAILABLE` must not clear `WORKER_COMMAND`; worker command drain remains a
separate dispatch-gate source. Positive state evidence enters
`WorkerDispatchRecoveryRuntime` and must pass worker-runtime validation before
the matching source can be cleared.

### Control

Package: `com.xa.mass.worker.runtime.control`

Owned contract:

- `WorkerDispatchBlockRuntime`
- `WorkerDispatchEligibilityRuntime`
- `WorkerDispatchGateRuntime`
- `WorkerDispatchRecoveryRuntime`
- `WorkerDispatchRecoveryMode`

Allowed callers:

- `WorkerDispatchBlockRuntime`: SDK/starter integration bridges that publish
  allowlisted external negative evidence. The current transport slice allows
  only confirmed current-session disconnect.
- Engine control paths.
- SDK/server worker control paths.
- Worker-runtime implementation.

`WorkerDispatchBlockRuntime` is negative-only. It records source-scoped block
metadata and cannot clear a dispatch block. `WorkerDispatchEligibilityRuntime`
owns worker state/command evidence interpretation into dispatch eligibility;
engine control submits evidence to this runtime instead of receiving the
clear-capable gate. `DefaultWorkerDispatchAvailabilityPolicy` is the default
worker-runtime implementation of that evidence translation. Positive recovery
from that policy uses `WorkerDispatchRecoveryRuntime`; the first synchronous
implementation validates worker meta, `WorkerDispatchRecoveryMode`, slot
presence, removing state, and the active block source before clearing only that
source.
`WorkerDispatchGateRuntime` is clear-capable and remains worker-runtime/control
internal; transport and concrete adapters must not import or call it.

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
may submit lifecycle results to worker-runtime dispatch eligibility, emit trace
events, and coordinate delivery retries, but engine must not own command
record/status storage or dispatch-gate mutation policy.

`WorkerAvailabilityWakeupRuntime` is assembly wiring for worker-runtime
availability changes that can make waiting tasks worth rechecking. Admission
release paths such as reservation release, final work accounting, and
exclusive-lock release may trigger it after capacity state changes. The wakeup
does not clear dispatch blocks and is not itself scheduling truth.

### Routing

Package: `com.xa.mass.worker.runtime.routing`

Owned contract:

- `WorkerCandidateBucketPolicies`

`WorkerCandidateBucketPolicies` is the current platform approved-attribute
candidate-bucket policy. Its standard route attributes are a worker-runtime
default, not Redis keyspace truth. The registry-level `WorkerCandidateBucketPolicy`
SPI remains in `mass-runtime-api` and exposes bucket fan-out cost so attribute
indexing write amplification is visible.

## Online And Heartbeat Semantics

Worker registration may create or refresh a registry slot so the runtime can
route current work, but declaration persistence is not the source of active
online or dispatchable state. Worker-runtime dispatch eligibility is derived
inside worker-runtime from reachability observations, heartbeat freshness in
current registry metadata, dispatch gates, capacity/admission, and selection
confirmation. Declaration-store writes must keep projecting to
`WorkerDeclarationRecord` before persistence so heartbeat or online/offline
churn never becomes durable worker declaration truth.

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
handler to run. It is not a worker selector and must not cause engine code to
scan all workers from item payload.

WorkerGroup owns project/event capability truth. Worker registration and
runtime state provide execution identity, group/node membership, reachability
observations, load, admission, draining/gate state, lease, and other
worker-runtime evidence for selection inside the selected group.

Engine scheduling code should not import registry primitives or worker-runtime
candidate/evidence/admission subports. The default engine-facing contract is
`WorkerSelectionRuntime`; module assembly may still wire registry
implementations into `WorkerManager`.

## Score-Band Slot Runtime

`platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/slot`
contains the score-band slot state-machine contract:

- `WorkerScoreBandSlotRuntime`
- `WorkerScoreBandSlotMetadata`
- `WorkerScoreBandTransitionCommand`
- `WorkerScoreBandTransitionResult`
- `WorkerScoreBand`

Current implementation status:

- worker registration/update projects stable worker slot metadata into the
  score-band runtime when assembly provides one;
- `homeBucketId` is `workerGroupId` in this slice;
- heartbeat refresh does not write score-band state or request positive
  recovery;
- memory and Redis implementations store only score zset/map plus stable
  metadata;
- production `WorkerSelectionRuntime` now acquires bounded eligible slots from
  score-band runtime and writes `FUTURE_BAND` claim scores for selected
  workers;
- `WorkerSelectionOwner` no longer writes the old
  `WorkerAdmissionRuntime.reserveWorkerCapacity / confirm / claimed / final`
  accounting path;
- `WorkerAdmissionRuntime` still owns exclusive worker locks and remains as
  direct/runtime API residue outside the score-band selection hot path;
- `WorkerSelectionRuntime.activeSelectedWorkerCount(taskId)` has been removed.
  Task-scope active dispatch worker budgeting belongs to engine/task-runtime
  assignment truth via `TaskAssignmentRuntimePort.countActiveDispatchWorkers`.

Score-band claim close requires an observation that can be validated against
the current score-band claim. Runtime claim paths carry `scoreBandClaimScore`
through worker claim target, task lease, dispatch binding, result context, and
selected-worker evidence. Null-observation legacy `SelectedWorkerEvidence` is
not enough to shorten a `FUTURE_BAND` score or reopen eligibility.

## Implementation-Only Owners

The root package contains implementation owners such as `WorkerManager`,
`WorkerResourceOwner`, `WorkerAdmissionOwner`, `WorkerCandidateSourceOwner`,
`WorkerCandidateIndex`, `WorkerRegistrySnapshot`, and `WorkerReportOwner`.

These are not contract families. Cross-module callers should prefer the
contract packages above.

## Verification

Runtime outcome proof:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest" test

.\mvnw.cmd -pl platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am `
  "-Dtest=InMemoryWorkerScoreBandSlotRuntimeTest,RedisWorkerScoreBandSlotRuntimeTest" test

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
