# Worker Runtime API Slimming Roadmap

Status: in progress. WRA-0 inventory is complete in
[`WORKER_RUNTIME_API_SLIMMING_INVENTORY.md`](./WORKER_RUNTIME_API_SLIMMING_INVENTORY.md).
WRA-0.5 contract alignment decisions are recorded here and in the inventory.
WRA-1a resource contract family has moved to `xa-mass-worker-runtime`.
WRA-1b match/evidence/admission contract families have moved to
`xa-mass-worker-runtime`.
WRA-1c report/state/control contract families have moved to
`xa-mass-worker-runtime`.
WRA-2 route-bucket policy ownership has been split: registry keeps only the
low-level SPI/default helper, and platform approved-attribute routing now lives
in `xa-mass-worker-runtime`.
This roadmap follows
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
  WorkerAdmissionResult / WorkerAdmissionStatus
```

Engine match path must not consume resource mutation or report-ingress
contracts. It also should not consume `WorkerMeta`, `WorkerSlot`, or
`WorkerRegistry` directly; those are registry primitives owned below
worker-runtime.

Engine control / SDK / server resource paths may consume:

```text
resource declaration
  WorkerResourceRuntime
  WorkerResourceDeclarationRuntime
  WorkerResourceQueryRuntime
  WorkerNodeBindingRuntime
  WorkerResourceRecord
  WorkerGroupRecord
  AdapterNodeRecord
  NodeGroupBindingRecord
  EventBinding
  EventKey remains a low-level project-scoped registry key

report and gate projection
  WorkerReportRuntime
  WorkerCapabilityReport*
  WorkerStateReport
  WorkerStateProjection*
  WorkerDispatchGateRuntime
```

Transport may consume worker resource lookup and report input contracts. It
must not call `WorkerRegistry`, admission APIs, or full resource mutation
surfaces directly.

`WorkerResourceRuntime` was intentionally suspicious in this roadmap because it
mixed declaration, lookup, node/group binding, and runtime-adjacent relationship
reads. WRA-0.5 keeps `WorkerResourceRuntime` as the full composite surface for
current SDK/server assembly, but splits the caller-facing contracts into:

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
EventKey
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
WorkerAdmissionResult
WorkerAdmissionStatus
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
WorkerResourceDeclarationRuntime
WorkerResourceQueryRuntime
WorkerNodeBindingRuntime
WorkerSchedulingViewRuntime
WorkerStateProjection
WorkerStateProjectionResult
WorkerStateProjectionRuntime
WorkerStateProjectionStatus
WorkerStateReport
WorkerTaskSelector
WorkerWarmHintRuntime
```

Delete or justify before slimming `mass-runtime-api`:

```text
WorkerAdmissionContext
WorkerAdmissionPolicy
WorkerCleanupPolicy
```

`EventKey` is currently a `(projectCode, eventCode)` key used for worker
capability scope, not a globally unique event identity. WRA-0 keeps it as a
low-level registry ceiling primitive because `WorkerRegistry.upsertSlot(...)`
uses it directly. WRA-3 must document that project-scoped meaning if the type
name remains unchanged.

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
  DefaultWorkerRouteBucketPolicy registry-neutral default-only helper

xa-mass-worker-runtime
  WorkerRouteBucketPolicies
  ApprovedAttributeRouteBucketPolicy
  STANDARD_APPROVED_ROUTE_ATTRIBUTES
```

Registry constructors should receive a `WorkerRouteBucketPolicy`. Their default
constructor may use only the registry-neutral default policy. Platform assembly
must inject the approved-attribute policy when it wants current
WorkerGroup-first route behavior.

The registry write path and worker-runtime source guard must use the same
route-bucket policy instance for a given engine assembly. Otherwise a worker
can be written into one bucket set while source guard validates it against a
different bucket convention.

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
  WorkerResourceDeclarationRuntime
  WorkerResourceQueryRuntime
  WorkerNodeBindingRuntime
  WorkerResourceRecord
  WorkerGroupRecord
  AdapterNodeRecord
  NodeGroupBindingRecord
  EventBinding
  EventKey references remain in mass-runtime-api

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
  WorkerAdmissionResult
  WorkerAdmissionStatus
  WorkerAvailabilityWakeupRuntime
  WorkerWarmHintRuntime

com.xa.mass.worker.runtime.control
  WorkerDispatchGateRuntime

com.xa.mass.worker.runtime.routing
  WorkerRouteBucketPolicies
  ApprovedAttributeRouteBucketPolicy
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

Status: contract decisions are made. The current alignment keeps the types in
`mass-runtime-api` only until WRA-1 movement, but narrows caller authority
before broad package churn:

- `WorkerResourceRuntime` remains the full composite resource surface.
- `WorkerResourceQueryRuntime` is the lookup-only surface for transport and
  engine control paths that do not need mutation.
- `WorkerResourceDeclarationRuntime` owns resource declaration mutation.
- `WorkerNodeBindingRuntime` owns node/group binding mutation.
- `WorkerAdmissionRuntime` returns `WorkerAdmissionResult` /
  `WorkerAdmissionStatus`; engine strategy no longer consumes
  `ReserveResult`, `ReserveStatus`, or `WorkerSlot` from the admission path.
- `WorkerSchedulingViewRuntime` keeps its current name for this roadmap; it is
  classified as scheduling evidence, not scheduling policy.
- engine-side `WorkerRoutingPolicy` is task-side only. Worker-side
  `WorkerMeta` bucket computation remains below the worker-runtime boundary.

Scope:

1. Define the worker-runtime contract families:
   - candidate source
   - scheduling evidence
   - admission
   - resource declaration/query
   - report/state projection
   - dispatch gate/control
   - warm hint
2. Verify current interfaces are correctly scoped:
   - `WorkerCandidateRuntime`
   - `WorkerSchedulingViewRuntime`
   - `WorkerAdmissionRuntime`
   - `WorkerResourceRuntime`
   - `WorkerReportRuntime`
   - `WorkerStateProjectionRuntime`
   - `WorkerDispatchGateRuntime`
   - `WorkerWarmHintRuntime`
   - engine-side `WorkerRoutingPolicy`
3. Keep `WorkerResourceRuntime` as a composite surface, and expose smaller
   resource declaration/query/binding contracts for narrow callers.
4. Keep the current `WorkerSchedulingViewRuntime` name for this roadmap and
   document it as evidence, not policy.
5. Keep `EventKey` in `mass-runtime-api` for this roadmap and document that it
   is a project-scoped worker capability key, not a global event identity.
6. Route `WorkerAdmissionRuntime` through a worker-plane admission result that
   hides registry reserve primitives from engine strategy.
7. Keep task-side route selector policy in engine for now. Engine may own the
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
6. Transport and engine control use `WorkerResourceQueryRuntime` where they
   need lookup only.
7. Package movement may proceed without changing contract semantics mid-move.

## Slice WRA-1a: Move Resource Contract Family

Goal: move the resource declaration/query family as one coherent contract
batch.

Status: complete. Resource records and resource runtime contracts now live
under `com.xa.mass.worker.runtime.resource`.

Do not move worker value types first while their runtime interfaces remain in
`mass-runtime-api`. That would force `mass-runtime-api` to depend on
`xa-mass-worker-runtime`, which violates the target dependency direction.
Move each contract family as value types plus the interfaces that reference
them.

Scope:

1. Move resource records:
   - `WorkerResourceRecord`
   - `WorkerGroupRecord`
   - `AdapterNodeRecord`
   - `NodeGroupBindingRecord`
   - `EventBinding`
   - `EventKey` references stay in `mass-runtime-api`
2. Move resource interfaces:
   - `WorkerResourceRuntime`
   - `WorkerResourceDeclarationRuntime`
   - `WorkerResourceQueryRuntime`
   - `WorkerNodeBindingRuntime`
3. Update imports in worker-runtime first, then engine control, SDK/server,
   transport, testing, and docs.
4. Do not keep forwarding types in `mass-runtime-api`.
5. Follow the package convention and contract family decisions from WRA-0.5.

Acceptance:

1. Moved resource types keep the same validation and behavior.
2. `mass-runtime-api` does not import or depend on `xa-mass-worker-runtime`.
3. Transport consumes only the resource query contract after the move.
4. No compatibility aliases remain under old package names.

## Slice WRA-1b: Move Match And Admission Contract Families

Goal: move worker matching evidence, candidate source, and admission contracts
without splitting value types from interfaces that reference them.

Status: complete. Candidate, scheduling-evidence, admission, wakeup, and warm
hint contracts now live under `com.xa.mass.worker.runtime.candidate`,
`com.xa.mass.worker.runtime.evidence`, and
`com.xa.mass.worker.runtime.admission`.

Scope:

1. Move candidate/routing contract family:
   - `WorkerCandidateRuntime`
   - `WorkerCandidateBatch`
   - `WorkerCandidateRow`
   - `WorkerTaskSelector`
2. Move scheduling evidence contract family:
   - `WorkerSchedulingViewRuntime`
   - `WorkerReachabilityView`
   - `WorkerGroupCapabilityView`
   - `WorkerLoadSnapshot`
   - `WorkerReachabilityState`
3. Move admission/wakeup/warm hint contract family:
   - `WorkerAdmissionRuntime`
   - `WorkerAdmissionResult`
   - `WorkerAdmissionStatus`
   - `WorkerAvailabilityWakeupRuntime`
   - `WorkerWarmHintRuntime`
4. Update imports in engine match/listener paths, worker-runtime owners,
   SDK assembly, tests, and perf helpers.
5. Do not keep forwarding types in `mass-runtime-api`.

Acceptance:

1. Engine match path imports candidate, evidence, and admission contracts from
   `xa-mass-worker-runtime`, not `mass-runtime-api`.
2. `mass-runtime-api` does not import or depend on `xa-mass-worker-runtime`.
3. Moved types keep the same behavior and validation.
4. No compatibility aliases remain under old package names.

## Slice WRA-1c: Move Report And Control Contract Families

Goal: move worker report, state projection, and dispatch-gate contracts after
the hot match/admission surface is already out of `mass-runtime-api`.

Status: complete. Report, state projection, and dispatch-gate contracts now
live under `com.xa.mass.worker.runtime.report` and
`com.xa.mass.worker.runtime.control`.

Scope:

1. Move report contract family:
   - `WorkerReportRuntime`
   - `WorkerCapabilityReport`
   - `WorkerCapabilityReportResult`
   - `WorkerCapabilityReportStatus`
2. Move state projection contract family:
   - `WorkerStateProjectionRuntime`
   - `WorkerStateReport`
   - `WorkerStateProjection`
   - `WorkerStateProjectionResult`
   - `WorkerStateProjectionStatus`
3. Move control contract:
   - `WorkerDispatchGateRuntime`
4. Update engine control, SDK/server, worker-runtime owners, tests, and docs.
5. Do not keep forwarding types in `mass-runtime-api`.

Acceptance:

1. Engine control/report paths import report and control contracts from
   `xa-mass-worker-runtime`, not `mass-runtime-api`.
2. `mass-runtime-api` does not import or depend on `xa-mass-worker-runtime`.
3. Moved types keep the same behavior and validation.
4. No compatibility aliases remain under old package names.

## Slice WRA-2: Split Route Bucket Policy Ownership

Goal: keep low-level route-bucket SPI in registry while moving platform routing
policy to worker-runtime.

Status: complete. Memory/Redis no longer import platform route policy, default
SDK/server assembly injects approved-attribute routing into both registry
bucket writes and worker-runtime source guard, and custom policy injection is
covered by registry tests.

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
   policy when creating default registries and passes the same policy into
   `WorkerManager` / `WorkerCandidateIndex` source-guard construction.
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
6. Tests or assembly checks prove registry bucket writes and source-guard
   revalidation use the same route policy for the default platform assembly.

## Slice WRA-3: Slim `mass-runtime-api`

Goal: make the public contents match the intended low-level SPI.

Expected remaining worker package:

```text
com.xa.mass.runtime.worker
  CleanupSummary
  DispatchAvailabilitySource
  EventKey
  DefaultWorkerRouteBucketPolicy
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

Document `EventKey` explicitly as a worker capability scope key, not a global
event identity.

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
5. Transport may consume `WorkerResourceQueryRuntime` from worker-runtime, but
   must not consume full resource mutation surfaces or mutate `WorkerRegistry`
   directly.
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
mvn -pl xa-mass-worker-runtime,xa-mass-engine,transport/transport_runtime -am `
  '-Dtest=WorkerAdmissionOwnerTest,WorkerManagerTest,RuleBasedTaskWorkerMatchingStrategyTest,TransportRuntimeRegistryTest,TransportRoutingTaskDispatchListenerTest,EngineSchedulingCoreArchitectureGuardTest' `
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
3. Transport routing and registry lookup tests pass without using full
   resource mutation contracts.
4. SDK/runtime selection proof still passes.
5. External worker black-box proof still passes.
6. No behavior or public API semantics change beyond Java package ownership.

## Implementation Notes

- This should be mostly file move plus import correction.
- Commit by slice or domain batch:
  - WRA-0 inventory/doc-only commit.
  - WRA-0.5 contract alignment commit if interfaces are split or renamed.
  - WRA-1a resource contract-family move commit.
  - WRA-1b match/admission contract-family move commit.
  - WRA-1c report/control contract-family move commit.
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
