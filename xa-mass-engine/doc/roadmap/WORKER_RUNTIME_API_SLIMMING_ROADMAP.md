# Worker Runtime API Slimming Roadmap

Status: proposed follow-up after
[`WORKER_RUNTIME_MODULE_EXTRACTION_ROADMAP.md`](./WORKER_RUNTIME_MODULE_EXTRACTION_ROADMAP.md).

WRX moved worker runtime ownership out of `xa-mass-engine`, but it left too
much worker-plane language in `platform_infra/mass-runtime-api`. That module is
now acting as both:

- the low-level runtime SPI consumed by memory/Redis implementations; and
- the high-level worker runtime API consumed by engine, SDK, server, and
  transport.

That shape is workable short term, but it is not a stable architecture. If
future work keeps adding worker concepts to `mass-runtime-api`, the module will
become the new shared bucket and worker-runtime ownership will become nominal.

The goal of this roadmap is to make `mass-runtime-api` thin again and make
`xa-mass-worker-runtime` carry the worker-plane contracts it owns.

This is not a package-cleanup-only roadmap. The package move is only valid if
the engine/worker-runtime contract boundary is explicit first. Otherwise the
same coupling will reappear under a cleaner Maven module name.

## Target Shape

```text
xa-mass-engine
  -> xa-mass-worker-runtime      worker resource/candidate/admission/report APIs
  -> mass-runtime-api            task work/result APIs; worker primitives only by documented exception

transport
  -> xa-mass-worker-runtime      worker resource lookup and runtime evidence

xa-mass-sdk / xa-mass-server
  -> xa-mass-worker-runtime      worker platform API surface
  -> mass-runtime-api            task runtime selection and documented low-level assembly only

xa-mass-worker-runtime
  -> mass-runtime-api            low-level registry primitives
  -> mass-storage-api            control-plane worker declarations
  -> xa-mass-base                stable base model conversion where still needed

mass-runtime-memory / mass-runtime-redis
  -> mass-runtime-api            implement WorkerRegistry only
```

`mass-runtime-api` remains the neutral low-level SPI. It must not carry
worker-plane resource, report, candidate, or control contracts.

## Contract Boundary

The core boundary is:

```text
engine decides scheduling policy
worker runtime enforces worker truth
transport owns protocol/session evidence
```

`xa-mass-engine` owns:

- task lifecycle, intake, assignment trigger, retry, and refill timing
- match strategy, rule evaluation, rank policy, and allocation budget
- task-runtime claim and dispatch binding
- result convergence and terminal policy
- assignment diagnostics that explain the whole path

`xa-mass-worker-runtime` owns:

- Worker / WorkerGroup / AdapterNode / NodeGroupBinding resource declarations
- derived worker runtime projection into slots, indexes, and dispatch gates
- bounded candidate source and source-guard evidence
- worker scheduling evidence reads
- structured admission: reserve, confirm, release, final, occupancy, leases
- worker capability/state report projection
- warm candidate hint storage and revalidation

`transport` owns:

- polling / websocket / socket session and delivery mechanics
- connection presence and adapter route evidence
- normalized worker registration/report/result input

Transport presence may feed worker runtime evidence. It must not become
scheduling truth directly.

### Contract Families

The worker-runtime public contract should be grouped by caller intent, not by
where the implementation class happens to live.

Engine match path may consume only:

```text
task-side routing selector
  WorkerTaskSelector
  approved route-attribute convention

candidate source
  WorkerCandidateRuntime
  WorkerCandidateBatch
  WorkerCandidateRow

scheduling evidence
  WorkerSchedulingViewRuntime
  WorkerGroupCapabilityView
  WorkerLoadSnapshot
  WorkerReachabilityView / WorkerReachabilityState

admission
  WorkerAdmissionRuntime
  WorkerAdmissionResult / WorkerAdmissionStatus if WRA-0.5 introduces them
  ReserveResult / ReserveStatus only if explicitly accepted as leaked registry primitives
```

Engine match path must not consume resource mutation or report-ingress
contracts. It also should not consume `WorkerMeta`, `WorkerSlot`, or
`WorkerRegistry` directly; those are registry primitives owned below
worker-runtime.

Engine control / SDK / server resource paths may consume:

```text
resource declaration
  WorkerResourceRuntime
  WorkerResourceRecord
  WorkerGroupRecord
  AdapterNodeRecord
  NodeGroupBindingRecord
  EventBinding
  ProjectEventKey / WorkerEventScope if EventKey moves

report and gate projection
  WorkerReportRuntime
  WorkerCapabilityReport*
  WorkerStateReport
  WorkerStateProjection*
  WorkerDispatchGateRuntime
```

Transport may consume worker resource lookup and report input contracts, but
must not call `WorkerRegistry` or admission APIs directly.

`WorkerResourceRuntime` is intentionally suspicious in this roadmap. It is a
large surface that currently mixes resource declaration, lookup, node/group
binding, and some runtime-adjacent relationship reads. WRA-0.5 must decide
whether it remains one contract for now or splits into smaller contracts such
as:

```text
WorkerResourceDeclarationRuntime
WorkerResourceQueryRuntime
WorkerNodeBindingRuntime
```

Do not split it merely for aesthetics. Split only if it reduces caller access
to capabilities they should not have.

## Current Problem

`mass-runtime-api` currently has two worker layers in one package.

The lists below are preliminary. WRA-0 must produce the authoritative
classification table from the current file list and import graph before any
move/delete slice starts.

Keep in `mass-runtime-api`:

```text
WorkerRegistry
WorkerSlot
WorkerMeta
ReserveResult
ReserveStatus
CleanupSummary
WorkerCandidateSamplingPolicy
WorkerCandidateSamplingContext
RandomWorkerCandidateSamplingPolicy
WorkerRouteBucketPolicy
DispatchAvailabilitySource
```

Move to `xa-mass-worker-runtime`:

```text
AdapterNodeRecord
EventBinding
NodeGroupBindingRecord
WorkerAdmissionRuntime
WorkerAvailabilityWakeupRuntime
WorkerCandidateBatch
WorkerCandidateRow
WorkerCandidateRuntime
WorkerCapabilityReport
WorkerCapabilityReportResult
WorkerCapabilityReportStatus
WorkerDispatchGateRuntime
WorkerGroupCapabilityView
WorkerGroupRecord
WorkerLoadSnapshot
WorkerReachabilityState
WorkerReachabilityView
WorkerReportRuntime
WorkerResourceRecord
WorkerResourceRuntime
WorkerSchedulingViewRuntime
WorkerStateProjection
WorkerStateProjectionResult
WorkerStateProjectionRuntime
WorkerStateProjectionStatus
WorkerStateReport
WorkerTaskSelector
WorkerWarmHintRuntime
```

Decide before implementation:

```text
EventKey
WorkerAdmissionContext
WorkerAdmissionPolicy
WorkerCleanupPolicy
```

`EventKey` is currently a `(projectCode, eventCode)` key used for worker
capability scope, not a globally unique event identity. WRA-0 must decide
whether to keep it as a low-level registry ceiling primitive, or rename/move it
with `EventBinding` as a worker-plane scope type such as `ProjectEventKey` or
`WorkerEventScope`.

`WorkerAdmissionContext`, `WorkerAdmissionPolicy`, and `WorkerCleanupPolicy`
look like low-level registry extension points, but current code does not use
them in production. Either keep them only if a memory/Redis implementation uses
them, or delete/move them in the same slice. Do not leave unused policy seams in
`mass-runtime-api`.

Route bucket policy needs a split:

```text
mass-runtime-api
  WorkerRouteBucketPolicy            low-level registry SPI

xa-mass-worker-runtime
  WorkerRouteBucketPolicies          platform-approved attribute policy
  STANDARD_APPROVED_ROUTE_ATTRIBUTES worker-plane routing convention
```

Memory/Redis registry implementations may still accept a
`WorkerRouteBucketPolicy` instance, but the platform default policy should be
provided by worker-runtime assembly. Registry implementations must not own the
standard worker attribute list.

Current code caveat: `InMemoryWorkerRegistry` and `RedisWorkerRegistry` import
`WorkerRouteBucketPolicies` directly today for constructor defaults and null
fallbacks. WRA-2 must audit those call sites first. Directly moving the whole
class would either break the registries or force a forbidden dependency from
memory/Redis to `xa-mass-worker-runtime`.

Preferred WRA-2 direction:

```text
mass-runtime-api
  WorkerRouteBucketPolicy
  DefaultRouteBucketPolicy or equivalent registry-neutral default-only helper

xa-mass-worker-runtime
  WorkerRouteBucketPolicies
  ApprovedAttributeRouteBucketPolicy
  STANDARD_APPROVED_ROUTE_ATTRIBUTES
```

Registry constructors should receive a `WorkerRouteBucketPolicy`. Their default
constructor may use only the registry-neutral default policy. Platform assembly
must inject the approved-attribute policy when it wants current
WorkerGroup-first route behavior.

## Target Package Convention

Moved worker-plane types must not keep the old
`com.xa.mass.runtime.worker` package name in a different Maven module. Split
packages hide ownership and make imports misleading.

Implementation owners may stay in:

```text
com.xa.mass.worker.runtime
```

Moved public worker-plane contracts should use domain subpackages:

```text
com.xa.mass.worker.runtime.resource
  WorkerResourceRuntime
  WorkerResourceRecord
  WorkerGroupRecord
  AdapterNodeRecord
  NodeGroupBindingRecord
  EventBinding
  ProjectEventKey / WorkerEventScope if EventKey moves

com.xa.mass.worker.runtime.report
  WorkerReportRuntime
  WorkerCapabilityReport*
  WorkerStateReport
  WorkerStateProjection*

com.xa.mass.worker.runtime.candidate
  WorkerCandidateRuntime
  WorkerCandidateBatch
  WorkerCandidateRow
  WorkerTaskSelector

com.xa.mass.worker.runtime.evidence
  WorkerSchedulingViewRuntime
  WorkerGroupCapabilityView
  WorkerLoadSnapshot
  WorkerReachabilityState
  WorkerReachabilityView

com.xa.mass.worker.runtime.admission
  WorkerAdmissionRuntime
  WorkerAvailabilityWakeupRuntime
  WorkerWarmHintRuntime

com.xa.mass.worker.runtime.control
  WorkerDispatchGateRuntime

com.xa.mass.worker.runtime.routing
  WorkerRouteBucketPolicies
  ApprovedAttributeRouteBucketPolicy
  TaskRouteAttributePolicy if WRA-0.5 extracts task-side routing from engine
```

If implementation proves a subpackage split creates circular dependency or
excessive churn, stop and update WRA-0. Do not fall back to same-package
movement without recording the tradeoff.

## Non-Goals

1. Do not change matching behavior.
2. Do not change Redis or memory registry semantics.
3. Do not introduce compatibility aliases for old package names.
4. Do not split task work/result runtime APIs in this roadmap.
5. Do not create a new facade module that only re-exports moved types.
6. Do not move `WorkerRegistry`, `WorkerSlot`, or `WorkerMeta` out of
   `mass-runtime-api`.
7. Do not make `mass-runtime-memory` or `mass-runtime-redis` depend on
   `xa-mass-worker-runtime`.

## Slice WRA-0: Inventory And Classification

Goal: freeze the boundary before moving files.

Scope:

1. Add an inventory table listing every `com.xa.mass.runtime.worker` type.
2. Classify each type as:
   - registry primitive
   - registry extension point
   - worker resource/control contract
   - worker report contract
   - worker candidate/match evidence
   - worker scheduling/admission facade
   - unused residue
3. Record current production consumers for each type:
   - memory registry
   - Redis registry
   - worker-runtime owner
   - engine
   - SDK/server
   - transport
4. Decide disposition for unused extension points:
   - keep in `mass-runtime-api`
   - move to `xa-mass-worker-runtime`
   - delete
5. Mark each worker-plane contract by allowed caller family:
   - engine match path
   - engine control/resource path
   - SDK/server public shell
   - transport
   - worker-runtime implementation only

Acceptance:

1. No code behavior changes.
2. Every worker type in `mass-runtime-api` has one owner classification.
3. The keep/move/delete list is explicit.
4. Memory/Redis required types are proven from imports, not assumed.
5. Every public worker-runtime contract has an allowed-caller classification.

## Slice WRA-0.5: Align Worker Runtime Contracts

Goal: correct the contract shape before package movement.

Scope:

1. Define the worker-runtime contract families:
   - candidate source
   - scheduling evidence
   - admission
   - resource declaration/query
   - report/state projection
   - dispatch gate/control
   - warm hint
2. Decide whether current interfaces are correctly scoped:
   - `WorkerCandidateRuntime`
   - `WorkerSchedulingViewRuntime`
   - `WorkerAdmissionRuntime`
   - `WorkerResourceRuntime`
   - `WorkerReportRuntime`
   - `WorkerStateProjectionRuntime`
   - `WorkerDispatchGateRuntime`
   - `WorkerWarmHintRuntime`
   - engine-side `WorkerRoutingPolicy`
3. Decide whether `WorkerResourceRuntime` remains one surface or splits into
   smaller resource declaration/query/binding contracts.
4. Decide whether `WorkerSchedulingViewRuntime` is the right name, or whether
   a name such as `WorkerSchedulingEvidenceRuntime` better communicates that
   it returns evidence, not policy.
5. Decide whether `EventKey` is renamed/moved as `ProjectEventKey` or
   `WorkerEventScope`.
6. Decide whether `WorkerAdmissionRuntime` may continue exposing
   `ReserveResult` / `ReserveStatus`, or whether worker-runtime needs a
   worker-plane admission result that hides `WorkerSlot` from engine strategy.
7. Decide where the task-side route selector policy lives. Engine may own the
   choice of task route attributes, but worker-side `WorkerMeta` bucket
   computation must stay below the worker-runtime boundary.
8. Update architecture guards to reflect allowed caller families before broad
   import churn starts.

Acceptance:

1. Engine match path contract set is explicit and limited to candidate,
   scheduling evidence, admission, and task-side routing selector contracts.
2. Engine control/resource path contract set is explicit and separate from
   match path.
3. Transport allowed worker-runtime contracts are explicit.
4. No contract grants a caller broader mutation authority than it needs.
5. Engine match strategy does not directly depend on `WorkerRegistry`,
   `WorkerSlot`, or `WorkerMeta` after contract alignment unless WRA-0.5
   records a concrete temporary exception.
6. Package movement may proceed without changing contract semantics mid-move.

## Slice WRA-1a: Move Worker Data Records And Enums

Goal: move pure worker-plane value types before runtime interfaces.

Scope:

1. Move worker resource/control records:
   - `WorkerResourceRecord`
   - `WorkerGroupRecord`
   - `AdapterNodeRecord`
   - `NodeGroupBindingRecord`
   - `EventBinding`
   - `ProjectEventKey` / `WorkerEventScope` if WRA-0 moves/renames `EventKey`
2. Move worker report and projection records/enums:
   - `WorkerCapabilityReport*`
   - `WorkerStateReport`
   - `WorkerStateProjection`
   - `WorkerStateProjectionResult`
   - `WorkerStateProjectionStatus`
3. Move worker candidate records/enums:
   - `WorkerCandidateBatch`
   - `WorkerCandidateRow`
   - `WorkerTaskSelector`
4. Move worker scheduling evidence records/enums:
   - `WorkerGroupCapabilityView`
   - `WorkerLoadSnapshot`
   - `WorkerReachabilityState`
5. Update imports in worker-runtime tests first, then engine/SDK/server/transport.
6. Do not keep forwarding types in `mass-runtime-api`.
7. Follow the package convention and contract family decisions from WRA-0.5.

Acceptance:

1. Moved value types keep the same validation and behavior.
2. No runtime interface is moved before its value types compile from
   `xa-mass-worker-runtime`.
3. No compatibility aliases remain under old package names.

## Slice WRA-1b: Move Worker Runtime Interfaces

Goal: make `xa-mass-worker-runtime` the module that exposes worker-plane
contracts.

Scope:

1. Move resource/report/candidate interfaces:
   - `WorkerResourceRuntime`
   - `WorkerReportRuntime`
   - `WorkerCandidateRuntime`
   - `WorkerStateProjectionRuntime`
2. Move scheduling evidence interfaces:
   - `WorkerSchedulingViewRuntime`
   - `WorkerReachabilityView`
3. Move high-level admission/wakeup/gate contracts:
   - `WorkerAdmissionRuntime`
   - `WorkerAvailabilityWakeupRuntime`
   - `WorkerDispatchGateRuntime`
   - `WorkerWarmHintRuntime`
4. Update imports in engine, SDK, server, transport, and worker-runtime tests.
5. Do not keep forwarding types in `mass-runtime-api`.
6. Do not move an interface unchanged if WRA-0.5 found it grants excessive
   caller authority; split or rename it first in the same slice.

Acceptance:

1. Production source outside memory/Redis no longer imports moved worker-plane
   types from `com.xa.mass.runtime.worker`.
2. `xa-mass-worker-runtime` exports the worker-plane contracts directly.
3. Moved types keep the same behavior and validation.
4. No compatibility aliases remain under old package names.
5. Engine match path imports only candidate, evidence, admission, and routing
   contract packages.

## Slice WRA-2: Split Route Bucket Policy Ownership

Goal: keep low-level route-bucket SPI in registry while moving platform routing
policy to worker-runtime.

Scope:

1. Keep `WorkerRouteBucketPolicy` in `mass-runtime-api`.
2. Audit every memory/Redis import of `WorkerRouteBucketPolicies` and classify
   the use as constructor default, null fallback, test fixture, or production
   route-bucket logic.
3. Split `WorkerRouteBucketPolicies` into:
   - a registry-neutral default-bucket helper in `mass-runtime-api`; and
   - platform approved-attribute policy in `xa-mass-worker-runtime`.
4. Remove direct memory/Redis imports of the platform policy class.
5. Ensure `InMemoryWorkerRegistry` and `RedisWorkerRegistry` still receive a
   `WorkerRouteBucketPolicy` without depending on worker-runtime.
6. Ensure `EngineConfig` / server assembly injects the platform default route
   policy when creating default registries.
7. Audit engine strategy imports of `WorkerRouteBucketPolicies`,
   `WorkerRouteBucketPolicy`, and `WorkerMeta`; preserve task-side route
   selector behavior without letting engine strategy depend on worker-side
   registry metadata.

Acceptance:

1. Memory/Redis registries do not import `xa-mass-worker-runtime`.
2. WorkerGroup-first and route-attribute matching behavior is unchanged.
3. Standard approved route attributes live outside `mass-runtime-api`.
4. Engine strategy does not import worker-side registry metadata for routing.
5. Tests prove custom route policy injection still works for memory and Redis.

## Slice WRA-3: Slim `mass-runtime-api`

Goal: make the public contents match the intended low-level SPI.

Expected remaining worker package:

```text
com.xa.mass.runtime.worker
  CleanupSummary
  DispatchAvailabilitySource
  RandomWorkerCandidateSamplingPolicy
  ReserveResult
  ReserveStatus
  WorkerCandidateSamplingContext
  WorkerCandidateSamplingPolicy
  WorkerMeta
  WorkerRegistry
  WorkerRouteBucketPolicy
  WorkerSlot
```

If WRA-0 keeps `EventKey`, rename the documentation to say explicitly that it
is a worker capability scope key, not a global event identity.

If WRA-0 keeps any of `WorkerAdmissionContext`, `WorkerAdmissionPolicy`, or
`WorkerCleanupPolicy`, document why they are active low-level registry
extension points. Otherwise remove or move them in this slice.

Acceptance:

1. `mass-runtime-api` worker package contains only registry primitives and
   active registry extension points.
2. `mass-runtime-memory` and `mass-runtime-redis` compile with only
   `mass-runtime-api`.
3. `xa-mass-worker-runtime` compiles without any circular dependency.
4. Engine imports `com.xa.mass.runtime.worker.*` only for low-level primitives
   when unavoidable; worker-plane contracts come from `xa-mass-worker-runtime`.

## Slice WRA-4: Architecture Guards

Goal: prevent regression into another shared API bucket.

Add or update guards:

Automated guards:

1. `mass-runtime-api` worker package must match an explicit allowlist after
   WRA-3. Any new type in `com.xa.mass.runtime.worker` fails the guard until
   the allowlist and WRA inventory are updated.
2. `mass-runtime-api` must not define worker resource/control/report/match
   contracts.
3. `mass-runtime-memory` and `mass-runtime-redis` must not depend on
   `xa-mass-worker-runtime`.
4. Engine strategy may import worker-plane contracts from
   `xa-mass-worker-runtime` candidate, evidence, admission, and routing
   packages only; it must not import resource/report/control packages,
   registry implementation classes, or low-level registry primitives such as
   `WorkerRegistry`, `WorkerSlot`, and `WorkerMeta`.
5. Transport may consume `WorkerResourceRuntime` from worker-runtime, but must
   not mutate `WorkerRegistry` directly.
6. Engine control may consume resource/report/control contracts, but match
   strategy may not.

Process guard:

7. New `com.xa.mass.runtime.worker` types require explicit classification as
   registry primitive or active registry extension point in the WRA inventory.

Acceptance:

1. Guard tests fail if a high-level worker-plane type is added to
   `mass-runtime-api`.
2. Guard tests fail if memory/Redis import worker-runtime.
3. Guard tests fail if moved types reappear as aliases in `mass-runtime-api`.
4. Guard tests fail if any non-allowlisted type exists in
   `com.xa.mass.runtime.worker`.
5. Guard tests fail if engine match strategy imports worker resource/report or
   control packages, or directly imports worker registry primitives.

## Slice WRA-5: Proof And Verification

Run the existing WRX proof set after the package move:

```powershell
mvn -pl platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am `
  '-Dtest=InMemoryWorkerRegistryTest,RedisWorkerRegistryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-worker-runtime,xa-mass-engine -am `
  '-Dtest=WorkerManagerTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-worker-runtime,xa-mass-engine,xa-mass-sdk -am test
```

```powershell
mvn -pl xa-mass-server -am `
  '-Dtest=ExternalWorkerParitySuite' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Acceptance:

1. Memory/Redis registry contract tests pass.
2. Engine match and architecture guard tests pass.
3. SDK/runtime selection proof still passes.
4. External worker black-box proof still passes.
5. No behavior or public API semantics change beyond Java package ownership.

## Implementation Notes

- This should be mostly file move plus import correction.
- Commit by slice or domain batch:
  - WRA-0 inventory/doc-only commit.
  - WRA-0.5 contract alignment commit if interfaces are split or renamed.
  - WRA-1a data type move commit.
  - WRA-1b interface move commit.
  - WRA-2 route policy split commit.
  - WRA-3/WRA-4 slim API and guard commit if small enough; split if test churn
    is large.
  Avoid one commit per moved file and avoid one mega-commit for the whole
  roadmap.
- Do not preserve old package aliases.
- If a moved type is used by generated docs or snippets, update the docs in the
  same change.
- If package movement creates ambiguous ownership, stop and update WRA-0
  instead of adding a convenience wrapper.
