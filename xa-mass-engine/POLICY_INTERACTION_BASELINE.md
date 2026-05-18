# Policy Interaction Baseline

Last updated: 2026-05-16

Status: current engine policy baseline.

This file defines engine policy interaction guardrails only.

Use with:

- [README.md](./README.md)
- [SCHEDULING_KERNEL_GUARDRAILS.md](./SCHEDULING_KERNEL_GUARDRAILS.md)
- [WORKER_SCHEDULING_VIEW_BASELINE.md](./WORKER_SCHEDULING_VIEW_BASELINE.md)
- [../doc/STATE_MACHINE_BASELINE.md](../doc/STATE_MACHINE_BASELINE.md)
- [../doc/TRACE_CONTRACT.md](../doc/TRACE_CONTRACT.md)
- [../doc/E2E_BASELINE.md](../doc/E2E_BASELINE.md)

## 1. Policy Layers

| Layer | Owns | May decide | Must not decide |
| --- | --- | --- | --- |
| Matching policy | worker scheduling-view eligibility | whether a candidate can match | task terminal state, retry, resource release, allocation budget |
| Ranking policy | preference order among rule-passed candidates | candidate ordering by load, routing affinity, or future fairness hints | eligibility correctness, runtime claim, task mutation |
| Acquisition/resource policy | candidate resource acquisition semantics | capacity reservation and whether an exclusive worker lock is required | candidate ranking, task terminal state, runtime work claim |
| Assignment policy | current dispatch shape | requested match count, minimum-worker start gate, dispatch candidate selection | result interpretation, task terminal state, runtime claim/bind |
| Worker budget policy | per-task worker budget | task-level worker ceiling and budget-exhausted decision | matching eligibility, runtime claim, result interpretation |
| Attempt policy | attempt lifecycle | create, close, expire, revoke, retry reset eligibility | task-level terminal reason except by reporting attempt outcome |
| Resource-release mechanism | runtime slot release | release reservations and exclusive worker locks selected by resource policy | logical message finality, refill formula |
| Refill policy | continued dispatch | whether pending `INIT` messages should re-enter assignment | worker matching semantics |
| Terminal policy | task closure | whether a task should close and why | worker selection, attempt mutation |
| Intake policy | append window | whether work items can be appended or sealed | worker/resource state |
| Control policy | manual/operator actions | pause, block, cancel, terminate intent | hidden result rewriting |

Rule:

- a policy returns a decision; orchestration owns cross-aggregate mutation and trace
- `AssignmentAllocationPolicy` returns only an allocation plan and decision; it
  must not mutate task status, unlock workers, claim runtime work, bind attempts,
  publish events, or decide terminal/result policy
- `TaskWorkerAssignListener` owns assignment orchestration around allocation
  decisions, while `SimpleTaskDispatchBinder` owns runtime claim and dispatch
  binding after candidates are selected
- `RuleBasedTaskWorkerMatchingStrategy` currently combines eligibility,
  ranking, capacity reservation, and optional worker-lock acquisition. That is
  the current acquisition path, not proof that reservation/lock acquisition is
  pure matching policy. Future splits must preserve the same lifecycle boundary
  instead of adding pass-through wrappers.
- `WorkerDispatchResourcePolicy` decides exclusive-lock usage. `WorkerDispatchResourceReleaser`
  executes cleanup mechanics. Listener and binder paths must not duplicate those
  formulas or cleanup primitives.

## 2. Global Precedence

1. manual terminal or cancel closes the task and prevents reopening
2. pause or block stops new dispatch but does not erase already-dispatched attempts
3. callback write-back resolves the active attempt before logical message finality
4. retry decision happens after attempt close and before logical message finality
5. resource release happens after attempt close or task terminal notification
6. refill request happens only after resource release and only if the task/message state is still eligible; it must re-enter through the engine-owned task dispatch path rather than an ad hoc external callback seam
7. terminal policy evaluates after stable message/attempt reconciliation
8. open-intake tasks do not close from normal all-messages-final convergence until sealed

Any change to this precedence must update state machine, trace contract, and E2E coverage in the same change.

## 3. Hard Invariants

- a logical message has at most one active attempt
- a stable-final logical message must not have an active attempt
- retryable failure closes the attempt but does not publish logical-final semantics
- no-active-attempt callbacks are rejected or ignored explicitly; they must not synthesize attempt history
- task terminal closure is `TERMINAL + terminalReason`, not a split terminal enum
- `OPEN` intake tasks do not close from normal all-final convergence
- `Worker.status` is engine control-plane worker lifecycle truth, not transport reachability truth
- transport reachability truth comes from transport presence and is consumed through engine reachability read seams
- worker lock truth remains in `WorkerStorage` / `WorkerManager.isLocked(...)`
- `Worker.attributes` and `WorkerSchedulingView` fields are matching labels only;
  they are not engine-owned device/account lifecycle state
- routing truth must come from explicit rules and worker scheduling attributes,
  not `workerGroupId`
- WorkerContext is not scheduling truth in the engine hot path; runtime,
  transport, projection, SDK/API, server payloads, and canonical trace identity
  are worker-level
- resource release must target the exact worker bound by the active or closing
  attempt and must apply the current `WorkerDispatchResourcePolicy`

## 4. Risky Interaction Pairs

| Pair | Failure mode to prevent |
| --- | --- |
| retry + resource release | duplicate release, leaked context, or duplicate active attempt |
| retry + refill | retry reset races with new dispatch |
| retry + terminal policy | retryable failure incorrectly closes the logical task |
| open intake + terminal policy | open task closes before seal |
| pause + callback | paused task fails to converge after dispatched callbacks finish |
| block + callback | runtime block erases valid in-flight results |
| manual terminal + late callback | terminal task reopens or counters mutate incorrectly |
| worker scheduling attributes + routing rule | worker satisfies routing without explicit scheduling evidence |
| min worker gate + delayed availability | task is orphaned after an insufficient match |
| shared-capacity worker + lock release | releasing one attempt frees an exclusive lock or reservation still needed by another active attempt |
| ranking + acquisition | high-ranked candidates leak reservations or locks when later acquisition/bind steps fail |
| worker budget + refill | large backlog repeatedly refills and starves another task despite budget evidence |

## 5. Trace And Test Rule

When a policy path changes:

- make the decision reconstructable from trace with `policyName`, `decision`, `reason`, `trigger`, and relevant ids/status
- cover at least the touched risky pairwise interaction
- assert final task status, terminal reason, final message status/final reason, attempt invariant, and release state

## 6. Working Rule

Before changing a policy path:

1. identify the owning policy layer
2. identify which aggregate may mutate
3. check global precedence
4. add decision trace if it is not already observable
5. cover the touched risky interaction pair
6. update owner docs in the same change
