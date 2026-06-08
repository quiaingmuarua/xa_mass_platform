# xa-mass-worker-runtime

Status: current higher-level worker runtime owner module.

This module owns worker-plane lifecycle, resource convergence, candidate
source, admission, reporting, and scheduling evidence above the low-level
`mass-runtime-api` registry SPI.

For the full boundary contract, see [CONTRACTS.md](CONTRACTS.md).

## Role

Worker runtime owns worker truth that is not task truth:

- WorkerGroup declaration state.
- AdapterNode and NodeGroupBinding relationships.
- Worker declaration row to runtime slot projection.
- Worker capability report application.
- Worker state report bounded projection.
- Worker reachability, load, and group capability evidence.
- Worker admission, occupancy, dispatch gates, and exclusive leases.
- Worker command lifecycle truth: command records, status transitions,
  worker-pulled claims, delivery attempt state, and command lifecycle value
  contracts.
- Stage-1 worker candidate source, source guard, and warm/cold merge.
- Task-local warm candidate hint storage.
- Platform-approved worker candidate bucket policies.

Engine still owns task scheduling decisions. Worker runtime provides worker
lifecycle state, admission operations, and evidence; it does not evaluate match
rules, rank candidates, choose allocation budgets, bind dispatches, converge
results, or apply dispatch-control side effects from command lifecycle changes.

## Package Map

```text
com.xa.mass.worker.runtime.resource   resource declarations and lookup
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
  the worker-runtime owned declaration port: identity, WorkerGroup/node
  membership, adapter hints, static attributes, max concurrency, and
  timestamps.
- `WorkerRuntimeStateRecord` is current runtime evidence: heartbeat freshness,
  reachability, dispatch gate, reservation/load, and lease observations.
- `WorkerResourceRecord` is the current composite read model. It may be used
  for SDK/server/operator resource views, but it must not become declaration
  persistence truth while it still carries status, last heartbeat, and
  compatibility supported project/event hints.

WorkerGroup owns capability truth. Worker-level supported project/event fields
are compatibility read hints only.

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

Current readiness evidence is intentionally narrow: dispatch-enabled workers are
`READY`, removing/draining workers are `DRAINING`, and dispatch-disabled workers
are `MAINTENANCE`. Target states such as `INIT_REQUIRED`, `VERSION_MISMATCH`,
`ACCOUNT_UNAVAILABLE`, and `HEALTH_UNAVAILABLE` remain target vocabulary until a
worker state/report owner maps them to dispatch-gate behavior and runtime
outcome proof.

Current Redis worker occupancy is still stored through the canonical
`WorkerSlot` aggregate. `worker:meta:{workerId}`,
`worker:occupancy:{workerId}`, and `available:{shard}` split keys are not
implemented and must not be added as parallel writable truth.

WorkerGroup capability boundary:

- WorkerGroup declares project/event capability through event bindings and
  group-level defaults such as attributes and capacity/concurrency hints.
- Worker registration declares execution identity, group/node membership,
  transport hints, static worker attributes, and runtime evidence.
- Worker rows must not become the source of project/event capability truth.
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
mvn -pl xa-mass-worker-runtime,xa-mass-engine -am '-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,TaskCandidateWarmPoolTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Boundary residue sanity:

```powershell
mvn -pl xa-mass-engine -am '-Dtest=EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
