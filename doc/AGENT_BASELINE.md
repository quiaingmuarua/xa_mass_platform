# XA Mass Platform Agent Baseline

Status: current global baseline.

This file keeps only stable platform baseline: product definition, owner
boundaries, mainline reality, and hard guardrails. Detailed flows, commands,
module inventories, and executable proof belong in owner docs and runbooks.

## 1. Working Rules

- verify old READMEs, roadmaps, inventories, and architecture notes against
  current code before using them
- update the owning contract doc when code changes documented behavior,
  ownership, or workflow expectations
- keep target-state and staged-refactor writing out of mainline baseline docs
- design guards and verification to protect the target owner model, not the
  historical implementation shape; current fields, DTOs, or protocols may be
  transition evidence, but they must not become sanctioned long-term APIs just
  because existing code still needs them
- when current behavior must remain temporarily during convergence, classify it
  as a legacy bridge with an explicit owner, scope, and removal condition rather
  than encoding it as baseline correctness
- delete stale parallel narratives unless they explain a live operational
  constraint

## 2. Platform Baseline

XA Mass Platform is a distributed task scheduling platform. Its kernel problem
is scheduling task work to heterogeneous, stateful workers, delivering selected
items, accepting results, and converging task state.

Core primitives:

- `Task`: task/control aggregate, intake boundary, shared config, runtime work,
  result convergence, and terminal state
- `WorkerGroup`: capability declaration and scheduling entry boundary
- `Worker`: execution identity plus group membership, attributes, load, state,
  and scheduling evidence
- `Scheduling Plane`: task-side policy execution, worker-universe resolution,
  and concrete runtime worker selection
- `Adapter`: final-hop network/session/endpoint lease carrier
- `Transport`: delivery executor for assigned work, result ingress, and system
  event ingress; it is not a scheduler

Current mainline:

```text
Task shell
  -> item append
  -> runtime enqueue
  -> scheduling eligibility
  -> worker selection and assignment
  -> transport dispatch
  -> result convergence
  -> task state
```

Kernel truth is split across:

- `Task.contract` for public/runtime preset input
- `Task.intakeStatus` for append-window truth
- `TaskWorkRuntime` for ready work, leases, retry, expiry, counters, and
  backpressure truth
- `TaskResultRuntime` and engine result services for stable-final result rows
  and result-side barriers

Runtime seams are transport-neutral: task dispatch, result ingest, and system
events. Runtime entry is SDK-first. `xa-mass-server` is the reference host,
backend product/API shell, control-console support surface, and validation
surface; it must not redefine kernel ownership.

Trace ownership is split: canonical trace writes belong to
`platform_infra/mass-trace-sink`, while operator trace reads and diagnosis
belong to `xa-mass-trace`.

## 3. Owner Vocabulary

- `TaskSchedulingPolicyExecution` owns task-side competition admission,
  cadence, priority, quota/fairness, and task budget execution.
- `WorkerSchedulingPolicyResolution` owns worker resource-universe, group
  selector, route, target override, and pool constraint resolution.
- `RuntimeWorkerSelection` owns concrete worker choice from live worker evidence
  and admission state.
- `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
  `ResolvedWorkerSchedulingPolicy` are engine-facing execution values, not
  storage truth or public policy products.
- `SchedulingPolicyCatalog` and `ProjectSchedulingBinding` are target policy
  product boundaries until a successor decision proves caller, cost, storage
  owner, and runtime consumer.
- `Matching` is the current worker-selection mechanism inside the Scheduling
  Plane, not a top-level owner.
- `WorkerGroupCapability` is group-level capability truth. A worker cannot
  self-declare new project/event capability.
- `eventCode` is handler/capability identity. It validates selected
  WorkerGroup event binding and chooses the worker-local handler invocation; it
  is not a worker selector.
- `Item` is the executable work unit: event code plus input or `payloadRef`. It
  does not own worker-selection policy.
- `selectedWorkerId` is the engine-selected execution target carried into
  transport as an assigned delivery constraint.
- `deliveryQueueKey` is queue/storage/batching partitioning only. Assigned
  delivery correctness comes from `selectedWorkerId` plus endpoint/session
  feasibility, not queue-key uniqueness.
- `routeKey`, `adapterId`, `connectionId`, endpoint lease ids, session handles,
  and delivery queue keys are transport implementation facts. Engine worker
  selection must not consume them directly.
- `Transport` owns delivery-executor mechanics: minimal assigned delivery
  commands, delivery queues/stores, endpoint lease evidence, result ingress,
  and delivery outcomes. It does not own worker capability, worker selection,
  retry policy, or task lifecycle mutation.
- `Adapter` owns one protocol endpoint/session driver and final-hop send/read
  behavior. An adapter may be embedded today or remote later, but it remains a
  connectivity owner; adapter identity, route keys, session handles, and
  endpoint leases must not become worker API or Scheduling Plane inputs.
- `WorkerContext` is retired compatibility vocabulary. Do not reintroduce it as
  worker capability, resource lifecycle, SDK, server, storage, trace, or engine
  scheduling truth.

Do not invert scheduling flow by letting item payload, `eventCode`, worker row
attributes, SDK snapshots, rule DSL, or transport identifiers become scheduling
policy owners.

## 4. Boundary Rules

SDK and API boundaries:

- the stable integration boundary for workers, embedding clients, and external
  automation is the SDK contract surface, not server DTOs and not engine/base
  aggregates
- `xa-mass-base` and `xa-mass-engine` models may evolve quickly; public
  compatibility is preserved through SDK request models and SDK snapshot
  read-models instead of freezing internal kernel types
- SDK snapshots are contract read-models only; engine/runtime logic must not
  consume SDK snapshots as decision input
- before changing `sdk/`, `integrations/`, public Controller DTOs, external
  worker contracts, or server startup registration behavior, read
  [SDK_INTEGRATIONS_BOUNDARY_GUARD.md](./SDK_INTEGRATIONS_BOUNDARY_GUARD.md)

Task and payload boundaries:

- `Task.sharedConfig` plus runtime item payload or `payloadRef` are the generic
  payload boundaries
- `Task.project` and `Task.user` are first-class task truth; do not push them
  back into free-form bags
- task runtime scheduling semantics resolve through explicit scheduling values,
  not directly from free-form `sharedConfig`
- task orchestration and worker selection belong at task or task-slice level;
  do not reintroduce per-message rule matching on the hot path
- protocol fields and wire DTOs must not become business or lifecycle truth

Worker, adapter, and transport boundaries:

- worker registration declares execution identity, WorkerGroup membership, and
  bounded scheduling evidence; capability truth stays with WorkerGroup
- adapters own final-hop connectivity, endpoint lease/session evidence, and
  local send attempts only. They cannot expand capability and cannot choose
  workers for assigned task delivery.
- transport core must stay process-boundary ready: facts that a future remote
  adapter cannot provide through typed delivery, lease, result, or diagnostics
  contracts belong in embedded adapter support, not in transport-neutral APIs.
- transport receives already selected work and delivers to the selected worker.
  Delivery infeasible for the selected worker is engine-owned retry or
  compensation input, not permission for transport to select another worker.
- if delivery reachability affects scheduling, expose it as worker-runtime
  scheduling evidence instead of raw transport facts.

Runtime and query boundaries:

- runtime admission happens through `TaskWorkRuntime`, not through a
  task-message CRUD mainline
- result convergence is runtime-first; verify the split from
  [TASK_LIFECYCLE_BASELINE.md](./TASK_LIFECYCLE_BASELINE.md) plus current
  engine/runtime code before changing it
- bounded message/attempt reads are server/API materialization or audit
  helpers, not the default engine query model
- observability belongs in logs, traces, counters, and bounded diagnostics, not
  scan-heavy hot-path projections

## 5. Current Entry Points

- task shell creation: `POST /api/v1/tasks`
- work-item ingest: `POST /api/v1/tasks/{taskId}/items`
- real Boot entry: `xa-mass-server`
- embedded runtime composition: `xa-mass-embedded-sdk`
- core acceptance lanes:
  - `xa-mass-testing` for perf and Boot-shell E2E
  - `xa-mass-engine` for concurrency and lifecycle-owner proof
  - owner-specific focused tests and architecture guards for boundary changes

Lifecycle and trace detail live in:

- [TASK_LIFECYCLE_BASELINE.md](./TASK_LIFECYCLE_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [TESTING_INDEX.md](./TESTING_INDEX.md)

## 6. Hard Guardrails

- do not let transport-specific shapes redefine the kernel
- do not let server view DTOs or SDK snapshots become kernel runtime truth
- do not let protocol fields become business or lifecycle truth
- do not let worker rows, item payload, or `eventCode` become policy owners
- do not let adapters become scheduling owners
- do not select workers by transport implementation identifiers
- do not add full-table, full-task, or full-attempt scans to hot paths
- keep new or changed policy seams explicit across matching, assignment,
  attempt, release, refill, intake, control, and terminal decisions
- UI pages, mock runtime, and demo APIs must not redefine the kernel

## 7. Read Next

- repo handoff: [../AGENTS.md](../AGENTS.md)
- full doc map: [README.md](./README.md)
- engine owner entry: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- transport owner entry: [../transport/AGENTS.md](../transport/AGENTS.md)
