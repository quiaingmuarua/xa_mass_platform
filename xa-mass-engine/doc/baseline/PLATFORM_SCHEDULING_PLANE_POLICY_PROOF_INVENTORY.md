# Platform Scheduling Plane Policy Proof Inventory

Status: current PP-0 inventory for
`roadmap/PLATFORM_SCHEDULING_PLANE_POLICY_PROOF_HARDENING_ROADMAP.md`.

This inventory classifies current computed-default Scheduling Plane facts. It
does not create a policy product, does not treat resolved records as writable
truth, and does not by itself prove policy behavior. Rows marked with existing
runtime evidence may still need additional perturbation or bypass tests before
the roadmap can claim hard policy proof.

## Current Owner Decisions

- The next implementation slice is binding-entry bypass proof, not policy
  product work and not a broader worker-side perturbation matrix.
- `TaskSchedulingTestHarness` may be extended for test-only entry drivers.
  Production bridge/facade/wrapper paths remain out of scope.
- `routeAttributes` remains `resolved-only/unproven`; do not implement or
  placeholder-test route-attribute scheduling in the next slice.
- Profile-backed fields remain classified as mixed carrier or
  mechanism-profile proof until a separate consumer-convergence slice.

## Proof Class Legend

| Class | Meaning |
| --- | --- |
| hard-proven | Integrated runtime outcome proof currently exercises the fact and observes runtime truth. This does not imply PP-3 binding-entry bypass proof unless the entry-specific row says so. |
| support-regression | Useful low-level regression, but not policy proof. |
| mechanism-profile proof | Current runtime profile mechanism is proven, but the proof does not show the resolved policy record carrying the behavior. |
| resolved-only/unproven | Current code carries the field but no distinct runtime consumer or outcome proof exists. |
| not-policy-truth | The value is capability, runtime evidence, trace, or payload context rather than policy truth. |

## Policy-Fact Inventory

| Fact | Writable truth owner | Resolved view | Runtime consumer | Current proof class | Runtime outcome/evidence | Bypass or residue risk | Next action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `workerGroupIds` / `workerGroupId` | `Task.sharedConfig` conventional selector keys | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | `WorkerTaskSelectorFactory#fromPolicy` -> worker candidate runtime | hard-proven | `TaskSchedulingGateAndTargetingTest#workerGroupSelectorNarrowsCandidatePoolBeforeRuntimeSelection` observes selected worker lease/binding, task RUNNING, ready/inflight counts, no non-selected group record, and no non-selected lock | Reintroducing `WorkerTaskSelectorFactory#fromTask` or all-worker scan would bypass the resolved view | Keep in mainline proof; residue scan for removed bridge/fallback paths |
| `targetWorkerId` | `Task.sharedConfig.targetWorkerId` | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | candidate lookup plus Stage-2 prefilter in `RuleBasedTaskWorkerMatchingStrategy` | hard-proven | `TaskSchedulingGateAndTargetingTest#targetWorkerIdWaitingTaskDoesNotDriftToBackupWorkerAfterContentionClears` observes READY wait, target conflict record, no backup binding, later target lease, task RUNNING | Direct target dispatch must stay group/candidate scoped, not a side-door bind | Keep in mainline proof and PP-3 binding-entry inventory |
| `targetWorkerAttributes` | `Task.sharedConfig.targetWorkerAttributes` | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | Stage-2 prefilter via `WorkerMatchContext` snapshot | hard-proven | `TaskSchedulingGateAndTargetingTest#targetWorkerAttributesRemainStableUnderContention` observes accepted gold worker, rejected silver record, locked gold conflict | Attribute matching can drift if rule context and prefilter context split | Keep `WorkerMatchContextTest` as boundary guard for context separation |
| `routingCode` | `Task.sharedConfig.routingCode` | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | Stage-2 prefilter and rule context | hard-proven | `TaskWorkerEligibilityTest#workerPrefilterExcludesUnreachableLockedOccupiedAndRoutingMismatchCandidates` and min-worker gate tests observe routing mismatch records, active leases, locks, status, and counters | `eventCode` or item payload must not replace routing as worker selector | Keep existing proof; do not add selector field-copy tests |
| `routeAttributes` | `Task.sharedConfig.routeAttributes` | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | route bucket policy / worker route buckets where configured | resolved-only/unproven in engine-local policy proof | Worker-runtime index and server trace tests cover route-bucket mechanics, but current engine-local harness does not produce a distinct route-attribute scheduling outcome | Easy to overclaim because resolver tests assert map copying | Keep `resolved-only/unproven`; do not implement or test route-attribute scheduling without a separate owner decision |
| `routeBucketKeys` | worker routing policy derived from task route facts | `ResolvedWorkerSchedulingPolicy` | `WorkerTaskSelectorFactory#fromPolicy` -> worker candidate runtime warm/cold source | support-regression | Candidate source stats exist in assignment records, and worker-runtime index tests cover bucket mechanics, but current engine-local hard proof is tied to group/routing outcomes rather than bucket-key perturbation alone | Warm/cold source guard can look proven by source scans only | Keep residue scans as sanity; add hard proof only when route-bucket behavior changes runtime assignment outcome |
| `adapterNodeId` | `Task.sharedConfig.adapterNodeId` | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | `WorkerTaskSelectorFactory#fromPolicy` selector; binder records adapter evidence from worker scheduling view | resolved-only/unproven | No current integrated test proves adapter-node perturbation changes candidate outcome | Field may be mistaken for public policy before there is a caller-visible outcome | Keep classified only; do not add placeholder test |
| `eventCode` | SDK metadata under `Task.sharedConfig._sdk.eventCode` and runtime item event binding | `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy` | worker group/event capability checks and dispatch binding | not-policy-truth | Capability and handler identity are covered by scheduling/dispatch tests, but `eventCode` is not worker policy truth | Reusing event code as worker selector would violate Scheduling Plane boundary | Keep as capability identity, not policy proof |
| `workloadClass` | `Task.executionSpec.workloadClass` | `ResolvedTaskSchedulingPolicy`; also runtime profile | `DefaultWorkerBudgetPolicy`, retry/profile resolvers, lane/profile owners | hard-proven for current outcomes; mixed carrier | `TaskSchedulingContentionTest#largeBulkTaskIsCappedAndLeavesWorkersForInteractiveTask` observes bulk cap, remaining ready/inflight counts, interactive lease; `TaskAssignWorkerTest#retryDelayUsesResolvedWorkloadPolicy` observes retry delay trace | Some runtime owners still resolve profile from task, so not every workload outcome is resolved-record proof | Keep hard proof for budget/retry outcomes; PP-2 must not overclaim profile-derived mechanisms as resolved-record proof |
| `dispatchLane` | derived from `Task.executionSpec.workloadClass` by runtime profile | `ResolvedTaskSchedulingPolicy` | `TaskAssignWorker` lane state | hard-proven | `TaskAssignWorkerTest#interactiveLaneContinuesWhileBulkLaneIsBlocked` observes interactive lane progress while bulk lane is blocked and queue trace lanes | Lane proof is local to assignment signal owner, not full worker-selection proof | Keep as essential lane proof; broader integration only if lane ownership changes |
| `dispatchPriority` | derived from workload profile | `ResolvedTaskSchedulingPolicy` | `TaskAssignWorker` priority queue signal ordering | support-regression | Current tests observe lane trace and queue behavior, but no distinct same-lane priority perturbation outcome is proven | Getter or queue-order unit tests would be weak proof | Keep outside policy proof unless same-lane priority becomes caller-visible |
| `batchPolicy` | derived from workload profile | `ResolvedTaskSchedulingPolicy`; runtime profile | `TaskRuntimeClaimOptionsResolver` via `TaskRuntimeProfileResolver` | mechanism-profile proof | Claim option tests/profile behavior protect the mechanism, but not resolved-policy carrier consumption | Resolved field can be mistaken as carrying claim behavior when current consumer reads profile directly | Record as profile-backed; no fake resolved-record test |
| `leaseProfile` | derived from workload profile | `ResolvedTaskSchedulingPolicy`; runtime profile | `TaskRuntimeClaimOptionsResolver` via `TaskRuntimeProfileResolver` | mechanism-profile proof | Lease duration options are profile-backed; hard runtime lease behavior is covered by redispatch/expiry tests | Same carrier-vs-consumer risk as `batchPolicy` | Keep classified until claim options consume resolved task policy or an integrated lease-profile perturbation exists |
| `backpressureClass` | derived from workload profile | `ResolvedTaskSchedulingPolicy`; runtime profile | `TaskRuntimeEnqueueOptionsResolver` via `TaskRuntimeProfileResolver` | mechanism-profile proof | `TaskKernelLifecycleTest` covers ready/intake truth; current backpressure class consumption is profile-backed | Do not claim resolved-policy proof while enqueue owner resolves profile directly | Keep as mechanism proof unless enqueue options consume resolved policy |
| `batchSize` | `Task.executionSpec.batchSize` | `ResolvedTaskSchedulingPolicy` | `DefaultAssignmentAllocationPolicy` and runtime claim options | hard-proven | `TaskSchedulingContentionTest#batchSizeChangesDispatchWorkerCountWithoutLosingReadyWork` observes same ready work and worker pool with different batch sizes changing bound worker count while ready/inflight counters remain consistent | Allocation object tests were field-copy/plan tests and have been deleted | Keep integrated outcome proof |
| `defaultMaxRetryCount` | `Task.executionSpec.defaultMaxRetryCount` | `ResolvedTaskSchedulingPolicy`; task runtime retry policy path | runtime retry / lease expiry owner | hard-proven as lifecycle retry input; mixed carrier | `TaskRedispatchCompetitionTest` and lifecycle/result tests observe retry/expiry/finalization outcomes | Current retry policy paths do not all consume `ResolvedTaskSchedulingPolicy` directly | Keep lifecycle proof; classify carrier path separately in PP-2 if resolved-record consumption is required |
| `minRequiredWorkerCount` | task aggregate field | `ResolvedTaskSchedulingPolicy` | `DefaultAssignmentAllocationPolicy` | hard-proven | `TaskSchedulingGateAndTargetingTest` and `TaskWorkerEligibilityTest` observe READY gate, zero active leases, worker unlock, later successful dispatch, task RUNNING, and counters | Allocation plan unit tests do not prove the gate and have been deleted | Keep integrated gate proof |
| foreground/background resource semantics | `Task.executionSpec.foreground` | not in resolved task policy | `WorkerDispatchResourcePolicy` | hard-proven mechanism; not policy product | `TaskSchedulingContentionTest#backgroundTasksShareStatelessWorkerUpToDeclaredCapacity` observes shared active lease count, no exclusive lock, quota rejection, and task status | Treating foreground/background as policy product before vocabulary decision would be premature | Keep in scheduling correctness matrix, not as public policy catalog proof |

## Existing Test Triage

| Test | Classification | PP-0 action | Reason |
| --- | --- | --- | --- |
| `TaskSchedulingGateAndTargetingTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Observes worker-group, target, min-worker, lease, lock, task status, and runtime counters. |
| `TaskWorkerEligibilityTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Observes runtime eligibility outcomes, rejection records, active leases, and worker state. |
| `TaskSchedulingContentionTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Observes contention, budgets, sharing, locks, ready/inflight counters, and release/refill. |
| `TaskRedispatchCompetitionTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Observes retry/lease-expiry redispatch and stale-result lifecycle outcomes. |
| `TaskDelayedAvailabilitySchedulingTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Observes late worker availability moving READY work into dispatch. |
| `TaskRuntimeRecoveryPortTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Exercises recovery owner path for runtime scheduling state. |
| `TaskAssignWorkerTest` | essential boundary/lifecycle guard | keep in `EngineSchedulingCoreSuite` | Protects assignment lane, retry, dedupe, and queue lifecycle behavior not covered by broader task-manager tests. |
| `TaskWorkerAssignListenerTest` | essential binding/lifecycle guard | keep in `EngineSchedulingCoreSuite` | Protects assignment listener cleanup and binder entry behavior. |
| `TaskResourceReleaseListenerTest` | hard proof | keep in `EngineSchedulingCoreSuite` | Observes resource release enabling later scheduling. |
| `WorkerSchedulingCandidateEnumeratorTest` | support regression | keep in suite only as owner-boundary guard | Protects candidate scheduling-view enumeration; not standalone policy proof. |
| `RuleBasedTaskWorkerMatchingStrategyTest` | support/boundary regression | keep in suite only as matching boundary guard | Protects rule/prefilter behavior and behavior-neutral assertion audit; not enough by itself for policy proof. |
| `WorkerMatchContextTest` | essential boundary guard | keep in `EngineSchedulingCoreSuite` | Protects approved rule-readable context and prevents live runtime evidence from entering rule evaluation. |
| `DefaultAssignmentAllocationPolicyTest` | delete candidate | deleted | Object/plan field assertions are covered by stronger integrated gate, budget, and dispatch-count outcomes. |
| `DefaultWorkerBudgetPolicyTest` | delete candidate | deleted | Budget field assertions are covered by integrated budget/contention outcomes. |
| `DefaultSchedulingPlaneResolverTest` | support regression | keep outside mainline policy proof | Resolver value construction drift check only; not runtime outcome proof. |
| `WorkerTaskSelectorFactoryTest` | delete candidate if present | no mainline proof allowed | Selector field-copy tests do not prove worker selection behavior. |

## Current PP-0 Decisions

- `DefaultAssignmentAllocationPolicyTest` and `DefaultWorkerBudgetPolicyTest`
  are deleted rather than moved to another suite. Keeping them would preserve CI
  cost without adding runtime proof.
- `WorkerMatchContextTest` remains selected because it protects the rule-context
  boundary: diagnostic and live runtime evidence stay out of rule evaluation.
- Existing covered rows in `doc/PROOF_REGISTRY.md` remain scheduling
  correctness proof, not a declaration that a public policy product exists.
- Fields classified `resolved-only/unproven` are intentionally not followed by
  placeholder tests.

## Binding Entry Inventory

This table records the current production entries that can move work toward a
worker binding. It is a bypass proof inventory, not a completed bypass proof
and not a source-shape guard. Full PP-3 completion still requires
entry-specific perturbation tests that prove these entries cannot bypass policy
facts while binding work.

```text
scheduling eligibility
  -> resolved scheduling view
  -> candidate-source constraints
  -> runtime worker selection
  -> allocation
  -> dispatch binding
```

| Entry | Current owner path | Existing evidence | Proof status | Residue or blocker |
| --- | --- | --- | --- | --- |
| Direct task assignment | `TaskAssignWorker#submit` queues a dispatchable task by resolved lane/priority, then `TaskWorkerAssignListener#onTaskAssign` checks status/runtime-ready work, matches candidates, allocates, and calls `TaskDispatchBinder#bindDispatches`. | `TaskSchedulingGateAndTargetingTest`, `TaskSchedulingContentionTest`, and `TaskWorkerEligibilityTest` observe task status, active leases, locks/load, assignment records, and ready/inflight counters. | control path; existing scheduling proof, not PP-3A completion credit | Use only as the control path for comparing non-direct entries. Do not spend PP-3A quota on another direct-entry-only test. |
| Retry redispatch | `TaskAssignWorker` schedules retry or requeue only for tasks that remained dispatchable after an assignment attempt, then re-enters `onTaskAssign`. | `TaskAssignWorkerTest#wakeWaitingRetriesRequeuesKnownRetryWithoutWaitingForDelay` proves bounded retry wakeup ownership; `TaskRedispatchCompetitionTest` proves redispatch competition and stale-result rejection outcomes. | inventoried; existing lifecycle proof, not complete policy-bypass proof | Needs a retry-entry perturbation test with a policy-sensitive outcome after retry re-entry. Retry delay/profile carrier is mixed. |
| Runtime-ready pump | `RuntimeReadyDispatchPump` reads runtime-owned dispatchable tasks and invokes the injected dispatch attempt, currently wired by `EngineRuntimeKernel` as `TaskWorkerAssignListener::onTaskAssign`. The pump does not select or bind workers. | `TaskRuntimeRecoveryPortTest` proves runtime-ready task source behavior; `RuntimeReadyDispatchPumpTest` proves pump admission/wakeup mechanics. | inventoried; support-mechanism evidence only | Needs an integrated runtime-ready-pump scenario proving worker policy constraints still decide the final binding after pump entry. |
| Lease-expiry redispatch | `LeaseExpireWatchdog` calls `TaskLeaseMaintenancePort#expireLeasedWork`; runtime lease ownership requeues retryable work, which later re-enters assignment through runtime-ready or assignment signal paths. | `TaskRedispatchCompetitionTest#leaseExpiryReentersBatchTaskIntoCompetitionPoolAndRedispatchesSameWorkOnce`, `#leaseExpiryReleasesWorkerForWaitingTaskCompetition`, and `#expiredWorkWaitsUnderCompetitionAndRedispatchesAfterWorkerRelease` observe lease token behavior, counters, worker release, and later assignment. | inventoried; lifecycle proof, not complete policy-bypass proof | Needs a lease-expiry redispatch perturbation proving policy selection is reapplied and cannot bind through stale/old selection state. |
| Worker-availability wakeup | `TaskDispatchWakeupBridge` fans out to `TaskAssignWorker#wakeWaitingRetries` and `RuntimeReadyDispatchPump#wakeIdleAdmissions`; it does not scan tasks, select workers, or bind work. | `TaskDispatchWakeupBridgeTest` proves fanout ownership; `TaskAssignWorkerTest#wakeWaitingRetriesRequeuesKnownRetryWithoutWaitingForDelay` proves bounded retry acceleration; `TaskDelayedAvailabilitySchedulingTest` proves late eligible workers can move READY work into dispatch. | inventoried; existing trigger proof, not complete policy-bypass proof | Needs wakeup-entry perturbation proving the woken task still honors target/group/routing policy before binding. |
| Target-worker dispatch | Targeting enters as a worker-side policy fact in `ResolvedWorkerSchedulingPolicy`; matching narrows candidates and still flows through allocation and binder. | `TaskSchedulingGateAndTargetingTest#targetWorkerIdWaitingTaskDoesNotDriftToBackupWorkerAfterContentionClears` observes no backup binding, target conflict, later target lease, task status, and counters. | hard worker-side fact proof; entry-bypass proof still partial | Needs coverage that target behavior remains true across retry/wakeup/redispatch entries, not only direct assignment. |

## PP-3A Entry Proof Targets

The next proof slice should add or materially strengthen runtime outcome tests
for these entries. Completion requires tests, not only this table.

| Entry | Preferred policy-sensitive fact | Required runtime outcome | Recommended test surface |
| --- | --- | --- | --- |
| Direct assignment / target dispatch control | `workerGroupIds` or `targetWorkerId` | selected worker lease/binding follows the policy fact; disallowed worker has no assignment record, no lock, no `MSG_ASSIGN`, and no dispatch binding | `TaskSchedulingGateAndTargetingTest` via existing harness; control only, does not count toward PP-3A completion |
| Retry / waiting-retry wakeup | `targetWorkerId` | retried or woken task does not drift to backup worker, then binds target after target becomes available | `TaskAssignWorker` or `TaskDispatchWakeupBridge` -> `TaskAssignWorker#wakeWaitingRetries` through an extended `TaskSchedulingTestHarness` |
| Runtime-ready pump scan / idle-admission wake | `workerGroupIds` | pump-triggered dispatch binds only selected group, with non-selected group excluded from assignment records, locks, `MSG_ASSIGN`, and dispatch bindings | `RuntimeReadyDispatchPump` through an extended deterministic `TaskSchedulingTestHarness`; no unbounded sleep |
| Lease-expiry redispatch | `workerGroupIds` or `targetWorkerId` | expired/requeued work re-enters policy-sensitive selection and cannot reuse stale/old selection to bind a disallowed worker | runtime lease expiry surfaces through an extended `TaskSchedulingTestHarness`; avoid invalid mid-run policy mutation |

`routeAttributes` is intentionally absent from this table because its current
classification is `resolved-only/unproven`.

PP-3A completion requires the three non-direct categories above: retry/wakeup,
runtime-ready pump, and lease-expiry redispatch. Direct assignment remains the
control path only.

## Explainability Inventory

Current hard scheduling outcomes must be explainable from runtime truth,
assignment diagnostics, and bounded trace evidence. These evidence surfaces are
read-side proof only; they do not own policy truth. This section records
available explanation surfaces; it is not a trace-observed proof bundle for
every policy entry.

| Policy fact or entry | Primary runtime evidence | Explanation surface | Trace gap status |
| --- | --- | --- | --- |
| `workerGroupIds` / `workerGroupId` | selected group leases/bindings, non-selected group no assignment/lock, task status, ready/inflight counters | assignment records, worker match accepted/rejected trace, dispatch binding evidence with WorkerGroup id | no new gap |
| `targetWorkerId` | READY wait under target conflict, no backup binding, later target lease after release | target-worker conflict assignment records, worker match rejection reason, dispatch binding worker id | no new gap |
| `targetWorkerAttributes` | accepted required-attribute worker, rejected non-matching worker, lock/resource contention | assignment records with worker scheduling snapshot and match rejection reason | no new gap |
| `routingCode` | routing mismatch rejection, accepted matching worker, active leases/locks | worker match rejection reason, assignment diagnostic context, worker scheduling evidence | no new gap |
| `workloadClass` / `dispatchLane` | budget/resource/lane outcome changes under workload profile | assignment summary trace with workload class, lane, priority, budget, requested match count, and dispatched count | no new gap |
| `batchSize` | same ready work and workers produce different bound worker count while ready/inflight counters remain consistent | assignment summary and dispatch binding summary include batch size, requested match count, dispatched count, unique worker count, and per-worker batch limit | no new gap |
| `minRequiredWorkerCount` | READY gate, no partial start, zero active leases, worker unlock, later dispatch after enough eligible workers exist | assignment summary required minimum, skip reason, worker resource release trace, assignment records | no new gap |
| retry / lease-expiry redispatch | lease token changes, stale result rejection, ready/inflight/final counters, worker release and later assignment | task work attempt transitions, assignment retry scheduled trace, dispatch binding summary, result/lease diagnostics | no new gap |
| worker-availability wakeup | delayed/late availability moves eligible READY work through normal assignment path | assignment queue snapshot, worker availability wakeup fanout tests, later assignment records | no new gap |

The bounded trace gap file remains unchanged by this inventory because the
current scheduling outcomes can be explained from existing assignment records,
runtime counters, and trace fields. PP-4 is not complete until the next proof
slice ties these explanation surfaces to the actual perturbation and entry
tests.
