# Policy Interaction Baseline

Last updated: 2026-04-20

This document defines the guardrails for adding or changing scheduling policies without creating combinatorial behavior that cannot be reasoned about.

It is intentionally about interaction boundaries, not policy implementation details.

Use with:

- [../STATE_MACHINE_BASELINE.md](../STATE_MACHINE_BASELINE.md)
- [../TRACE_CONTRACT.md](../TRACE_CONTRACT.md)
- [../E2E_BASELINE.md](../E2E_BASELINE.md)
- [./TASK_EXECUTION_FLOW.md](./TASK_EXECUTION_FLOW.md)

## 1. Problem Boundary

Policy interaction risk appears when multiple policies can affect the same runtime fact.

High-risk facts:

- task lifecycle status
- task terminal reason
- task intake state
- logical `TaskMsg` status
- active `TaskMsgAttempt`
- worker lock
- worker-context reservation or occupancy
- retry eligibility
- refill eligibility
- routing and matching eligibility

The project should support more policies over time, but each policy must have a narrow owner, explicit inputs, explicit output, and traceable reason.

## 2. Policy Layers

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

Design rule:

- a policy should return a decision, not directly mutate unrelated aggregate state
- state mutation should happen in a small orchestration path that can validate invariants and emit trace
- if current code already mutates directly, new changes should move that path toward explicit decision plus orchestrated mutation instead of adding another side effect

## 3. Global Precedence

When policies overlap, use this order:

1. manual terminal or cancel closes the task and prevents reopening
2. pause or block stops new dispatch but does not erase already-dispatched attempts
3. callback write-back resolves the active attempt before logical message finality is evaluated
4. retry decision happens after attempt close and before logical message finality
5. resource release happens after attempt close or task terminal notification
6. refill request happens only after resource release and only if the task/message state is still eligible
7. terminal policy evaluates after stable message/attempt reconciliation
8. open-intake tasks do not close from normal all-messages-final convergence until sealed

Any new policy that needs a different precedence must update this file, state-machine baseline, trace contract, and E2E coverage together.

## 4. Hard Invariants

These invariants must hold across all policy combinations:

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

## 5. Risky Interaction Pairs

These pairs need explicit coverage whenever touched:

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

## 6. Trace Requirements For Policy Changes

New or changed policy paths must make the decision reconstructable from logs or structured trace.

Minimum decision fields:

- `taskId`
- `msgId` when message-specific
- `attemptId` when attempt-specific
- `workerId` when worker-specific
- `workerContextId` when context-specific
- `policyName`
- `decision`
- `reason`
- `trigger`
- relevant before/after status fields

Preferred event naming:

- use existing stable trace events when the decision maps to an existing lifecycle event
- add a new stable event name only when an existing event would hide an independent decision point
- update [../TRACE_CONTRACT.md](../TRACE_CONTRACT.md) in the same change when adding a stable event

Examples:

- matching decisions should surface through worker match accept/reject traces
- retry decisions should be visible with attempt close and retry reset traces
- terminal decisions should be visible with terminal-policy evaluation and terminal closure traces
- resource decisions should be visible with release decision and release result traces

## 7. Test Strategy

Do not try to exhaustively test every policy combination.

Required test shape:

- policy contract tests for each new or changed policy seam
- pairwise integration tests for every risky interaction pair touched by the change
- E2E tests only for high-value runtime paths where HTTP, gateway, callback, and persistence must converge together

Minimum assertions for policy-interaction tests:

- final task status and terminal reason
- final message status and final reason
- attempt count and active-attempt invariant
- worker and worker-context release state
- relevant trace or observable audit signal

If a policy change cannot be explained through these assertions, the policy boundary is probably too implicit.

## 8. Agent Working Rule

Before changing a policy path:

1. identify which policy layer owns the decision
2. identify which aggregate is allowed to mutate
3. check the global precedence order
4. add trace for the decision point if it is not already observable
5. cover at least the touched pairwise interaction
6. update the relevant baseline docs in the same change

Do not add a policy that silently changes another layer's source of truth.
