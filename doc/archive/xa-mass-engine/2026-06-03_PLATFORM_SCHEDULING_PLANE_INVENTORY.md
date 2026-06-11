# Platform Scheduling Plane Inventory

Status: archived PSP-0 inventory / classification.

Archived: 2026-06-03 after PSP mainline implementation and active-link cleanup.

Source roadmap:
`doc/archive/xa-mass-engine/2026-06-11_PLATFORM_SCHEDULING_PLANE_ROADMAP.md`.

This inventory classifies the current Scheduling Plane inputs before any new
policy owner or contract is introduced. It records current production behavior
and proof surfaces; it does not define new runtime behavior.

## Current Code Evidence

Primary production evidence:

| Area | Current source |
| --- | --- |
| Public task creation shape | `sdk/xa-mass-public-contract/src/main/java/com/xa/mass/contract/task/TaskCreateRequest.java` |
| Public task execution options | `sdk/xa-mass-public-contract/src/main/java/com/xa/mass/contract/task/TaskExecutionSpec.java` |
| Public task shared-config keys | `sdk/xa-mass-public-contract/src/main/java/com/xa/mass/contract/task/TaskSharedConfigKeys.java` |
| Base task shared-config helpers | `xa-mass-base/src/main/java/com/xa/mass/base/model/TaskSharedConfig.java` |
| Base task execution options | `xa-mass-base/src/main/java/com/xa/mass/base/model/TaskExecutionSpec.java` |
| Server task create mapping | `xa-mass-server/src/main/java/com/xa/mass/api/internal/TaskApiController.java` |
| Runtime profile resolution | `xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/TaskRuntimeProfileResolver.java` |
| Assignment lanes / retry signal queue | `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskAssignWorker.java` |
| Assignment budget / gate policy | `xa-mass-engine/src/main/java/com/xa/mass/engine/assignment/DefaultAssignmentAllocationPolicy.java` |
| Worker budget defaults | `xa-mass-engine/src/main/java/com/xa/mass/engine/assignment/DefaultWorkerBudgetPolicy.java` |
| Assignment refill policy | `xa-mass-engine/src/main/java/com/xa/mass/engine/assignment/DefaultAssignmentRefillPolicy.java` |
| Worker selector adapter | `xa-mass-engine/src/main/java/com/xa/mass/engine/strategy/WorkerTaskSelectorFactory.java` |
| Stage-1 candidate source | `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateIndex.java` |
| Worker selector value | `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate/WorkerTaskSelector.java` |
| Stage-2 matching mechanism | `xa-mass-engine/src/main/java/com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java` |
| Rule-readable context | `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java` |
| Worker scheduling read view | `xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java` |
| Candidate ranker | `xa-mass-engine/src/main/java/com/xa/mass/engine/strategy/DefaultWorkerCandidateRanker.java` |
| Default matching rules | `xa-mass-engine/src/main/java/com/xa/mass/engine/rules/RuleConfig.java` |
| Rule set provider seam | `xa-mass-engine/src/main/java/com/xa/mass/engine/rules/MatchingRuleSetProvider.java` |
| Storage-backed rule set assembly | `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/StorageBackedMatchingRuleSetProvider.java` |
| Event-backed group selector assembly | `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/WorkerGroupSelectorResolver.java` |
| Runtime-ready pump | `xa-mass-engine/src/main/java/com/xa/mass/engine/watchdog/RuntimeReadyDispatchPump.java` |

Representative proof surfaces:

| Area | Current tests |
| --- | --- |
| Runtime profile mapping | `xa-mass-engine/src/test/java/com/xa/mass/engine/runtime/TaskRuntimeProfileResolverTest.java` |
| Matching context | `xa-mass-engine/src/test/java/com/xa/mass/engine/model/WorkerMatchContextTest.java` |
| Matching strategy | `xa-mass-engine/src/test/java/com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategyTest.java` |
| Candidate source / route buckets | `xa-mass-worker-runtime/src/test/java/com/xa/mass/worker/runtime/WorkerCandidateIndexTest.java` |
| Assignment allocation | `xa-mass-engine/src/test/java/com/xa/mass/engine/assignment/DefaultAssignmentAllocationPolicyTest.java` |
| Assignment refill | `xa-mass-engine/src/test/java/com/xa/mass/engine/assignment/DefaultAssignmentRefillPolicyTest.java` |
| Runtime-ready pump | `xa-mass-engine/src/test/java/com/xa/mass/engine/watchdog/RuntimeReadyDispatchPumpTest.java` |
| Public task contract | `sdk/xa-mass-public-contract/src/test/java/com/xa/mass/contract/task/PublicTaskContractTest.java` |
| Server task API | `xa-mass-server/src/test/java/com/xa/mass/api/internal/TaskApiControllerTest.java` |
| Scheduling E2E harness | `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskSchedulingTestHarness.java` |

## Classification Legend

Owner classes:

- `task-scheduling-policy`
- `worker-scheduling-policy`
- `runtime-worker-selection`
- `project-workload-binding`
- `task-dispatch-intent`
- `worker-group-capability`
- `runtime-admission-backpressure`
- `engine-lifecycle-repair`
- `matching-evaluator-input`
- `trace-diagnostic-evidence`
- `not-scheduling-policy`

Cost classes:

- `preset-only`
- `resolved-view`
- `runtime-stateful`
- `storage-backed`
- `not-policy`

## Production Input Classification

| Input | Current source | Owner class | Cost class | Rationale / PSP-1 note |
| --- | --- | --- | --- | --- |
| `TaskExecutionSpec.profile` | public/base execution spec | task-scheduling-policy | preset-only | Current public option, but current engine profile resolver ignores it for dispatch mapping. PSP-1 must decide whether it remains public vocabulary or retires as non-effective residue. |
| `TaskExecutionSpec.workloadClass` | public/base execution spec; `TaskRuntimeProfileResolver` | task-scheduling-policy | preset-only | Current primary task-side selector for dispatch lane, priority, batch/lease/backpressure class. |
| `TaskExecutionSpec.batchSize` | public/base execution spec; `DefaultAssignmentAllocationPolicy` | task-scheduling-policy | preset-only | Parameter that affects desired worker count calculation. Enforcement remains assignment mechanism. |
| `TaskExecutionSpec.maxRuntimeSeconds` | public/base execution spec | task-scheduling-policy | preset-only | Public task execution option. No current scheduling hot-path use found in PSP-0 search; PSP-1 should classify as future cadence/SLA parameter or non-effective residue. |
| `TaskExecutionSpec.defaultMaxRetryCount` | public/base execution spec; server append default resolution | task-scheduling-policy | preset-only | Runtime item retry budget default. Execution truth remains `TaskWorkRuntime` and result/attempt handling. |
| `TaskExecutionSpec.foreground` | public/base execution spec | task-scheduling-policy | preset-only | Public option. No current assignment hot-path use found; PSP-1 should classify as public vocabulary or retire. |
| `TaskRuntimeProfile.DispatchLane` | `TaskRuntimeProfileResolver`, `TaskAssignWorker` | task-scheduling-policy | preset-only | Controls which assignment queue a task enters. |
| `TaskRuntimeProfile.DispatchPriority` | `TaskAssignWorker` priority queue | task-scheduling-policy | preset-only | Queue ordering within a lane. |
| `TaskRuntimeProfile.BatchPolicy` | runtime profile | task-scheduling-policy | preset-only | Current named profile output. No direct use found outside profile/tests in PSP-0 search; candidate residue or future policy parameter. |
| `TaskRuntimeProfile.LeaseProfile` | runtime profile / retry resolver path | task-scheduling-policy | preset-only | Current named profile output tied to runtime behavior. PSP-1 must keep parameter vs execution owner split. |
| `TaskRuntimeProfile.BackpressureClass` | runtime profile / backpressure naming | task-scheduling-policy | preset-only | Current named profile output. Runtime backpressure truth stays in runtime queue/admission. |
| `TaskAssignWorker` dispatch lanes | engine assignment signal worker | task-scheduling-policy | runtime-stateful | Execution mechanism for lane queues/retry scheduling; policy may select lane/priority, but lane state is runtime owner. |
| `TaskAssignWorker` retry delay | engine assignment signal worker; `TaskRuntimeRetryPolicyResolver` | task-scheduling-policy | preset-only | Task-side redispatch cadence parameter. Retry queue execution belongs to `TaskAssignWorker`. |
| assignment queue capacity | `TaskAssignWorker.DEFAULT_ASSIGNMENT_QUEUE_CAPACITY` | runtime-admission-backpressure | runtime-stateful | Runtime queue capacity/backpressure, not policy definition truth. |
| `RuntimeReadyDispatchPump` interval / scan limit / idle backoff | `RuntimeReadyDispatchPump` | runtime-admission-backpressure | runtime-stateful | Runtime-driven batch redispatch cadence and backpressure mechanism. Can be parameterized by task policy later, but current owner is pump/runtime recovery. |
| `DefaultAssignmentAllocationPolicy` desired worker count | `readyWorkCount / batchSize` | task-scheduling-policy | preset-only | Value calculation is task policy candidate; dispatch decision is assignment mechanism. |
| `DefaultAssignmentAllocationPolicy` minimum start gate | `Task.minRequiredWorkerCount` | task-scheduling-policy | preset-only | Gate parameter. Enforcement remains assignment allocation and retry/release paths. |
| `DefaultWorkerBudgetPolicy` interactive max 5 / bulk max 20 | workload class budget defaults | task-scheduling-policy | preset-only | Per-task worker budget defaults. No public override yet. |
| `AssignmentAllocationOutcome.BELOW_MIN_START_GATE` | assignment allocation | runtime-admission-backpressure | runtime-stateful | Enforcement state and release/retry behavior, not policy definition. |
| `DefaultAssignmentRefillPolicy` running + ready work refill | task resource release listener path | runtime-admission-backpressure | runtime-stateful | Refill execution after worker release. Policy may parameterize future refill strategy, but current owner is assignment/runtime mechanism. |
| `Task.sharedConfig.workerGroupId` | base/shared config + public contract builder | task-dispatch-intent | resolved-view | Explicit resource-universe selector. |
| `Task.sharedConfig.workerGroupIds` | base/shared config + public contract builder | task-dispatch-intent | resolved-view | Explicit ordered set of resource universes. |
| `WorkerGroupSelectorResolver` event-backed selector | embedded SDK assembly | project-workload-binding | resolved-view | Assembly/intake materializes project/event capability into explicit group selector before assignment. |
| `Task.sharedConfig.adapterNodeId` | base shared config + worker selector factory | task-dispatch-intent | resolved-view | Adapter-node constraint for worker universe. Not in public `TaskSharedConfigKeys` currently. |
| `Task.sharedConfig.targetWorkerId` | base shared config + strategy prefilter + worker candidate index | task-dispatch-intent | resolved-view | Direct task-level target override. Requires explicit group selector in SDK resolver. Not exposed by public `TaskSharedConfigKeys` builder today. |
| `Task.sharedConfig.targetWorkerAttributes` | base/public shared config + strategy prefilter | task-dispatch-intent | resolved-view | Task-level constrained routing inside selected universe. |
| `Task.sharedConfig.routingCode` | base/public shared config + prefilter/ranker/rules | worker-scheduling-policy | preset-only | Route affinity / eligibility input. Should become resolved worker policy or task dispatch intent field, not item payload. |
| `Task.sharedConfig.routeAttributes` | base/public shared config + `WorkerRoutingPolicy` | worker-scheduling-policy | preset-only | Stage-1 approved route bucket narrowing input. |
| `Task.sharedConfig._sdk.eventCode` | base shared config | worker-group-capability | not-policy | SDK metadata for task-level event capability. It validates WorkerGroup support; it is not worker selection policy. |
| task item `eventCode` | server append/sync event resolution | not-scheduling-policy | not-policy | Handler/capability identity for item execution. It must not participate in worker matching. |
| `Task.project` | task shell / authorization / capability checks | project-workload-binding | resolved-view | Project scoping and capability binding input. |
| `Task.contract` | task shell / runtime-ready pump | task-scheduling-policy | preset-only | Current BATCH/SESSION distinction affects runtime-ready pump eligibility and terminal behavior. |
| `Task.status` READY/RUNNING | task lifecycle | runtime-admission-backpressure | runtime-stateful | Dispatchable status gate. Not policy definition truth. |
| `Task.intakeStatus` OPEN | task lifecycle / sync append guard | not-scheduling-policy | not-policy | Intake-window truth, not dispatch policy. |
| `Task.taskTargetNumber` | `WorkerMatchContext` diagnostic/rule-readable field | trace-diagnostic-evidence | not-policy | Exposed to rule context today but no current matching use found. PSP-1 should remove or mark diagnostic-only. |
| `WorkerGroupRecord.eventBindings` | worker runtime group capability | worker-group-capability | storage-backed | Capability truth for project/event support. |
| `WorkerGroupRecord.attributes` / defaults | worker runtime group record | worker-group-capability | storage-backed | Group-level capability/default metadata. It may feed worker policy resolution but is not Scheduling Plane truth. |
| worker attributes | worker registration/runtime row | worker-scheduling-policy | resolved-view | Current route tags and target attributes derive from worker attributes. PSP-1 should separate declarative attributes from live evidence. |
| worker `dispatchEnabled` | worker runtime scheduling view | runtime-worker-selection | runtime-stateful | Live admission/reachability gate used before rule evaluation. |
| transport reachability | worker reachability view | runtime-worker-selection | runtime-stateful | Live evidence. Excluded from declarative rules by default. |
| worker exclusive lock | worker admission runtime | runtime-worker-selection | runtime-stateful | Live lock/admission truth. |
| worker active lease count / reserved count / capacity / estimated load | worker admission runtime | runtime-worker-selection | runtime-stateful | Live ranking/admission evidence. |
| `WorkerCandidateIndex` group/adapter/route source guard | worker runtime candidate index | worker-scheduling-policy | resolved-view | Stage-1 resource-universe narrowing and stale-source guard. |
| `RuleConfig` default rules | engine rule config | matching-evaluator-input | preset-only | Current rule set mixes live runtime gates and declarative inputs. PSP-1 must narrow context. |
| `MatchingRuleSetProvider` | engine rule seam / SDK storage adapter | matching-evaluator-input | resolved-view | Read-only matching rule provider. Storage adapter is assembly, not engine policy truth. |
| candidate rank weights | `DefaultWorkerCandidateRanker` / `WorkerCandidateRankPolicy` | runtime-worker-selection | preset-only | Ranking strategy uses live load, route affinity, availability. |
| assignment trace / diagnostic context | `AssignmentDiagnosticRecorder`, trace logger | trace-diagnostic-evidence | not-policy | Observation/proof only. Must not reverse-drive policy ownership. |

## Shared Config Keys

| Key | Current exposure | Owner class | Cost class | Notes |
| --- | --- | --- | --- | --- |
| `routingCode` | base + public contract + SDK builder | worker-scheduling-policy | preset-only | Route affinity / eligibility. Current rules also read derived `workerSchedulingMatchesRoutingCode`. |
| `routeAttributes` | base + public contract + SDK builder | worker-scheduling-policy | preset-only | Stage-1 bucket narrowing through approved attributes. |
| `workerGroupId` | base + public contract + SDK builder | task-dispatch-intent | resolved-view | Explicit single group selector. |
| `workerGroupIds` | base + public contract + SDK builder | task-dispatch-intent | resolved-view | Explicit multi-group selector. |
| `adapterNodeId` | base helper only | task-dispatch-intent | resolved-view | Narrows to adapter node; not public builder key today. |
| `targetWorkerId` | base helper only | task-dispatch-intent | resolved-view | Direct worker override; SDK resolver requires explicit group selector if present. |
| `targetWorkerAttributes` | base + public contract + SDK builder | task-dispatch-intent | resolved-view | Worker attribute constraint after group selection. |
| `_sdk.eventCode` | base SDK metadata helper | worker-group-capability | not-policy | Task-level event capability metadata; not a worker selector. |

Known public-contract gap: public `TaskSharedConfigKeys` exposes
`targetWorkerAttributes` but not `targetWorkerId` or `adapterNodeId`; base
helpers support all three. PSP-1 should decide whether these stay internal
conventions or become public dispatch-intent fields.

## WorkerMatchContext Key Classification

`WorkerMatchContext` is the current catch-all rule/diagnostic context. The
table below is the required key-by-key PSP-0 inventory.

| Key | Source | Owner class | Cost class | Rule-readable decision | Migration recommendation |
| --- | --- | --- | --- | --- | --- |
| `taskId` | task shell | trace-diagnostic-evidence | not-policy | diagnostic-only | Keep only in diagnostics/trace. |
| `taskName` | task shell | trace-diagnostic-evidence | not-policy | diagnostic-only | Keep only in diagnostics/trace. |
| `taskProject` | task shell | project-workload-binding | resolved-view | declarative allowed | Move to declarative eligibility context only if project capability rules need it. |
| `taskEventCode` | `_sdk.eventCode` | worker-group-capability | not-policy | declarative allowed for capability validation | Prefer explicit capability validation outside generic rule map. |
| `taskUsesEventCapability` | derived from eventCode | worker-group-capability | not-policy | declarative allowed | Prefer capability validation result, not raw boolean in catch-all map. |
| `taskTargetWorkerId` | shared config | task-dispatch-intent | resolved-view | declarative allowed only as resolved intent | Move to `TaskDispatchIntent`; prefilter should enforce. |
| `taskTargetWorkerAttributes` | shared config | task-dispatch-intent | resolved-view | declarative allowed only as resolved intent | Move to `TaskDispatchIntent`; prefilter should enforce. |
| `taskSharedConfig` | task shared config | not-scheduling-policy | not-policy | no | Remove from rule context; too broad. |
| `routingCode` | shared config | worker-scheduling-policy | preset-only | declarative allowed | Move to resolved worker scheduling policy / route intent. |
| `taskHasRoutingRequirement` | derived from routingCode | worker-scheduling-policy | preset-only | declarative allowed | Move to route eligibility context. |
| `taskStatus` | task lifecycle | runtime-admission-backpressure | runtime-stateful | no | Keep as assignment gate / diagnostic, not rule input. |
| `taskTargetNumber` | task shell | trace-diagnostic-evidence | not-policy | diagnostic-only | Remove from rule input unless PSP-1 names a policy need. |
| `batchSize` | execution spec | task-scheduling-policy | preset-only | no by default | Use in assignment budget calculation, not worker eligibility. |
| `minRequiredWorkerCount` | task shell | task-scheduling-policy | preset-only | no by default | Parameter for assignment gate; not rule input. |
| `appCount` | supported projects size | worker-group-capability | storage-backed | no | Capability diagnostic only. PSP-3 removed it from active/default rule input. |
| `supportsProject` | group capability | worker-group-capability | storage-backed | declarative allowed | Prefer explicit capability validation outside generic rule map. |
| `supportsEvent` | group capability | worker-group-capability | storage-backed | declarative allowed | Prefer explicit capability validation outside generic rule map. |
| `matchesTargetWorkerId` | task intent + worker id | task-dispatch-intent | resolved-view | no by default | Enforce in prefilter/direct candidate path, not rule DSL. |
| `matchesTargetWorkerAttributes` | task intent + worker attributes | task-dispatch-intent | resolved-view | no by default | Enforce in prefilter. |
| `workerSchedulingProjectMatchesTaskProject` | current scheduling project field | matching-evaluator-input | resolved-view | no by default | Current `schedulingProject` is null in view; likely residue. |
| `workerSchedulingMatchesRoutingCode` | route tags + routingCode | worker-scheduling-policy | preset-only | declarative allowed | Move to narrowed route eligibility context. |
| `workerId` | worker row | trace-diagnostic-evidence | not-policy | diagnostic-only | Keep diagnostics/rank trace only. |
| `workerStatus` | worker row status | runtime-worker-selection | runtime-stateful | no | Runtime evidence / diagnostic, not declarative policy. |
| `transportReachability` | reachability view | runtime-worker-selection | runtime-stateful | no | Prefilter/admission only. |
| `isTransportReachable` | reachability view | runtime-worker-selection | runtime-stateful | no | Prefilter/admission only. |
| `workerGroupId` | worker relation | worker-group-capability | storage-backed | diagnostic-only | Group already selected before rules; keep diagnostic. |
| `workerAttributes` | worker row attributes | worker-scheduling-policy | resolved-view | declarative allowed for static attributes only | Split static scheduling attributes from live evidence. |
| `agentVersion` | worker row | trace-diagnostic-evidence | preset-only | no by default | Diagnostic only. A future static compatibility policy requires a named successor decision. |
| `supportedProjects` | group capability | worker-group-capability | storage-backed | declarative allowed | Prefer capability validation outside generic map. |
| `supportedEventCodes` | group capability | worker-group-capability | storage-backed | declarative allowed | Prefer capability validation outside generic map. |
| `isWorkerAvailable` | dispatch enabled + reachability | runtime-worker-selection | runtime-stateful | no | Current default rule reads it; PSP-1 should move to prefilter/admission. |
| `isWorkerLocked` | worker admission lock | runtime-worker-selection | runtime-stateful | no | Current default rule reads it; PSP-1 should move to prefilter/admission. |
| `workerActiveLeaseCount` | worker admission load | runtime-worker-selection | runtime-stateful | no | Runtime selection/ranking evidence. |
| `workerReservedCount` | worker admission load | runtime-worker-selection | runtime-stateful | no | Runtime selection/ranking evidence. |
| `workerDeclaredCapacity` | worker admission capacity | runtime-worker-selection | runtime-stateful | no | Runtime admission/ranking evidence. |
| `workerEstimatedLoadRatio` | worker admission load | runtime-worker-selection | runtime-stateful | no | Ranking evidence. |
| `currentActiveLeaseCount` | alias of active lease count | runtime-worker-selection | runtime-stateful | no | Duplicate alias; remove or diagnostics only. |
| `estimatedLoadRatio` | alias of load ratio | runtime-worker-selection | runtime-stateful | no | Duplicate alias; remove or diagnostics only. |
| `workerSchedulingResourceId` | worker id alias | trace-diagnostic-evidence | not-policy | diagnostic-only | Current alias; not policy truth. |
| `workerSchedulingProject` | current view field, always null | trace-diagnostic-evidence | not-policy | no | Likely residue; remove unless PSP-1 assigns meaning. |
| `workerSchedulingRoutingTags` | worker attributes | worker-scheduling-policy | resolved-view | declarative allowed | Static route eligibility input. |
| `workerSchedulingAttributes` | worker attributes | worker-scheduling-policy | resolved-view | declarative allowed | Static attribute eligibility input. |
| `hasWorkerSchedulingResource` | worker id exists | trace-diagnostic-evidence | not-policy | diagnostic-only | Current always true for valid view. |
| `isWorkerSchedulingResourceAllocatable` | dispatch enabled | runtime-worker-selection | runtime-stateful | no | Current default rule reads it; PSP-1 should move to prefilter/admission. |
| `isWorkerSchedulingResourceAvailable` | dispatch enabled + reachability | runtime-worker-selection | runtime-stateful | no | Runtime selection/admission evidence. |
| `isWorkerSchedulingResourceUsable` | dispatch enabled | runtime-worker-selection | runtime-stateful | no | Runtime selection/admission evidence. |
| `isWorkerSchedulingResourceReserved` | reserved count | runtime-worker-selection | runtime-stateful | no | Runtime admission/ranking evidence. |
| `isWorkerSchedulingResourceOccupied` | active lease count | runtime-worker-selection | runtime-stateful | no | Runtime admission/ranking evidence. |

## Min/Max Worker Gate Split

| Current value / behavior | Parameter owner | Enforcement owner | Mutable state |
| --- | --- | --- | --- |
| `Task.minRequiredWorkerCount` | task-scheduling-policy | `DefaultAssignmentAllocationPolicy.decide()` / retry path | assignment retry state only |
| raw desired worker count from `readyWorkCount / batchSize` | task-scheduling-policy | `DefaultAssignmentAllocationPolicy.plan()` | no new mutable policy state |
| workload max workers 5 / 20 | task-scheduling-policy | `DefaultWorkerBudgetPolicy` + allocation trim | current active worker count from admission/runtime |
| dispatch candidate limit after budget | task-scheduling-policy parameter | assignment allocation + matching max count | no new mutable policy state |
| `BELOW_MIN_START_GATE` handling | runtime-admission-backpressure | assignment release/retry path | reservations/locks are released by runtime mechanisms |
| refill after worker release | runtime-admission-backpressure | `DefaultAssignmentRefillPolicy` + resource release listener | runtime-ready work state |

PSP-1 decision: min/max worker values can become task scheduling policy
parameters, but hold/refill/release/retry execution must stay in assignment and
runtime mechanisms.

## Current Tests And Trace Surfaces Likely To Break

| Change area | Likely impacted proof |
| --- | --- |
| `WorkerMatchContext` narrowing | `WorkerMatchContextTest`, `RuleBasedTaskWorkerMatchingStrategyTest`, assignment diagnostic assertions |
| route/routing rule move | `RuleBasedTaskWorkerMatchingStrategyTest`, `WorkerCandidateIndexTest`, `TaskSchedulingGateAndTargetingTest` |
| min/max worker gate retargeting | `DefaultAssignmentAllocationPolicyTest`, `TaskSchedulingGateAndTargetingTest`, `TaskSchedulingContentionTest` |
| workload/profile vocabulary | `TaskRuntimeProfileResolverTest`, `TaskAssignWorkerTest`, scheduling contention tests |
| SDK shared config surface | `PublicTaskContractTest`, `TaskClientTest`, server API tests |
| event-backed selector assembly | embedded SDK tests and scenario launcher tests |
| runtime-ready pump policy | `RuntimeReadyDispatchPumpTest`, delayed availability scheduling tests |
| trace field names | trace analyzers, `TaskSchedulingTestHarness`, assignment diagnostics |

## PSP-1 Decisions Required

1. Decide whether first implementation stays assembly-default/computed-view
   only. Current evidence does not prove a persisted catalog/binding table is
   needed.
2. Decide whether `TaskExecutionSpec.profile`, `maxRuntimeSeconds`, and
   `foreground` remain effective public scheduling vocabulary, become future
   reserved fields, or are documented residue.
3. Decide whether public contract should expose `targetWorkerId` and
   `adapterNodeId`, since base shared-config helpers support them but
   `TaskSharedConfigKeys` does not.
4. Decide whether `routingCode`/`routeAttributes` are formal
   `WorkerSchedulingPolicy` inputs or `TaskDispatchIntent` route constraints.
5. Decide the final rule-readable boundary. Recommended default from the
   inventory: declarative rules may read task/project/route/static capability
   facts, but live reachability/load/lease/reserve/lock/admission fields move to
   runtime worker selection and admission.
6. Keep `agentVersion` diagnostic-only unless a successor decision introduces
   a named static compatibility policy.
7. Keep `appCount` diagnostic-only; PSP-3 removed it from active/default rule
   input.
8. Decide whether `SchedulingPolicyResolver` is needed now. Current evidence
   supports a computed resolver in assembly/SDK before any storage-backed
   catalog.
9. Decide how selected policy/binding/task intent appears in trace after PSP-2.

## PSP-0 Conclusion

PSP-0 confirms the roadmap's main boundary: current behavior mixes task policy,
worker resource-universe selection, runtime worker selection, capability truth,
and diagnostics in the same matching path. The safest next slice is PSP-1
decision work, not code movement.
