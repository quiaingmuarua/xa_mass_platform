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

## Target Shape

```text
xa-mass-engine
  -> xa-mass-worker-runtime      worker resource/candidate/admission/report APIs
  -> mass-runtime-api            task work/result runtime APIs only

transport
  -> xa-mass-worker-runtime      worker resource lookup and runtime evidence

xa-mass-sdk / xa-mass-server
  -> xa-mass-worker-runtime      worker platform API surface
  -> mass-runtime-api            task runtime selection only where needed

xa-mass-worker-runtime
  -> mass-runtime-api            low-level registry primitives
  -> mass-storage-api            control-plane worker declarations
  -> xa-mass-base                stable base model conversion where still needed

mass-runtime-memory / mass-runtime-redis
  -> mass-runtime-api            implement WorkerRegistry only
```

`mass-runtime-api` remains the neutral low-level SPI. It must not carry
worker-plane resource, report, candidate, or control contracts.

## Current Problem

`mass-runtime-api` currently has two worker layers in one package.

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
WorkerAdmissionContext
WorkerAdmissionPolicy
WorkerCleanupPolicy
```

These three look like low-level registry extension points, but current code
does not use them in production. Either keep them only if a memory/Redis
implementation uses them, or delete/move them in the same slice. Do not leave
unused policy seams in `mass-runtime-api`.

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

Acceptance:

1. No code behavior changes.
2. Every worker type in `mass-runtime-api` has one owner classification.
3. The keep/move/delete list is explicit.
4. Memory/Redis required types are proven from imports, not assumed.

## Slice WRA-1: Move High-Level Worker Contracts

Goal: make `xa-mass-worker-runtime` the module that exposes worker-plane
contracts.

Scope:

1. Move worker resource/control records and contracts:
   - `WorkerResourceRuntime`
   - `WorkerResourceRecord`
   - `WorkerGroupRecord`
   - `AdapterNodeRecord`
   - `NodeGroupBindingRecord`
   - `EventBinding`
2. Move worker report and projection contracts:
   - `WorkerReportRuntime`
   - `WorkerCapabilityReport*`
   - `WorkerStateReport`
   - `WorkerStateProjection*`
3. Move worker candidate/match evidence contracts:
   - `WorkerCandidateRuntime`
   - `WorkerCandidateBatch`
   - `WorkerCandidateRow`
   - `WorkerTaskSelector`
   - `WorkerGroupCapabilityView`
   - `WorkerLoadSnapshot`
   - `WorkerReachabilityState`
   - `WorkerReachabilityView`
4. Move high-level admission/wakeup/gate contracts:
   - `WorkerAdmissionRuntime`
   - `WorkerAvailabilityWakeupRuntime`
   - `WorkerDispatchGateRuntime`
   - `WorkerSchedulingViewRuntime`
   - `WorkerWarmHintRuntime`
5. Update imports in engine, SDK, server, transport, and worker-runtime tests.
6. Do not keep forwarding types in `mass-runtime-api`.

Acceptance:

1. Production source outside memory/Redis no longer imports moved worker-plane
   types from `com.xa.mass.runtime.worker`.
2. `xa-mass-worker-runtime` exports the worker-plane contracts directly.
3. Moved types keep the same behavior and validation.
4. No compatibility aliases remain under old package names.

## Slice WRA-2: Split Route Bucket Policy Ownership

Goal: keep low-level route-bucket SPI in registry while moving platform routing
policy to worker-runtime.

Scope:

1. Keep `WorkerRouteBucketPolicy` in `mass-runtime-api`.
2. Move `WorkerRouteBucketPolicies` to `xa-mass-worker-runtime`, or split it
   into:
   - a registry-neutral default-bucket helper in `mass-runtime-api`; and
   - platform approved-attribute policy in `xa-mass-worker-runtime`.
3. Ensure `InMemoryWorkerRegistry` and `RedisWorkerRegistry` still receive a
   `WorkerRouteBucketPolicy` without depending on worker-runtime.
4. Ensure `EngineConfig` / server assembly injects the platform default route
   policy when creating default registries.

Acceptance:

1. Memory/Redis registries do not import `xa-mass-worker-runtime`.
2. WorkerGroup-first and route-attribute matching behavior is unchanged.
3. Standard approved route attributes live outside `mass-runtime-api`.
4. Tests prove custom route policy injection still works for memory and Redis.

## Slice WRA-3: Slim `mass-runtime-api`

Goal: make the public contents match the intended low-level SPI.

Expected remaining worker package:

```text
com.xa.mass.runtime.worker
  CleanupSummary
  DispatchAvailabilitySource
  EventKey
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

1. `mass-runtime-api` must not define worker resource/control/report/match
   contracts.
2. `mass-runtime-memory` and `mass-runtime-redis` must not depend on
   `xa-mass-worker-runtime`.
3. Engine strategy may import worker-plane contracts from
   `xa-mass-worker-runtime`, but must not import registry implementation
   classes.
4. Transport may consume `WorkerResourceRuntime` from worker-runtime, but must
   not mutate `WorkerRegistry` directly.
5. New `com.xa.mass.runtime.worker` types require explicit classification as
   registry primitive or active registry extension point.

Acceptance:

1. Guard tests fail if a high-level worker-plane type is added to
   `mass-runtime-api`.
2. Guard tests fail if memory/Redis import worker-runtime.
3. Guard tests fail if moved types reappear as aliases in `mass-runtime-api`.

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
- Use one or a small number of phase commits; avoid one commit per moved file.
- Do not preserve old package aliases.
- If a moved type is used by generated docs or snippets, update the docs in the
  same change.
- If package movement creates ambiguous ownership, stop and update WRA-0
  instead of adding a convenience wrapper.
