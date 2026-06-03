# Platform Scheduling Plane Roadmap

Status: proposed direction roadmap.

This roadmap defines how XA Mass should converge toward a clear
platform-level Scheduling Plane without treating any target owner as already
implemented. The Scheduling Plane is the parent owner for task-side scheduling
policy, worker-side scheduling policy, and runtime worker selection.

It separates two policy families plus one runtime selection owner:

- `TaskSchedulingPolicy`: task-side competition admission, cadence, priority,
  quota/fairness, and task-level budget.
- `WorkerSchedulingPolicy`: worker-side resource-universe selection, group
  selector, route, target override, and worker-pool constraints.
- `RuntimeWorkerSelection`: concrete worker choice inside the resolved worker
  universe from live runtime evidence and admission state.

Name convention:

- `TaskSchedulingPolicy` and `WorkerSchedulingPolicy` are conceptual policy
  families.
- `TaskSchedulingPolicyDefinition` and `WorkerSchedulingPolicyDefinition` are
  catalog/config shapes for reusable policy definitions, if PSP-1 selects a
  catalog path.
- `ResolvedTaskSchedulingPolicy` and `ResolvedWorkerSchedulingPolicy` are
  engine-facing resolved views produced from catalog/binding/defaults plus task
  dispatch intent.
- `TaskSchedulingPolicyExecution` and `WorkerSchedulingPolicyResolution` are
  runtime owner names used in the Scheduling Plane architecture. The former
  executes task-side competition/cadence/budget behavior; the latter resolves
  worker universe and eligibility constraints before runtime worker selection.
- `RuntimeWorkerSelection` is the runtime selection owner. It is not a policy
  family and should not be described as one.

The target architecture separates policy families from the runtime selection
owner:

```text
Scheduling Plane
  TaskSchedulingPolicyExecution
  WorkerSchedulingPolicyResolution
  RuntimeWorkerSelection

External inputs and constraints
  SchedulingPolicyCatalog / ProjectSchedulingBinding  -> allowed/default policy selection
  TaskDispatchIntent                                  -> selected policies, route, target constraints
  WorkerGroupCapability                               -> project/event capability truth
  Item                                                -> eventCode plus payload only
```

The policy catalog and project binding nodes are target owner boundaries, not
current modules. WorkerGroupCapability is an external capability truth that
constrains worker scheduling resolution, not an internal Scheduling Plane
strategy. Current Scheduling Plane behavior is still distributed across task
runtime profile, explicit group selectors, assignment/allocation policy,
matching rule providers, runtime queue/backpressure behavior, and admission.

## Current Facts

- Scheduling Plane is documented as a target boundary in `AGENTS.md`,
  `doc/AGENT_BASELINE.md`, and `doc/TASK_LIFECYCLE_BASELINE.md`, but a complete
  policy catalog or project binding module does not exist yet.
- Scheduling-related ownership has three categories:
  - `TaskSchedulingPolicy`: decides whether, when, and with what task-level
    budget/priority/fairness a work batch enters dispatch competition.
  - `WorkerSchedulingPolicy`: decides which worker resource universe the work
    competes in, including WorkerGroup selector, route, target override, and
    pool constraints.
  - `RuntimeWorkerSelection`: selects a concrete worker inside the resolved
    worker universe from live online/presence/load/admission/draining/lease
    evidence.
- Task scheduling strategies such as `LOW_LATENCY`, `BULK_THROUGHPUT`,
  `FAIR_SHARE`, or future `SLA_DEADLINE` modes are cross-project platform
  capabilities. Worker scheduling strategies such as `DEDICATED_GROUP`,
  `TARGETED_GROUP`, `REGIONAL_ROUTE`, or `CAPABILITY_POOL` are also
  cross-project platform capabilities. A project should bind and configure
  allowed policies; it should not own the scheduling algorithms themselves.
- Existing names such as `STANDARD`, `INTERACTIVE`, and `BULK` are current
  runtime profile / workload tuning vocabulary. PSP-0 must classify whether
  each maps to a future policy, remains runtime tuning, or becomes binding
  config. Do not assume they are final scheduling policy names.
- Current task creation and runtime use task-level fields and configuration
  such as `project`, `workloadClass`, `executionSpec`, `sharedConfig`, and
  explicit `workerGroupId(s)` / selector inputs.
- WorkerGroup capability is worker-runtime truth outside the Scheduling Plane.
  It constrains worker scheduling resolution and handler validation. Worker
  rows are execution slots and evidence carriers, not project/event capability
  truth.
- Current matching starts from explicit WorkerGroup selectors, validates
  WorkerGroup capability, enumerates worker scheduling candidates, evaluates
  rule-backed eligibility, ranks candidates, and reserves/adopts admission
  evidence. This two-stage match is the current worker-selection mechanism, not
  the definition of either task scheduling policy or worker scheduling policy.
- Current `WorkerMatchContext` is a legacy flat rule/diagnostic map. It exposes
  task intent fields, WorkerGroup/capability facts, worker scheduling facts,
  live reachability/load/admission evidence, and derived booleans through one
  rule-readable shape. PSP-0 must classify every key before any policy contract
  is introduced.
- `eventCode` is handler/capability identity. It validates selected
  WorkerGroup capability and drives worker-local handler dispatch. It is not a
  worker selector and item payload is not worker-selection policy.
- Current scheduling-like behavior is spread across:
  - task runtime profile / workload class
  - task execution spec
  - assignment allocation policy
  - assignment refill policy
  - minimum / maximum worker gates
  - batch size and lane policy
  - matching rule-set provider and evaluator
  - explicit WorkerGroup selector inputs
  - `routingCode` / route attributes
  - `targetWorkerId` / target worker attributes
  - runtime-ready dispatch policy
  - queue/backpressure/admission behavior
  - retry, delay, and lease timing where they affect dispatch cadence

## Owner Review

1. **Scheduling Plane is the top-level owner, not Scheduling Policy.**
   Scheduling policy refers only to `TaskSchedulingPolicy` and
   `WorkerSchedulingPolicy`. `RuntimeWorkerSelection` is a first-class runtime
   owner inside Scheduling Plane, not a policy family and not a detail of
   `WorkerSchedulingPolicy`.

2. **Scheduling policy is platform-level, not project-local.**
   Projects should not each grow their own scheduling algorithm. They should
   bind reusable policies, choose defaults, and supply scoped configuration.

3. **TaskSchedulingPolicy, WorkerSchedulingPolicy, and RuntimeWorkerSelection
   must stay separate.**
   Task scheduling policy controls competition admission, cadence, priority,
   quota, fairness, and task-level budget. Worker scheduling policy controls
   the worker resource universe, WorkerGroup selector, route, target override,
   and worker-pool constraints. Runtime worker selection controls live worker
   evidence, ranking, reservation, locks, and admission result.

4. **Project/workload binding is not runtime truth.**
   Binding defines what a project/workload may use and what it defaults to.
   Runtime truth remains in task lifecycle, `TaskWorkRuntime`,
   `TaskResultRuntime`, worker-runtime admission/evidence, and trace/audit.

5. **TaskDispatchIntent remains task-level.**
   A task selects allowed task/worker scheduling policies or inherits the
   project/workload defaults, then narrows actual dispatch through explicit
   group selector, route, target worker override, and target attributes.

6. **WorkerGroupCapability remains capability truth.**
   Worker scheduling policy may allow or prefer groups, but WorkerGroup declares
   which projects/events it can actually serve.

7. **Runtime policy execution owns stateful cost.**
   If a task scheduling policy requires quota ledgers, fairness counters,
   priority queues, deadline/SLA state, or cost budgets, that state belongs to a
   runtime policy execution owner, not to project binding or task payload.
   Worker scheduling policy should not absorb live worker evidence or admission
   state.

8. **RuntimeWorkerSelection remains the third owner, not a worker policy detail.**
   No worker scheduling policy can bypass online/presence/load/admission/
   draining/lease checks inside the selected group. Those checks, rank weights,
   reservation, locks, and admission result belong to runtime worker selection.

9. **Two-stage match is mechanism, not policy ownership.**
   Task and worker scheduling policies resolve the competition and resource
   boundaries. The current two-stage match executes candidate acquisition,
   filtering, ranking, and reservation inside those boundaries.

10. **Matching rules are not the strategy owner.**
   Rule sets can be referenced by worker scheduling policy, but rule DSL must
   not become the hidden place where platform scheduling ownership lives.

11. **Item payload is outside scheduling ownership.**
   Item payload and `eventCode` choose handler invocation and input. They must
   not become worker-selection policy.

12. **`WorkerMatchContext` is not the future policy contract.**
   It is the current rule/diagnostic snapshot. Any future rule-readable context
   must be narrowed so declarative task/worker policy inputs do not silently
   absorb live runtime evidence such as reachability, load, lease, lock, reserve,
   or admission state. The expected default is that declarative rules do not
   read live load, lease, reserve, lock, or admission evidence; exceptions must
   be named and justified by PSP-1.

13. **Task scheduling policy may parameterize runtime cadence, but it does not
   own runtime work truth.**
   `TaskWorkRuntime` remains the hot-path owner for ready work, delayed
   visibility, active leases, retry scheduling, retry budget, lease expiry
   indexes, and queue/backpressure truth. Task scheduling policy may resolve
   options consumed by that owner; it must not become a second queue/lease owner.

14. **Task scheduling policy may parameterize worker gates, but assignment
   policy enforces them.**
   Min/max worker budget and minimum-start-gate values may be task scheduling
   policy parameters. The act of holding, dispatching, retrying, releasing, or
   refilling assignment remains owned by assignment/runtime mechanisms such as
   `AssignmentAllocationPolicy`, assignment refill, runtime-ready recovery, and
   worker admission.

## Boundary Decision

Target owner and input shape:

```text
Definition / binding inputs
  SchedulingPolicyCatalog
  - platform-level reusable policy definitions / modes
  - owns two policy families:
      TaskSchedulingPolicyDefinition
      WorkerSchedulingPolicyDefinition
  - task examples: LOW_LATENCY, BULK_THROUGHPUT, FAIR_SHARE, SLA_DEADLINE
  - worker examples: DEDICATED_GROUP, TARGETED_GROUP, REGIONAL_ROUTE,
    CAPABILITY_POOL
  - defines policy family, shape, and cost class

  ProjectSchedulingBinding
  - project/workload allowed task scheduling policies
  - project/workload default task scheduling policy
  - project/workload allowed worker scheduling policies
  - project/workload default worker scheduling policy
  - per-policy config overrides
  - quota/fairness scope, if task scheduling policies need one

  TaskDispatchIntent
  - selected taskSchedulingPolicyRef or inherited default
  - selected workerSchedulingPolicyRef or inherited default
  - WorkerGroup selector
  - routingCode / route attributes
  - optional targetWorkerId
  - optional targetWorkerAttributes

Resolved engine-facing views consumed by runtime owners
  ResolvedTaskSchedulingPolicy
  - engine-facing task competition view
  - produced from catalog + project/workload binding + task dispatch intent
  - does not execute queue, lease, retry, expiry, or backpressure truth
  - input to TaskSchedulingPolicyExecution

  ResolvedWorkerSchedulingPolicy
  - engine-facing worker resource-universe view
  - produced from catalog + project/workload binding + task dispatch intent
  - does not own live worker evidence or admission
  - input to WorkerSchedulingPolicyResolution

Scheduling Plane runtime owners
  TaskSchedulingPolicyExecution
  - consumes ResolvedTaskSchedulingPolicy
  - applies task scheduling policy behavior at runtime
  - owns stateful ledgers/counters/queues only when the task policy cost
    requires it

  WorkerSchedulingPolicyResolution
  - consumes ResolvedWorkerSchedulingPolicy and TaskDispatchIntent
  - resolves WorkerGroup/resource-pool scope and worker eligibility constraints
  - consumes WorkerGroupCapability as an external capability constraint
  - does not own live worker evidence, worker admission, or result convergence

  RuntimeWorkerSelection
  - consumes the already resolved worker universe plus live runtime evidence
  - selects concrete workers after task and worker scheduling policies resolve
  - owns live online/presence/load/admission/draining/lease evidence, ranking,
    reserve/lock, and admission result
  - is not stored in the policy catalog and is not project binding
```

External constraints:

```text
WorkerGroupCapability
  - group-level project/event capability truth
  - validates selected project/event support and group defaults
  - constrains WorkerSchedulingPolicyResolution
  - is not policy catalog, project binding, or runtime worker selection
```

`Scheduling Plane` is a conceptual cross-layer owner boundary, not a package,
module, or new god service. A future implementation may introduce specific
contracts or adapters after PSP-1, but it must not collect task policy, worker
policy, runtime worker selection, catalog, binding, runtime queues, and DB
storage into one module merely because they all participate in scheduling.

The engine should eventually consume `ResolvedTaskSchedulingPolicy`,
`ResolvedWorkerSchedulingPolicy`, and `TaskDispatchIntent`, not a storage entity
and not a generic `sharedConfig` bag. SDK/server/control-plane assembly may own
catalog/binding persistence and resolution if PSP-1 decides persistence is
required.

Resolved policy views are intermediate runtime inputs. They are not persistence
truth and not standalone owners: task scheduling execution consumes the resolved
task view, worker scheduling resolution consumes the resolved worker view, and
runtime worker selection consumes only the already resolved worker universe plus
live evidence/admission state.

Scheduling Plane should not own:

- task runtime truth
- per-item payload interpretation
- WorkerGroup project/event capability truth
- worker runtime evidence or admission state
- result convergence
- transport delivery
- trace/audit history
- the two-stage match mechanism itself

## Owner And Cost Classification

Every Scheduling Plane related input must first be classified by exactly one
normative owner class:

| Owner Class | Meaning | Example |
| --- | --- | --- |
| `task-scheduling-policy` | controls whether/when work enters competition and with what task-level budget or fairness | low-latency admission, bulk throughput cadence, retry/delay cadence, project fair-share |
| `worker-scheduling-policy` | controls worker resource universe and pool constraints before live worker selection | dedicated group, targeted group, route attributes, target worker override policy |
| `runtime-worker-selection` | selects concrete workers from live evidence inside the resolved worker universe | online/presence/load/draining/lease checks, ranking, reserve/lock |
| `project-workload-binding` | chooses allowed/default policy and scoped configuration but does not execute policy | project defaults, allowed policies, scoped quota/fairness config |
| `task-dispatch-intent` | task-level dispatch target or explicit override | selected policy refs, WorkerGroup selector, route, target worker |
| `worker-group-capability` | external project/event capability truth that constrains worker scheduling | event binding, project binding, group defaults |
| `runtime-admission-backpressure` | runtime execution gate or capacity/backpressure truth outside policy definition | admission result, queue pressure, capacity unavailable |
| `engine-lifecycle-repair` | lifecycle, repair, watchdog, or maintenance behavior that is not scheduling policy | repair pump, lease watchdog trigger, runtime-ready recovery |
| `matching-evaluator-input` | declarative rule/evaluator input after PSP-1 explicitly allows it | narrowed eligibility context field |
| `trace-diagnostic-evidence` | observation-only evidence that must not drive policy | assignment trace field, diagnostic snapshot |
| `not-scheduling-policy` | belongs elsewhere | item payload, result finality, transport delivery |

The introductory categories map into the normative classes above:

| Introductory category | Normative classes |
| --- | --- |
| `TaskSchedulingPolicy` | `task-scheduling-policy` |
| `WorkerSchedulingPolicy` | `worker-scheduling-policy` |
| `RuntimeWorkerSelection` | `runtime-worker-selection` |
| Inputs / constraints | `project-workload-binding`, `task-dispatch-intent`, `worker-group-capability` |
| Runtime and non-policy support | `runtime-admission-backpressure`, `engine-lifecycle-repair`, `matching-evaluator-input`, `trace-diagnostic-evidence`, `not-scheduling-policy` |

Then every Scheduling Plane related input must be classified by cost:

| Cost Class | Meaning | Example |
| --- | --- | --- |
| `preset-only` | pure strategy parameters, no new runtime state | low-latency defaults, bulk-throughput defaults, default selector |
| `resolved-view` | needs catalog/binding/task resolution but no mutable runtime ledger | selected worker policy, selected task policy, default rule set |
| `runtime-stateful` | needs mutable runtime state | fair-share counters, quota ledger, project priority queues |
| `storage-backed` | needs durable control-plane record | project/workload binding, named policy config |
| `not-policy` | belongs elsewhere | item payload, result finality, worker capability truth |

## Hard Rules

1. Do not introduce a class or record named `ProjectSchedulingPolicy` as the
   main owner. The intended name is `ProjectSchedulingBinding`;
   `ProjectSchedulingPolicy` conflates project binding with platform policy
   definitions.
2. Do not introduce `SchedulingPolicyCatalog`, `ProjectSchedulingBinding`, or
   resolved task/worker scheduling policy classes before PSP-1 owner decision.
3. PSP-0 is inventory/classification only. No code movement.
4. Every current scheduling-like input must receive exactly one owner
   classification and one cost classification.
5. Do not collapse `TaskSchedulingPolicy`, `WorkerSchedulingPolicy`, and
   `RuntimeWorkerSelection` into one policy bag.
6. Do not describe `RuntimeWorkerSelection` as a scheduling policy family. It
   is first-class inside Scheduling Plane, but it is a runtime selection owner.
7. Existing `STANDARD`, `INTERACTIVE`, `BULK`, and `workloadClass` vocabulary
   are inventory subjects, not final policy names.
8. Do not move item payload or `eventCode` into scheduling policy.
9. Do not let worker rows become project/event capability truth.
10. Do not make engine depend on DB/control-plane storage to fetch policy.
11. Do not encode policy ownership in rule DSL alone.
12. Do not preserve two live policy tracks after convergence. Update in-repo
   callers rather than adding compatibility aliases.
13. Do not create a broad `scheduling` module/package just to gather unrelated
   policy, runtime, storage, and selection code. `Scheduling Plane` is an owner
   boundary, not an implementation bucket.
14. Do not treat `WorkerMatchContext` as the future resolved policy contract.
   It must first be split or narrowed by PSP-0/PSP-1 classification.
15. Do not let task scheduling policy own queue, lease, retry, expiry, or
   backpressure truth. `TaskWorkRuntime` remains the runtime execution owner.
16. Do not add persisted catalog/binding tables before PSP-1 proves that
   assembly defaults or computed resolved views are insufficient.
17. Do not let declarative rule inputs read live worker load, lease, reserve,
   lock, or admission evidence by default. Such fields belong to
   `RuntimeWorkerSelection` unless PSP-1 names and proves an exception.
18. Do not let task scheduling policy enforce min/max worker gates, assignment
   holds, refills, releases, or dispatch retries. It may resolve parameters;
   assignment/runtime mechanisms execute them.
19. Do not let PSP-1 defer the rule-readable live-evidence boundary. PSP-1
   must decide which current `WorkerMatchContext` fields are declarative,
   runtime-selection evidence, diagnostic-only, or explicitly allowed
   exceptions before PSP-3/PSP-4 can retarget policy behavior.

## Non-Goals

1. No scheduling behavior change in PSP-0/PSP-1.
2. No public SDK/API compatibility promise for internal policy names.
3. No rule-engine replacement.
4. No worker-runtime ownership expansion into task scheduling decisions.
5. No new DB-backed policy table until PSP-1 decides persistence ownership.
6. No item-level matching or per-item policy execution.
7. No change to result convergence, transport delivery, or worker command
   lifecycle.

## PSP-0 Inventory And Classification

Goal: classify all current Scheduling Plane related inputs before creating any
new owner.

Scope:

- Create the PSP-0 inventory, now archived at
  `doc/archive/xa-mass-engine/2026-06-03_PLATFORM_SCHEDULING_PLANE_INVENTORY.md`.
- Inventory current source and tests for:
  - `TaskRuntimeProfile`
  - `Task.workloadClass`
  - `TaskExecutionSpec`
  - SDK/public-contract task execution options
  - SDK/public-contract shared config keys
  - server task create/update DTOs and controller mapping
  - task `sharedConfig` keys used for dispatch or routing
  - assignment allocation policy
  - assignment refill policy
  - min/max worker gates
  - batch size and lane policy
  - matching rule-set provider / evaluator inputs
  - every `WorkerMatchContext` key and every rule-readable field
  - WorkerGroup selector inputs
  - `workerGroupId` / `workerGroupIds`
  - `routingCode` / route attributes
  - `targetWorkerId`
  - target worker attributes
  - runtime-ready dispatch pump policy
  - retry/delay/lease timing that affects dispatch cadence
  - queue/backpressure/admission knobs
  - trace fields that expose scheduling decisions
- For min/max worker gates and worker budget fields, record:
  - which value is a task scheduling policy parameter
  - which component currently enforces the gate or budget
  - whether enforcement belongs to assignment allocation, refill,
    runtime-ready recovery, worker admission, or diagnostics
  - whether any mutable state is involved
- For every `WorkerMatchContext` key, record:
  - key name
  - source method / owner object
  - its normative owner class from the Owner And Cost Classification table
  - whether rule evaluation may continue reading it
  - whether it should move to a declarative policy context, runtime selection
    evidence view, diagnostic-only snapshot, or be removed
- Classify each item as exactly one normative owner class from the Owner And
  Cost Classification table:
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
- Classify each item by cost:
  - `preset-only`
  - `resolved-view`
  - `runtime-stateful`
  - `storage-backed`
  - `not-policy`
- Identify current tests and trace analyzers that would break if the item
  moved.
- Identify whether the item is persisted, computed, task-level, group-level,
  runtime-only, or trace-only.
- Separate internal runtime tuning vocabulary from external API vocabulary.
  Public SDK/server fields may remain stable caller vocabulary even when the
  internal owner changes.

Acceptance:

- Inventory accounts for every current scheduling-like input found by source
  search.
- No code behavior changes.
- Every item has one owner classification, one cost classification, and a
  short rationale.
- Inventory explicitly groups items by the normative owner classes above and
  then separately calls out which items are candidates for
  `TaskSchedulingPolicyDefinition`, `WorkerSchedulingPolicyDefinition`,
  `ProjectSchedulingBinding`, `TaskDispatchIntent`, `WorkerGroupCapability`,
  `RuntimeWorkerSelection`, and runtime/admission internals.
- Inventory identifies all known `sharedConfig` keys that currently affect
  routing or scheduling.
- Inventory lists SDK/public-contract/server surfaces that expose workload,
  routing, group selection, target worker, or Scheduling Plane related
  configuration.
- Inventory includes a `WorkerMatchContext` key table with one owner
  classification, one cost classification, rule-readable decision, and
  migration recommendation per key.
- Inventory explicitly separates declarative rule inputs from live runtime
  selection/admission evidence.
- Inventory explicitly separates min/max worker gate parameters from the
  assignment/runtime mechanisms that enforce hold, dispatch, refill, release,
  or retry behavior.
- Inventory names where `TaskWorkRuntime` currently owns ready/delayed/lease/
  retry/expiry/backpressure truth and which policy inputs merely parameterize
  that owner.

## PSP-1 Owner, Binding, And Persistence Decision

Goal: decide what the policy catalog, project/workload binding, resolved task
policy view, resolved worker policy view, and runtime worker selection are
allowed to own.

Scope:

- Decide whether policy catalog and project/workload binding are:
  - persisted control-plane data
  - SDK/server assembly defaults
  - computed read views
  - engine-local resolved views
  - some combination with one canonical truth per layer
- Decide module ownership:
  - policy catalog owner
  - project/workload binding owner
  - SDK/server assembly resolver
  - engine-facing resolved task scheduling policy contract
  - engine-facing resolved worker scheduling policy contract
  - task scheduling policy execution owner for stateful task policies
  - runtime worker selection owner for live evidence/rank/reserve/admission
  - public SDK request/snapshot exposure, if any
- Decide how binding references task scheduling policy:
  - allowed task scheduling policies
  - default task scheduling policy
  - task scheduling config overrides
  - workload/runtime profile defaults
  - backpressure/fairness/priority/quota policy
- Decide how binding references worker scheduling policy:
  - allowed worker scheduling policies
  - default worker scheduling policy
  - allowed WorkerGroups
  - default WorkerGroup selector
  - matching rule-set reference
- Decide task override rules:
  - which task scheduling policies a task may select
  - which worker scheduling policies a task may select
  - what task dispatch intent may override for task scheduling
  - what task dispatch intent may override for worker scheduling
  - what ProjectSchedulingBinding may reject
  - what WorkerGroup capability may reject
  - what runtime admission may always reject
- Decide whether task creation, task approval, or runtime assignment resolves
  the current policy view.
- Decide whether a named `SchedulingPolicyResolver` is needed. If it is needed,
  it is an assembly/resolution component that produces
  `ResolvedTaskSchedulingPolicy` and `ResolvedWorkerSchedulingPolicy` from
  catalog/defaults, project/workload binding, and `TaskDispatchIntent`. It is
  not itself a policy owner, runtime selector, or storage truth.
- Decide the rule-readable context boundary:
  - which fields may remain in a declarative worker policy / eligibility
    context
  - which fields must move to runtime worker selection evidence
  - which fields are diagnostic-only and must not drive rule decisions
  - the expected default is that live load/lease/reserve/lock/admission facts
    are excluded from declarative matching rules and stay in
    `RuntimeWorkerSelection` or admission
  - any exception that lets a rule read live evidence must name the field, the
    policy need, the caller/proof evidence, and why ranking/admission cannot own
    the decision instead
  - this decision is mandatory in PSP-1 and must not be deferred to PSP-3 or
    PSP-5; later slices may defer mechanical cleanup only for fields that PSP-1
    has already classified and explicitly allowed
- Decide the min/max worker gate boundary:
  - which values are task scheduling policy parameters
  - which current or future assignment/runtime component enforces the start
    gate, max worker budget, hold, refill, release, and dispatch retry behavior
  - whether any stateful policy ledger is needed, or whether existing
    assignment/runtime mechanisms remain the only execution owner
- Decide whether PSP-2 should create separate names for:
  - declarative policy eligibility context
  - runtime worker selection evidence
  - assignment diagnostics snapshot
- Decide the default first implementation cost: assembly defaults / computed
  resolved views first unless PSP-1 identifies named concrete policy needs plus
  caller/proof evidence that require persisted catalog or runtime-stateful
  ledgers.

Acceptance:

- Decision document or updated roadmap section declares one owner for each
  layer: catalog, project/workload binding, resolved task scheduling view,
  resolved worker scheduling view, task policy execution, and runtime worker
  selection.
- Engine-facing contracts are resolved views, not DB/storage entities.
- Task dispatch intent task-policy selection, worker-policy selection, and
  override rules are explicit.
- WorkerGroup capability remains authoritative for project/event support.
- Runtime admission remains authoritative for current execution eligibility.
- No implementation class is introduced without the owner decision.
- Rule-readable live evidence policy is explicit; no future implementation may
  infer it from the legacy `WorkerMatchContext` shape.
- Rule-readable live evidence is excluded by default. Any exception is recorded
  with named fields, policy need, caller/proof evidence, and owner rationale.
- PSP-1 makes a final rule-readable context boundary decision. It may approve
  named exceptions, but it may not leave unclassified live evidence for PSP-3
  or PSP-5 to decide.
- Min/max worker gate parameter ownership and enforcement ownership are
  explicitly separated.
- The decision records that the default first implementation is assembly
  defaults or computed resolved views unless persisted/stateful cost is
  justified by named policy needs and caller/proof evidence.

## PSP-2 Contract Shape And Resolution Path

Goal: define minimal contracts after PSP-1 decides ownership.

Scope:

- Introduce only the necessary value/contract names, for example:
  - `SchedulingPolicyCatalog`
  - `TaskSchedulingPolicyDefinition`
  - `WorkerSchedulingPolicyDefinition`
  - `ProjectSchedulingBinding`
  - `ResolvedTaskSchedulingPolicy`
  - `ResolvedWorkerSchedulingPolicy`
  - `SchedulingPolicyResolver`
  - `TaskDispatchIntent`
  - `TaskSchedulingPolicyExecution`
  - `WorkerSchedulingPolicyResolution`
  - `RuntimeWorkerSelection`
- Keep names if PSP-1 chooses different ones, but preserve the boundary.
- If `SchedulingPolicyResolver` is introduced, keep it on the side selected by
  PSP-1 ownership: SDK/server/control-plane assembly when persistence/config is
  outside engine, or engine-local assembly only if PSP-1 explicitly keeps
  resolved views engine-local. It must produce resolved views for engine
  consumption and must not make engine import storage/control-plane modules.
- Define how a task obtains resolved task and worker scheduling views:
  - task create time
  - task approve time
  - assignment time
  - hybrid with immutable task dispatch intent plus current policy view
- Define how catalog, binding, selected task policy, selected worker policy,
  task intent, and runtime worker selection are represented in trace.
- If PSP-1 keeps rules in the path, define separate contract shapes for
  declarative rule input and runtime worker selection evidence, or explicitly
  document why one narrowed shape is still safe.
- Define how `TaskSchedulingPolicyExecution` passes cadence/budget options to
  `TaskWorkRuntime` without taking ownership of queue, lease, retry, expiry, or
  backpressure truth.
- Define how `TaskSchedulingPolicyExecution` passes min/max worker budget and
  minimum-start-gate parameters to assignment allocation/refill/runtime-ready
  mechanisms without taking ownership of assignment holds, refills, releases,
  dispatch retries, or worker admission.
- Define how `WorkerSchedulingPolicyResolution` consumes
  `ResolvedWorkerSchedulingPolicy`, `TaskDispatchIntent`, and
  `WorkerGroupCapability` to produce a worker universe / eligibility constraint
  view without consuming live reachability, load, lease, reserve, lock, or
  admission evidence.

Acceptance:

- Contracts compile without changing scheduling behavior.
- Engine consumes only resolved task/worker scheduling and task-intent
  contracts.
- Contracts do not expose item payload as policy input.
- Contracts do not require engine production to import storage/control-plane
  modules.
- Contracts do not expose live worker selection/admission evidence as
  declarative policy input unless PSP-1 explicitly allowed that field class.
- Contracts preserve `TaskWorkRuntime` as the runtime work truth owner.
- Contracts preserve `WorkerSchedulingPolicyResolution` as resource-universe
  and eligibility-constraint resolution only; live worker evidence remains in
  `RuntimeWorkerSelection`.
- Contracts preserve assignment/runtime mechanisms as the enforcement owner for
  min/max worker gates, refills, releases, dispatch retries, and worker
  admission.

## PSP-3 Retarget Existing Defaults

Goal: move selected defaults behind the resolved scheduling policy without
changing behavior.

Scope:

- Retarget only items classified in PSP-0 and approved in PSP-1.
- Likely task scheduling candidates:
  - default task scheduling policy
  - default priority/fairness/quota/backpressure reference
  - default runtime profile or workload tuning reference
  - task-level min/max worker budget parameters, if PSP-1 classifies the
    values as task scheduling policy parameters
- Likely worker scheduling candidates:
  - default worker scheduling policy
  - default allowed WorkerGroups
  - default group selector
  - default route/routing attribute policy
  - default matching rule-set reference
- Preserve task-level explicit selectors and overrides.
- Preserve runtime/admission rejection behavior.
- Preserve assignment allocation/refill enforcement behavior unless PSP-1
  explicitly selected a behavior-changing policy implementation path.
- Retarget current prefilter/rule/rank/reserve behavior only after PSP-0
  classified each condition as declarative eligibility, runtime worker
  selection evidence, admission/backpressure, or diagnostics.
- Treat rule context narrowing as an explicit PSP-3 sub-scope:
  - PSP-3 may narrow or split the current rule-readable context only for fields
    classified by PSP-0 and approved by PSP-1.
  - PSP-3 may defer mechanical removal only for fields PSP-1 explicitly
    classified and allowed as remaining rule-readable inputs.
  - PSP-3 must not carry unclassified live evidence forward as a PSP-5 cleanup
    item. Unclassified or owner-ambiguous fields block PSP-4 policy
    implementation.
- Do not rename `RuleBasedTaskWorkerMatchingStrategy` into a policy owner. If
  it remains, describe it as the current runtime worker selection mechanism.

Acceptance:

- Existing scheduling tests pass unchanged or with assertion updates only for
  renamed fields.
- Explicit task dispatch intent still overrides policy/binding defaults only
  where PSP-1 allowed it.
- Project/workload binding rejects a task-selected task or worker policy that
  is not allowed.
- WorkerGroup capability can reject a policy or task intent that references an
  unsupported event/project.
- Trace shows catalog policy, project/workload binding, selected task policy,
  selected worker policy, and task-level override when more than one layer
  contributes to the resolved view.
- Live reachability/load/lease/reserve/lock/admission checks remain in runtime
  worker selection or admission, not in worker scheduling policy.
- Min/max worker gate values may be resolved from task scheduling policy, but
  gate enforcement remains in assignment/runtime mechanisms.
- Rule-backed eligibility no longer receives unclassified live evidence through
  a catch-all context map.
- Any remaining rule-readable live evidence is a PSP-1-approved, named
  exception with owner rationale and proof evidence. No unclassified
  `WorkerMatchContext` field remains as a PSP-5 decision item.

## PSP-4 Selected Policy Implementation Path

Goal: implement only the policy path selected by PSP-1 after contract shape is
stable.

Scope:

- PSP-4 is not a promise to implement all possible branches in one PR. It
  implements only the PSP-1 selected path. If PSP-1 selects multiple unrelated
  cost classes, split PSP-4 into named sub-slices or successor roadmaps.
- PSP-4 must not begin if any rule-readable `WorkerMatchContext` field that can
  affect the selected policy path remains unclassified or owner-ambiguous.
- If PSP-1 selected persisted catalog/binding:
  - add control-plane storage/API/SDK surfaces in the owner module
  - keep engine consuming resolved view only
- If PSP-1 selected assembly defaults:
  - add SDK/server configuration path only when PSP-1 names a concrete caller
    or second policy variant that needs external selection
  - if PSP-1 selects computed defaults only, document PSP-4 as deferred and
    keep the implementation in resolved views without adding a writable config
    surface
  - keep storage out of policy truth
- If PSP-1 selected runtime-stateful policies:
  - add runtime owner for the specific ledger/counter/queue
  - keep state scoped by policy cost, for example project, tenant, workload, or
    WorkerGroup
- Add validation for:
  - referenced scheduling policy exists
  - selected task scheduling policy is allowed by project/workload binding
  - selected worker scheduling policy is allowed by project/workload binding
  - referenced WorkerGroups exist
  - referenced events are supported by selected WorkerGroups
  - referenced rule-set exists, if rule-set reference is enabled
  - unsupported task override is rejected or falls back according to binding

Acceptance:

- PSP-4 scope explicitly names which PSP-1-selected branch is being
  implemented.
- If PSP-1 selected computed defaults only, PSP-4 explicitly records that no
  new policy configuration surface is introduced in this roadmap.
- Engine production has no storage dependency for scheduling policy.
- Policy validation fails before runtime dispatch where possible.
- Stateful task policy runtime has one owner and does not duplicate admission
  truth.
- Worker scheduling policy does not absorb runtime worker selection evidence,
  ranking, reserve, locks, or admission result.
- No duplicate policy truth exists across storage, SDK config, engine resolved
  view, and runtime state.
- Selected policy implementation does not depend on unresolved or unclassified
  catch-all `WorkerMatchContext` fields.

## PSP-5 Guards, Proof, And Residue Scan

Goal: prevent policy ownership from decaying into bags or item-level matching.

Scope:

- Add minimum automated guards:
  - no engine production dependency on policy storage/control-plane module
  - no item payload access in policy resolution
  - no `eventCode -> all workers` matching path
  - no worker row project/event capability truth
  - no worker scheduling policy class owns live worker selection evidence,
    ranking, reserve, locks, or admission result
  - no declarative policy/rule context exposes unclassified live worker
    selection evidence
  - no task scheduling policy class owns `TaskWorkRuntime` queue/lease/retry/
    expiry/backpressure truth
  - no new scheduling-like `sharedConfig` keys without owner classification
  - no class named `ProjectSchedulingPolicy` acting as the root owner
  - no broad `scheduling` module/package that owns both policy and runtime
    selection/storage truth
- Add process / residue checks for areas that cannot be enforced cleanly by
  source guard:
  - stale docs that describe `ProjectSchedulingPolicy` as implemented
  - stale examples that imply `eventCode` or item payload selects workers
  - duplicate policy names that preserve old and new owner tracks together
- Add a policy-truth ownership proof:
  - for every policy fact introduced or retargeted by PSP-4, name exactly one
    truth owner
  - classify all other appearances as request input, resolved view, runtime
    parameter, trace evidence, or diagnostic copy
  - for source-detectable writable owners, add an automated guard that fails
    when a second writable owner is introduced for the same policy fact
  - identify whether an automated guard can fail on a second writable owner
  - document any process-only check that cannot be expressed as a stable source
    guard
- Update:
  - `AGENTS.md`
  - `doc/AGENT_BASELINE.md`
  - `doc/TASK_LIFECYCLE_BASELINE.md`
  - `xa-mass-engine/README.md`
  - `xa-mass-worker-runtime/README.md` / `CONTRACTS.md`
  - `doc/PROOF_REGISTRY.md` if new proof lanes are added
  - `doc/TESTING_INDEX.md` if suites move
- Run roadmap residue scan for old policy names and stale target/current
  wording.

Acceptance:

- Minimum automated guards fail for item-level worker selection, worker-row
  capability truth, worker-policy ownership of runtime selection, engine
  policy-storage imports, and root `ProjectSchedulingPolicy` ownership.
- Guards or residue scan fail when new rule/policy context fields are added
  without owner classification.
- Guards or residue scan fail when task policy classes write directly to
  `TaskWorkRuntime` internals or duplicate queue/lease/retry ownership.
- PSP-5 includes a policy-truth ownership matrix covering storage/control-plane
  records, SDK/server config, engine resolved views, runtime state, trace, and
  diagnostics for every PSP-4 policy fact.
- Guards or residue scan fail when a policy fact has two writable truth owners
  or when a duplicate owner track is preserved without a PSP-1 decision.
- Docs distinguish current implementation from target policy owner.
- Residue scan finds no stale statement that a single
  `ProjectSchedulingPolicy` is the implemented root owner.

## Risks

| Risk | Responsible slice | Impact | Mitigation |
| --- | --- | --- | --- |
| Project binding becomes a project-local algorithm bag | PSP-1 | policy reuse disappears and project records absorb runtime strategy | keep reusable strategy in `SchedulingPolicyCatalog`; binding only selects/configures |
| Policy catalog becomes a static enum too early | PSP-0 / PSP-1 | future stateful policies need another migration | classify policy cost in PSP-0 and separate preset-only from runtime-stateful |
| Engine consumes persisted policy directly | PSP-2 / PSP-5 | storage/control-plane dependency returns to engine | resolved view contract only; guard engine production imports |
| Task and worker policy collapse into one policy bag | PSP-0 / PSP-1 | task lifecycle cadence and worker-pool constraints become hard to reason about | classify every input by normative owner class |
| Worker scheduling policy absorbs runtime worker selection | PSP-1 / PSP-3 / PSP-5 | live evidence, ranking, reserve, and admission become hidden inside a static policy owner | keep `RuntimeWorkerSelection` as the third owner and guard policy classes from owning live selection state |
| Legacy `WorkerMatchContext` becomes the future policy contract | PSP-0 / PSP-1 / PSP-5 | rule DSL keeps seeing mixed declarative and live evidence fields, so owner split decays immediately | PSP-0 key-by-key inventory; PSP-1 rule-readable boundary decision; guard unclassified context fields |
| Rule-readable live evidence remains open by default | PSP-1 / PSP-3 / PSP-5 | current load/lease/admission fields keep pulling runtime worker selection back into rule DSL | default to declarative-only rules; require named proof for exceptions |
| Scheduling Plane becomes a new catch-all module | PSP-1 / PSP-2 / PSP-5 | policy, runtime selection, DB storage, and runtime queues get centralized by name rather than ownership | keep plane as conceptual owner boundary; introduce only PSP-1-approved contracts |
| Task scheduling policy duplicates `TaskWorkRuntime` | PSP-1 / PSP-2 / PSP-5 | queue/lease/retry/expiry truth splits across policy and runtime | policy resolves options only; `TaskWorkRuntime` remains execution truth |
| Task scheduling policy absorbs assignment gate execution | PSP-0 / PSP-1 / PSP-3 | min/max worker gate values drag hold/refill/release/retry execution state into policy owner | classify value vs enforcement separately; keep assignment/runtime mechanisms as enforcement owners |
| Persisted policy catalog appears before policy cost is proven | PSP-1 / PSP-4 | DB/control-plane work lands before there is caller/proof evidence requiring it | default to assembly defaults or computed views until PSP-1 names concrete persisted/stateful need |
| Duplicate policy truth survives implementation | PSP-4 / PSP-5 | storage, SDK config, engine resolved views, and runtime state can diverge on the same policy fact | PSP-5 policy-truth ownership matrix plus guards/residue scan for second writable owners |
| Rule DSL becomes hidden policy owner | PSP-0 / PSP-1 / PSP-3 | strategy decisions become opaque and hard to reason about | policy references rule sets but owns default strategy shape |
| WorkerGroup capability is bypassed | PSP-1 / PSP-3 / PSP-5 | unsupported event/project can dispatch | capability validation remains mandatory after policy resolution |
| Runtime admission is bypassed | PSP-1 / PSP-3 / PSP-5 | offline/draining/capacity-exhausted workers receive work | runtime worker selection and admission remain final execution eligibility gate |
| Task overrides become unrestricted | PSP-1 / PSP-4 | task sharedConfig turns into all-purpose policy bag | explicit override matrix in PSP-1 |

## Suggested Implementation Order

1. PSP-0 inventory and classification.
2. PSP-1 owner, binding, and persistence decision.
3. PSP-2 contract shape and resolution path.
4. PSP-3 behavior-neutral retargeting.
5. PSP-4 selected policy implementation path, if approved.
6. PSP-5 guards, proof, and residue scan.

Do not begin PSP-2 until PSP-0 and PSP-1 are complete.

## Verification Candidates

Inventory source search:

```bash
rg -n "TaskRuntimeProfile|workloadClass|TaskExecutionSpec|sharedConfig|workerGroupId|workerGroupIds|routingCode|routeAttributes|targetWorkerId|targetWorkerAttributes|WorkerMatchContext|MatchingRuleSetProvider|AssignmentAllocationPolicy|AssignmentRefillPolicy|backpressure|admission|RuntimeReadyDispatchPump|retryDelay|lease" xa-mass-engine xa-mass-worker-runtime sdk xa-mass-server platform_infra transport -S
```

Likely targeted regression after implementation slices:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime,xa-mass-server,sdk/xa-mass-java-sdk -am "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskDelayedAvailabilitySchedulingTest,RuleBasedTaskWorkerMatchingStrategyTest,WorkerCandidateIndexTest,*Scheduling*IntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```
