# Policy Interaction Baseline

Last updated: 2026-04-27

This file defines engine policy interaction guardrails only.

Use with:

- [README.md](./README.md)
- [TASK_EXECUTION_FLOW.md](./TASK_EXECUTION_FLOW.md)
- [../doc/STATE_MACHINE_BASELINE.md](../doc/STATE_MACHINE_BASELINE.md)
- [../doc/TRACE_CONTRACT.md](../doc/TRACE_CONTRACT.md)
- [../doc/E2E_BASELINE.md](../doc/E2E_BASELINE.md)

## 1. Policy Layers

| Layer | Owns | May decide | Must not decide |
| --- | --- | --- | --- |
| Matching policy | worker and worker-context eligibility | whether a candidate can match | task terminal state, retry, resource release |
| Assignment policy | current dispatch shape | how many workers/items to dispatch this round | result interpretation, task terminal state |
| Attempt policy | attempt lifecycle | create, close, expire, revoke, retry reset eligibility | task-level terminal reason except by reporting attempt outcome |
| Resource-release policy | runtime slot release | when a worker/context slot can be released | logical message finality |
| Refill policy | continued dispatch | whether pending `INIT` messages should re-enter assignment | worker matching semantics |
| Terminal policy | task closure | whether a task should close and why | worker selection, attempt mutation |
| Intake policy | append window | whether work items can be appended or sealed | worker/resource state |
| Control policy | manual/operator actions | pause, block, cancel, terminate intent | hidden result rewriting |

Rule:

- a policy returns a decision; orchestration owns cross-aggregate mutation and trace

## 2. Global Precedence

1. manual terminal or cancel closes the task and prevents reopening
2. pause or block stops new dispatch but does not erase already-dispatched attempts
3. callback write-back resolves the active attempt before logical message finality
4. retry decision happens after attempt close and before logical message finality
5. resource release happens after attempt close or task terminal notification
6. refill request happens only after resource release and only if the task/message state is still eligible
7. terminal policy evaluates after stable message/attempt reconciliation
8. open-intake tasks do not close from normal all-messages-final convergence until sealed

Any change to this precedence must update state machine, trace contract, and E2E coverage in the same change.

## 3. Hard Invariants

- a `TaskMsg` has at most one active `TaskMsgAttempt`
- a stable-final `TaskMsg` must not have an active attempt
- retryable failure closes the attempt but does not publish logical-final semantics
- no-active-attempt callbacks are rejected or ignored explicitly; they must not synthesize attempt history
- task terminal closure is `TERMINAL + terminalReason`, not a split terminal enum
- `OPEN` intake tasks do not close from normal all-final convergence
- `Worker.status` remains the online truth
- worker lock truth remains in `WorkerStorage` / `WorkerManager.isLocked(...)`
- `Worker.attributes` and `WorkerContext.attributes` remain matching labels only
- routing truth must come from explicit rules and worker-context signals, not `workerGroupId`
- stateless workers may match only tasks whose rules do not require worker-context-specific routing
- resource release must target the exact worker and worker-context bound by the active or closing attempt

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
| worker without context + routing rule | stateless worker satisfies context-specific routing by accident |
| min worker gate + delayed availability | task is orphaned after an insufficient match |
| multi-context worker + lock release | releasing one context unlocks unrelated sibling context incorrectly |

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
