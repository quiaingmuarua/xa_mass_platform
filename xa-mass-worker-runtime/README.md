# xa-mass-worker-runtime

Status: current higher-level worker runtime owner module.

This module owns worker-plane lifecycle, resource convergence, runtime worker
selection, candidate source, admission, reporting, and scheduling evidence
above the low-level `mass-runtime-api` registry SPI.

For the full boundary contract, see [CONTRACTS.md](CONTRACTS.md).

## Role

Worker runtime owns worker truth that is not task truth:

- WorkerGroup declaration state.
- Worker declaration row to runtime slot projection.
- Worker capability report application.
- Worker state report bounded projection.
- Worker reachability, load, and group capability evidence.
- Worker admission, occupancy, dispatch gates, and exclusive leases.
- Runtime worker selection: worker-side predicates, ordering, reservation,
  selected handles, claim authorization, and selected-worker accounting.
- Worker command lifecycle truth: command records, status transitions,
  worker-pulled claims, delivery attempt state, and command lifecycle value
  contracts.
- Stage-1 worker candidate source, source guard, and warm/cold merge.
- Task-local warm candidate hint storage.
- Platform-approved worker candidate bucket policies.
- Legacy adapter-node and node/group relation read models where still present in
  worker registration surfaces. These are topology/control-plane evidence only;
  they do not own event capability, final-hop delivery, or worker selection.

Engine still owns task lifecycle, task-side scheduling policy intent,
allocation budgets, dispatch binding, result convergence, and terminal policy.
Worker-runtime owns worker-fact predicate/ranking mechanics only behind the
minimal `WorkerSelectionRuntime` contract; it must not reinterpret task payload
or become a second task scheduling policy owner.

Scheduling admission is WorkerGroup-scoped. Engine assignment calls
`WorkerSelectionRuntime` with task-side worker-universe intent and consumes
`SelectedWorkerHandle` / `SelectedWorkerEvidence` for confirm, release, claim,
and final accounting. `WorkerAdmissionTarget` remains below that selection
boundary; engine mainline must not call worker-id-only admission mutations when
group evidence is already known or recoverable from runtime lifecycle records.

Stage-1 scheduling candidate acquisition passes one scheduling clock into the
registry acquisition and source guard. Redis may use that clock against an
adapter-internal deadline projection, but WorkerRuntime contracts still expose
only bounded candidate acquisition plus canonical source-guard validation, not
Redis key shapes.

Candidate bucket policy is source-index policy, not lifecycle truth. The
runtime-api `WorkerCandidateBucketPolicy` owns bucket key calculation and its
declared max fan-out cost; memory and Redis registries execute that policy and
must not hardcode worker attribute dimensions. When no policy-specific bucket is
available, scheduling falls back to the `default` source bucket plus bounded
acquisition, source guard, and worker-runtime selection filtering.

## Package Map

```text
com.xa.mass.worker.runtime.resource   resource declarations and lookup
com.xa.mass.worker.runtime.selection  selected-worker contract for engine
com.xa.mass.worker.runtime.candidate  Stage-1 candidate contracts
com.xa.mass.worker.runtime.evidence   read-only scheduling evidence
com.xa.mass.worker.runtime.admission  reserve/release, wakeup, warm hints
com.xa.mass.worker.runtime.command    worker command lifecycle truth
com.xa.mass.worker.runtime.report     capability and state report projection
com.xa.mass.worker.runtime.control    worker dispatch gate control
com.xa.mass.worker.runtime.routing    platform candidate bucket policy
com.xa.mass.worker.runtime            implementation owners and assembly
```

## Worker Shape Split

- `WorkerDeclarationRecord` and `WorkerDeclarationStore` live in this module as
  the worker-runtime owned declaration port: execution identity, WorkerGroup
  membership, adapter hints, static attributes, max concurrency, and timestamps.
- `WorkerRuntimeStateRecord` is current runtime evidence: heartbeat freshness,
  reachability, dispatch gate, reservation/load, and lease observations.
- `WorkerResourceRecord` is the current composite read model. It may be used
  for SDK/server/operator resource views, but it must not become declaration
  persistence truth while it still carries status, last heartbeat, and
  compatibility supported project/event hints.

WorkerGroup owns capability truth. Worker-level supported project/event fields
are compatibility read hints only, and a worker cannot self-declare event
capability outside the WorkerGroup event binding.

Worker runtime does not own transport delivery identity:

- `routeKey` is transport connection/domain metadata and remains opaque to
  transport; worker-runtime may expose worker group/node facts used by assembly
  to mint or resolve route keys, but it does not make routeKey a worker
  identity.
- `selectedWorkerId` is produced by engine runtime worker selection and carried
  into transport as the already selected execution identity.
- `deliveryQueueKey` is transport queue partitioning and must not be read as
  worker runtime reachability, admission, or capacity truth.
- transport endpoint leases are connection evidence. Worker runtime may
  consume reachability evidence for scheduling views, but endpoint lease
  refresh/release must not become worker capability, state-report, command, or
  lifecycle truth.
- raw transport identifiers such as `adapterId`, `routeKey`, `connectionId`,
  endpoint lease ids, session handles, and delivery queue keys are not worker
  selection inputs. If they affect scheduling, they must first be projected into
  bounded worker-runtime evidence.

## Worker Runtime State Dimensions

Worker runtime state is split into independent dimensions:

- reachability: transport/heartbeat observation such as `ONLINE`, `STALE`,
  `OFFLINE`, or `UNKNOWN`
- readiness: dispatch-in-principle state such as `READY`, `DRAINING`, or
  `MAINTENANCE`
- occupancy: capacity/reservation/active-lease/lock observation such as `FREE`,
  `RESERVED`, `OCCUPIED`, or `CAPACITY_FULL`

`UNKNOWN` reachability is an observation gap and is not reachable for runtime
scheduling. Legacy `statusName` / worker `status` fields are display
compatibility only; they must not drive scheduling.

Occupancy is diagnostic. `OCCUPIED` means active work exists; it does not mean
the worker is unschedulable when declared capacity remains. Reservation and
capacity truth stay in the worker registry/admission path, and `CAPACITY_FULL`
or exclusive lock evidence is what blocks new work.

Current readiness evidence is intentionally narrow. Worker-runtime state records
can derive `DRAINING` from removing evidence. Engine scheduling currently treats
worker state report `DRAINING` as upstream dispatch-gate unavailability; the
scheduling view does not need a distinct DRAINING label to reject the worker.
Dispatch-enabled workers are `READY`, and dispatch-disabled workers are
`MAINTENANCE` in the current scheduling diagnostic view. Target states such as
`INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
`HEALTH_UNAVAILABLE` remain target vocabulary until a worker state/report owner
maps them to dispatch-gate behavior and runtime outcome proof.

`WorkerStateReport` remains an open worker-originated diagnostic report string.
The default dispatch-gate policy is an allowlist over the latest state
projection, not a parser for every report value:

| State report value | Default scheduling effect |
| --- | --- |
| `DRAINING` | disables dispatch through the `WORKER_STATE` source |
| `AVAILABLE` | clears only the `WORKER_STATE` disable source |
| `DEGRADED` | diagnostic-only projection |
| `OFFLINE` | diagnostic-only projection; transport reachability owns offline scheduling evidence |
| `READY` | diagnostic-only projection |

`AVAILABLE` does not clear `WORKER_COMMAND`; command-drain state remains a
separate dispatch-gate source.

Current Redis worker occupancy is still stored through the canonical
`WorkerSlot` aggregate. `worker:meta:{workerId}`,
`worker:occupancy:{workerId}`, and `available:{shard}` split keys are not
implemented and must not be added as parallel writable truth.

WorkerGroup capability boundary:

- WorkerGroup declares project/event capability through event bindings and
  group-level defaults such as attributes and capacity/concurrency hints.
- Worker registration declares execution identity, WorkerGroup membership,
  bounded topology evidence, transport hints, static worker attributes, and
  runtime evidence.
- Worker rows must not become the source of project/event capability truth, and
  adapters cannot expand worker capability through session or endpoint evidence.
- `eventCode` is handler/capability identity for work items. It validates
  against WorkerGroup event bindings and tells the worker which local handler
  to run; it is not a worker selector.
- Runtime worker selection happens inside an already selected WorkerGroup from
  reachability, load, admission, draining, lease, and explicit scheduling
  evidence. It must not reinterpret item payload as matching policy.

## Dependency Rules

Allowed:

- `xa-mass-engine -> xa-mass-worker-runtime -> mass-runtime-api`
- `transport/* -> xa-mass-worker-runtime` for lookup/evidence contracts
- `sdk/xa-mass-embedded-sdk/server -> xa-mass-worker-runtime` for worker shell assembly

Forbidden:

- This module must not depend on `xa-mass-engine` or transport adapter
  implementations.
- `mass-runtime-memory` and `mass-runtime-redis` must not depend on this module.
- `mass-storage-api` must not own worker declaration contracts; storage modules
  may implement this module's declaration port as adapters.
- Engine match strategy must not consume `WorkerRegistry`, `WorkerSlot`,
  `WorkerMeta`, `ReserveResult`, or `ReserveStatus` as strategy contracts.

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
