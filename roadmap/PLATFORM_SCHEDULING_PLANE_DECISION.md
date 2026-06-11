# Platform Scheduling Plane Decision

Status: PSP-1 owner / binding / persistence decision.

Source roadmap:
`doc/archive/xa-mass-engine/2026-06-11_PLATFORM_SCHEDULING_PLANE_ROADMAP.md`.

Inventory: `doc/archive/xa-mass-engine/2026-06-03_PLATFORM_SCHEDULING_PLANE_INVENTORY.md`.

This decision authorizes PSP-2 contract-shape work. It does not authorize a
persisted policy catalog, storage-backed project binding table, or behavior
change.

## Decision Summary

The first implementation path is:

```text
SDK/server assembly defaults + computed resolved views
  -> TaskDispatchIntent
  -> ResolvedTaskSchedulingPolicy
  -> ResolvedWorkerSchedulingPolicy
  -> existing assignment / matching / runtime owners
```

No new DB/control-plane policy truth is introduced in PSP-2 or PSP-3. A
persisted `SchedulingPolicyCatalog` / `ProjectSchedulingBinding` remains a
future option that requires at least two concrete policy variants with caller
and proof evidence.

## Layer Ownership

| Layer | Decision owner | Current first implementation |
| --- | --- | --- |
| Policy catalog | SDK/server assembly defaults | No persisted catalog. Define names only if PSP-2 needs value contracts. |
| Project/workload binding | SDK/server assembly / computed binding | No storage table. Project/event catalog and worker group capability stay separate. |
| Task dispatch intent | Engine-facing value derived from task shell + shared config | Represents explicit worker group selector, route, target override, and selected policy refs when present. |
| Resolved task scheduling view | Engine-facing resolved view | Contains task-side lane/priority/batch/retry/backpressure/budget parameters. |
| Resolved worker scheduling view | Engine-facing resolved view | Contains selected worker universe, route constraints, target constraints, rule-set reference if enabled. |
| Task scheduling execution | Existing engine assignment/runtime mechanisms | `TaskAssignWorker`, assignment allocation/refill, retry scheduling, runtime-ready pump, and `TaskWorkRuntime` remain execution owners. |
| Worker scheduling resolution | Existing worker candidate source / strategy adapter | Stage-1 group/adapter/route/target narrowing remains the current mechanism. |
| Runtime worker selection | Existing matching/rank/reserve/admission mechanisms | Live reachability/load/lease/reserve/lock/admission stays outside declarative policy. |

## Persistence Decision

Default: no persisted policy catalog or project scheduling binding in this
roadmap slice.

Rationale:

- Current evidence shows one main effective strategy axis:
  `TaskExecutionSpec.workloadClass` -> runtime profile and worker budget.
- Current project/event to WorkerGroup selector materialization already happens
  in SDK/server assembly through `WorkerGroupSelectorResolver`.
- Existing `MatchingRuleSetProvider` can remain a read-only rule provider
  without making engine consume storage-backed policy truth.
- Adding storage now would create duplicate policy truth before multiple
  concrete policy variants exist.

Persistence can be revisited only when a successor decision names:

- at least two concrete task scheduling policies or worker scheduling policies
  that require configurable selection,
- the caller that must configure them,
- the proof surface that validates the difference,
- the storage owner, and
- the runtime owner that consumes the resolved view.

## TaskSchedulingPolicy Boundary

Task scheduling policy owns resolved parameters for how work enters or
re-enters dispatch competition:

- dispatch lane
- queue priority
- batch-size-derived desired worker count
- assignment retry cadence
- default retry budget for appended work
- task-level max worker budget
- minimum start gate value
- backpressure class / runtime-ready scan class, when exposed as policy input

Task scheduling policy does not own:

- ready/delayed/leased work truth,
- queue storage,
- active lease indexes,
- result convergence,
- assignment hold/release/refill execution,
- worker admission, or
- transport delivery.

Execution remains in:

- `TaskAssignWorker`
- `DefaultAssignmentAllocationPolicy`
- `DefaultAssignmentRefillPolicy`
- `RuntimeReadyDispatchPump`
- `TaskWorkRuntime`
- result/lease maintenance owners

## WorkerSchedulingPolicy Boundary

Worker scheduling policy owns resolved parameters for the worker universe:

- allowed/selected WorkerGroup ids,
- adapter-node constraint,
- candidate bucket keys from approved route attributes,
- routing code / route affinity constraints,
- target worker override,
- target worker attributes,
- matching rule-set reference, if rule evaluation remains enabled.

Worker scheduling policy does not own:

- WorkerGroup project/event capability truth,
- worker reachability,
- dispatch-enabled status,
- worker locks,
- reservations,
- active lease count,
- estimated load,
- admission result, or
- concrete rank/reserve side effects.

## RuntimeWorkerSelection Boundary

Runtime worker selection owns live evidence and final concrete worker choice:

- transport reachability,
- dispatch-enabled gate,
- exclusive worker lock state,
- reserved count,
- active lease count,
- declared capacity,
- estimated load ratio,
- candidate ranking,
- capacity reservation,
- exclusive lock acquisition,
- admission rejection reasons,
- resource release after failed or completed dispatch.

These fields must not become declarative policy input by default.

## Rule-Readable Context Decision

Default: declarative rule input must be narrowed. The legacy
`WorkerMatchContext` must not remain the future policy contract.

Allowed declarative inputs:

- task project,
- task event/capability identity,
- selected WorkerGroup capability facts,
- selected route/routing code,
- static worker scheduling attributes,
- target constraints already represented in `TaskDispatchIntent`,
- selected rule-set metadata, if enabled.

Not allowed by default:

- transport reachability,
- dispatch-enabled status,
- worker lock state,
- active lease count,
- reserved count,
- declared capacity,
- estimated load ratio,
- current admission status,
- broad `taskSharedConfig`,
- duplicated aliases such as `currentActiveLeaseCount` and
  `estimatedLoadRatio`.

Explicit exceptions:

| Field | Decision |
| --- | --- |
| `agentVersion` | Not approved as default declarative input. It remains available in the full diagnostic snapshot only. A future static compatibility policy requires a named successor decision and proof. |
| `appCount` | Not approved. The old default rule treated supported project count as a load-like signal; PSP-3 removed it from active/default rule input and kept it diagnostic-only. |
| `isWorkerAvailable` / `isWorkerLocked` | Not approved. Move to prefilter/admission. |
| `isWorkerSchedulingResourceAllocatable` | Not approved as rule input. Move to runtime selection/admission. |

PSP-2 must define separate shapes or seams for:

- declarative worker eligibility input,
- runtime worker selection evidence,
- assignment diagnostics snapshot.

## Min/Max Worker Gate Decision

Gate values may be task scheduling policy parameters:

- `minRequiredWorkerCount`
- workload-based max worker budget
- desired dispatch worker count derived from ready work and batch size

Gate enforcement remains assignment/runtime execution:

- `DefaultAssignmentAllocationPolicy` computes and enforces the start gate,
- resource release returns reservations/locks to worker admission,
- retry and refill are driven by assignment/runtime-ready mechanisms,
- `TaskWorkRuntime` remains work visibility and lease truth.

No policy owner may directly hold, release, refill, or retry assignment work.

## Public / SDK Surface Decision

Current public fields remain source-compatible for now:

- `TaskExecutionSpec.profile`
- `TaskExecutionSpec.workloadClass`
- `TaskExecutionSpec.batchSize`
- `TaskExecutionSpec.maxRuntimeSeconds`
- `TaskExecutionSpec.defaultMaxRetryCount`
- `TaskExecutionSpec.foreground`
- `sharedConfig.workerGroupId`
- `sharedConfig.workerGroupIds`
- `sharedConfig.routingCode`
- `sharedConfig.routeAttributes`
- `sharedConfig.targetWorkerAttributes`

PSP-2 should not add new public fields unless the resolved-view contract needs
them.

Specific decisions:

- `profile`: keep as public vocabulary but do not treat it as effective
  scheduling truth until a later policy path uses it.
- `maxRuntimeSeconds`: keep as public vocabulary; no current dispatch policy
  effect found.
- `foreground`: keep as public vocabulary; no current dispatch policy effect
  found.
- `targetWorkerId`: keep as internal shared-config convention for now. Do not
  add to public `TaskSharedConfigKeys` until PSP-2/PSP-3 decide target override
  validation surface.
- `adapterNodeId`: keep internal for now.

## Trace Decision

PSP-2 should prepare trace fields for resolved policy evidence, but PSP-2 does
not need to emit new events.

Recommended trace shape after PSP-3:

- `taskSchedulingPolicy`
- `workerSchedulingPolicy`
- `taskDispatchIntent`
- `workerGroupSelector`
- `routeConstraint`
- `targetConstraint`
- `runtimeWorkerSelectionReason`

Trace remains evidence, not policy truth.

## PSP-2 Authorization

PSP-2 may introduce minimal contracts only if they preserve current behavior:

- `TaskDispatchIntent`
- `ResolvedTaskSchedulingPolicy`
- `ResolvedWorkerSchedulingPolicy`
- a resolver/adapter selected by this decision, if needed
- declarative eligibility input shape
- runtime worker selection evidence shape
- assignment diagnostic snapshot shape

PSP-2 must not introduce:

- persisted catalog tables,
- persisted project scheduling binding tables,
- engine imports of policy storage/control-plane modules,
- a broad `scheduling` module,
- root `ProjectSchedulingPolicy`,
- rule context containing unclassified live evidence,
- duplicate writable policy truth.

## PSP-4 Implementation Decision

PSP-4 is deferred for this roadmap iteration.

This decision selects computed defaults and resolved views only. It does not
select a second concrete policy variant, a caller-owned policy selector, or a
new SDK/server configuration surface. Adding a configuration path now would
create writable policy truth without a policy cost that needs it.

Current implementation target:

- derive `TaskDispatchIntent` from the task shell and explicit shared-config
  routing fields,
- derive `ResolvedTaskSchedulingPolicy` from the existing
  `TaskRuntimeProfileResolver`,
- derive `ResolvedWorkerSchedulingPolicy` from the existing worker-group,
  adapter, target, and route selector inputs,
- keep `TaskWorkRuntime`, assignment allocation/refill, candidate source,
  ranking, reserve, and admission as execution/runtime owners.

PSP-4 can be re-opened only when a successor decision names:

- the concrete task or worker scheduling policy variant,
- the caller that selects or configures it,
- the single writable owner of that policy fact, and
- the proof that current computed defaults are insufficient.

## Policy Truth Ownership Matrix

No new writable policy truth is introduced in this roadmap iteration. Existing
facts retargeted by PSP-2/PSP-3 have these single owners:

| Policy fact | Writable truth owner | Resolved consumer | Diagnostic evidence |
| --- | --- | --- | --- |
| dispatch lane / priority / workload class | task shell execution spec | `ResolvedTaskSchedulingPolicy` consumed by `TaskAssignWorker` | assignment logs / task profile tests |
| desired worker count and minimum start gate values | task shell execution spec and task fields | `ResolvedTaskSchedulingPolicy` consumed by assignment allocation | assignment records / scheduling tests |
| worker group selector | task shared config plus SDK/server selector assembly | `TaskDispatchIntent` / `ResolvedWorkerSchedulingPolicy` | trace candidate source and matching diagnostics |
| routing code / route attributes | task shared config | `TaskDispatchIntent` / worker scheduling resolution | rule context snapshot and trace |
| target worker override / target attributes | task shared config | `TaskDispatchIntent` / worker scheduling resolution | rule context snapshot and matching diagnostics |
| matching rules | rule definition owner and read-only provider | `MatchingRuleSetProvider` consumed by matching | rule evaluation records |

Runtime reachability, load, leases, locks, reservations, admission status,
queue state, and result convergence are not policy truth. They remain runtime
selection or task runtime evidence.

## Open Follow-Up For Later Slices

These are not blockers for PSP-2:

- whether `profile` should become a real scheduling policy selector,
- whether `targetWorkerId` should be public SDK vocabulary,
- whether `agentVersion` deserves a formal static compatibility policy,
- whether multiple concrete policy variants justify a persisted catalog,
- whether runtime-stateful fairness/quota policies are needed.
