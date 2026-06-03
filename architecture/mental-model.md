# Mental Model

Status: human-facing architecture overview.

This page explains the project in product and integration terms. For normative
kernel contracts, use [`../doc/`](../doc/) and owner module READMEs.

## The Mainline

The current execution mainline is:

```text
Task shell
  -> item append
  -> TaskWorkRuntime enqueue
  -> assignment and matching
  -> dispatch binder
  -> transport delivery
  -> worker execution
  -> result ingest
  -> TaskResultRuntime visible final row
  -> task progress / terminal convergence
```

In practical terms:

1. Create a task shell.
2. Append one or more work items to the task.
3. Each item declares an `eventCode`.
4. SDK/intake resolves event/project intent into explicit worker-group
   selectors when needed.
5. The engine narrows worker candidates through group capability, worker
   scheduling policy, runtime evidence, rule-backed eligibility, ranking, and
   runtime admission.
6. Transport delivers assigned work to the selected worker.
7. The worker executes local logic for the `eventCode`.
8. The worker submits a task result.
9. Runtime state decides retry, finality, resource release, and task
   convergence.

## Core Concepts

### Task

A task is the lifecycle shell. It carries task-level truth such as:

- project
- user
- contract
- intake status
- shared config
- execution spec

Task creation does not automatically mean there is work to execute. Work enters
through explicit item append.

### Task Item

A task item is the executable unit inside a task. It carries:

- `messageId`
- `eventCode`
- input payload
- retry budget and runtime lease state

It does not carry worker-selection policy. The item decides which handler
should run and what payload that handler receives; task dispatch intent and
WorkerGroup capability decide where work may be dispatched.

Public final result reads are item-level stable-final rows.

### Event

An event is the capability identity and invocation language. For normal task
work, `eventCode` tells the worker which local handler should process the item.
It is handler/capability identity, not a worker selector.

Current event metadata includes:

- `PriorityClass`
- `ResponseMode`
- `TargetScope`

These fields describe behavior and policy inputs. They do not directly own
queue placement, result finality, worker state, or command lifecycle.

### Worker

A worker is a runtime execution unit. Worker registration declares identity,
group/node membership, transport routing hints, and attributes.

Registration does not mean the worker is online, and worker registration does
not own event capability. Capability is declared on `WorkerGroup.eventBindings`;
online truth belongs to the transport presence plane.

### WorkerGroup

Worker capability is converging around group-backed event bindings:

```text
WorkerGroup
  -> eventBindings
  -> candidate source index

Worker
  -> runtime execution identity
  -> belongs to a group
```

SDK/intake may resolve `eventCode` and project into explicit
`workerGroupId(s)` before scheduling by validating against WorkerGroup
capability. The kernel candidate source starts from those group selectors, then
applies worker scheduling policy, runtime reachability/evidence, rule-backed
eligibility, ranking, reserve, and runtime admission. Matching is the current
two-stage worker-selection mechanism, not a top-level policy owner. Additional
selection inputs must remain explicit scheduling evidence and must not redefine
worker ownership.

Scheduling Plane has three first-class owners:

```text
TaskSchedulingPolicyExecution -> competition admission, cadence, priority, fairness, budget
WorkerSchedulingPolicyResolution -> worker universe, group selector, route, pool constraints
RuntimeWorkerSelection        -> live evidence, ranking, reserve, admission
```

Those owners consume explicit inputs and constraints:

```text
SchedulingPolicyCatalog / ProjectSchedulingBinding -> allowed/default policy selection
task dispatch intent                               -> selected/inherited policies, route, target constraints
WorkerGroupCapability                              -> project/event capability truth
item                                               -> eventCode plus payload only
```

The catalog/binding policy path is not a complete current implementation.
Today those policy concerns still live across task runtime profile, explicit
group selectors, matching rules, assignment policy, backpressure, and admission
behavior. Project/workload owns binding and scoped configuration, not the
reusable scheduling algorithm itself. WorkerGroupCapability is an external
capability truth that constrains worker scheduling resolution, not an internal
Scheduling Plane strategy. Worker scheduling policy must not absorb runtime
worker selection; live evidence, ranking, reserve, locks, and admission result
stay in `RuntimeWorkerSelection`.

### Transport

Transport owns worker delivery and result ingress mechanics. It must not own
task lifecycle.

Current adapters include:

- polling
- websocket
- socket

Transport presence answers whether a worker route is reachable. The engine
still owns worker selection and assignment.

### Result

Public result reads are runtime-owned stable-final rows from
`TaskResultRuntime`.

Server review/export materialization is lagging operator material. It must not
be the public `/results` or SDK result query truth.

## Owner Boundaries

Keep these boundaries in mind:

| Area | Owner | Meaning |
| --- | --- | --- |
| task lifecycle | engine | task status, terminal policy, intake, control commands |
| ready/lease/retry | `TaskWorkRuntime` | hot-path executable work truth |
| public results | `TaskResultRuntime` | stable-final rows and result sequence |
| capability candidate source | WorkerGroup / WorkerCandidateIndex | declared worker capability narrowing |
| runtime worker selection | engine + worker runtime | eligibility component, ranking, affinity, metrics, reserve, and admission orchestration |
| delivery and presence | transport | adapter routing, delivery, worker reachability |
| trace | trace/audit plane | evidence, not runtime truth |

## Common Misreads

Avoid these interpretations:

- Worker registration is not worker online state.
- Transport presence is not worker capability truth.
- `eventCode` is not a task type or worker selector; it is handler/capability identity.
- Item payload is not worker-selection policy.
- Result projection is not public result truth.
- Trace evidence is not lifecycle ownership.
- A unified event language does not imply one mandatory event runtime or one
  generic event owner.
