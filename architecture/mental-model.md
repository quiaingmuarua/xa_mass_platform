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
4. The engine narrows worker candidates by capability.
5. Transport delivers assigned work to the selected worker.
6. The worker executes local logic for the `eventCode`.
7. The worker submits a task result.
8. Runtime state decides retry, finality, resource release, and task
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

Public final result reads are item-level stable-final rows.

### Event

An event is the capability identity and invocation language. For normal task
work, `eventCode` tells the worker which local handler should process the item.

Current event metadata includes:

- `PriorityClass`
- `ResponseMode`
- `TargetScope`

These fields describe behavior and policy inputs. They do not directly own
queue placement, result finality, worker state, or command lifecycle.

### Worker

A worker is a runtime execution unit. Worker registration declares identity,
transport routing hints, attributes, and capability bindings.

Registration does not mean the worker is online. Online truth belongs to the
transport presence plane.

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

The scheduler first narrows candidates by `eventCode` and project, then applies
reachability, rules, ranking, resource policy, and runtime admission.

### Transport

Transport owns worker delivery and result ingress mechanics. It must not own
task lifecycle.

Current adapters include:

- polling
- websocket
- socket

Transport presence answers whether a worker route is reachable. The engine
still owns worker matching and assignment.

### Result

Public result reads are runtime-owned stable-final rows from
`TaskResultRuntime`.

Projection residue can still exist for compatibility, debug, and audit, but it
must not be the public `/results` or SDK result query truth.

## Owner Boundaries

Keep these boundaries in mind:

| Area | Owner | Meaning |
| --- | --- | --- |
| task lifecycle | engine | task status, terminal policy, intake, control commands |
| ready/lease/retry | `TaskWorkRuntime` | hot-path executable work truth |
| public results | `TaskResultRuntime` | stable-final rows and result sequence |
| capability candidate source | WorkerGroup / WorkerCandidateIndex | declared worker capability narrowing |
| delivery and presence | transport | adapter routing, delivery, worker reachability |
| trace | trace/audit plane | evidence, not runtime truth |

## Common Misreads

Avoid these interpretations:

- Worker registration is not worker online state.
- Transport presence is not worker capability truth.
- `eventCode` is not a task type; it is capability identity.
- Result projection is not public result truth.
- Trace evidence is not lifecycle ownership.
- A unified event language does not imply one mandatory event runtime or one
  generic event owner.

