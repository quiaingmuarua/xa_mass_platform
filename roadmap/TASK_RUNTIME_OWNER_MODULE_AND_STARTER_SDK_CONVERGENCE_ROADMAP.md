# Task Runtime Owner Module And Starter SDK Convergence Roadmap

Status: proposed direction document.

This roadmap creates a task-runtime owner module before any large engine
cleanup. The target is a non-best-effort task runtime that owns logical work
convergence while keeping physical runtime storage and all thread/bootstrap
assembly outside the semantic owner module.

Read with:

- [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [platform_infra/README.md](../platform_infra/README.md)
- [sdk/README.md](../sdk/README.md)
- [score-band-task-runtime-redis-shape.md](../architecture/score-band-task-runtime-redis-shape.md)

Prerequisite:

- [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
  is the completed prerequisite boundary for this roadmap. It creates
  `xa-mass-engine-starter` as the containment module for current
  engine-facing assembly and records the approved starter surfaces, temporary
  value-contract exceptions, and guards in
  [ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md).
- TROM must start from those engine-starter handles instead of rediscovering
  broad `embedded-sdk -> engine` dependency/import leakage. The prerequisite
  cleanup is still best-effort containment, not a new runtime owner, and it
  must not introduce listener-first or event-bus runtime coordination as the
  path into TROM.

## Current Code Observations

- Current task work and result runtime contracts live in
  `platform_infra/mass-runtime-api` as `TaskWorkRuntime` and
  `TaskResultRuntime`.
- Current memory and Redis implementations live in
  `platform_infra/mass-runtime-memory` and
  `platform_infra/mass-runtime-redis`.
- Current engine code still mixes shell lifecycle, scheduling orchestration,
  task work runtime calls, result convergence, dispatch binding, repair loops,
  and compatibility projections.
- Current engine cut-in sites must be treated as old port/method paths that
  need explicit closure plans: `TaskLifecycleService` / `TaskManager` for
  append and shell counters, `SimpleTaskDispatchBinder` for claim/dispatch,
  `TaskResultService` for result finality and repair, and
  `EngineRuntimeKernel` for assignment, runtime-ready dispatch, lease repair,
  and result repair loops.
- Current embedded SDK / starter assembly owns much of the process bootstrap
  and runtime thread creation.
- The target Redis shape in
  `architecture/score-band-task-runtime-redis-shape.md` is a physical
  implementation reference. It must not become the public task-runtime module
  contract.
- The older Redis task-runtime roadmap described a Stream / at-least-once
  direction. This roadmap supersedes that direction as the execution entry;
  any retained Redis-shape note must be re-approved under the non-best-effort
  task-runtime boundary below.

## Owner Review

Task runtime belongs to a dedicated task-runtime owner module. It owns the
logical work/result convergence protocol:

```text
accepted append
ready backlog visibility
scheduled retry visibility
active lease ownership
result apply
retry/finality
duplicate and late result handling
lease-timeout repair liveness
task-local final result read semantics
discard cleanup
```

The task-runtime owner module must not own Redis keys, Redis data structures,
Lua scripts, Stream/Pending Entry mechanics, JDBC tables, storage schema,
process threads, Spring beans, embedded SDK facade behavior, transport adapter
loops, or server HTTP routes.

Infra implementation modules own physical storage adapters for the task-runtime
contracts. They may use Redis, memory, Lua, ZSET/LIST/HASH, codec choices, or
future storage primitives, but those choices must stay behind task-runtime
ports and contract tests.

The task-runtime starter SDK owns process assembly and thread lifecycle. It may
create scheduled loops, repair workers, pumps, bootstrap objects, and external
integration wiring. It must not become the owner of task runtime state
transitions.

Engine remains a caller during migration. It may orchestrate task shell policy,
scheduling plane decisions, worker selection, assignment, and terminal policy,
but it should stop owning per-message runtime state. During strangler slices,
engine can adapt old callers to the new task-runtime ports; it must not keep a
second live task-item lifecycle truth.

Transport remains best-effort assigned delivery. It may consume already claimed
dispatch payloads and submit result ingress, but it must not own retry,
finality, lease repair, or task result reliability.

## Boundary Decision

Create three separate module roles:

```text
task-runtime owner module
  semantic contracts, state-machine commands and outcomes, invariants,
  contract-test fixtures, and no physical storage/thread/bootstrap code

platform_infra task-runtime implementation modules
  memory and Redis adapters for the task-runtime contracts
  platform_infra/mass-task-runtime-memory
  platform_infra/mass-task-runtime-redis
  physical key/value/codec/Lua details hidden from callers

task-runtime starter SDK
  runtime bootstrap, thread creation, loop scheduling, external ingress/egress
  wiring, lifecycle handles, and host-facing configuration
```

The semantic owner module is a top-level runtime module:

```text
xa-mass-task-runtime
```

It is parallel to `xa-mass-worker-runtime`, not a `platform_infra` module.
`platform_infra` may host memory/Redis implementation adapters, but it must not
own task-runtime protocol semantics.

The project runtime taxonomy is:

```text
xa-mass-task-runtime
  task item/result convergence runtime

xa-mass-worker-runtime
  worker lifecycle/resource/scheduling-evidence runtime

transport/transport_runtime
  best-effort assigned-delivery executor runtime
```

The working starter path candidate is:

```text
sdk/task-runtime-starter-sdk
```

The artifact id can follow repo naming conventions, for example
`xa-mass-task-runtime-starter-sdk`, but the directory should make the ownership
clear: SDK/starter assembly, not runtime state owner. TROM-0 must make the final
module path and Maven artifact decision. If the final path does not follow the
existing `sdk/xa-mass-*` naming pattern, record why that exception is
intentional.

## Target Module Shape

| Module | Role | May depend on | Must not own |
| --- | --- | --- | --- |
| `xa-mass-task-runtime` | task runtime protocol owner, state-machine contracts, command/outcome values, contract-test suite | base value contracts, kernel SPI values that are explicitly allowed, test fixtures in test scope | Redis keys, memory maps as public shape, Lua, Stream/PEL, Spring, threads, engine implementation, transport implementation, SDK facade |
| `platform_infra/mass-task-runtime-memory` | in-memory implementation for local/dev and contract proof | task-runtime semantic module, narrow infra test helpers | task semantics beyond implementing ports, SDK/startup, engine orchestration |
| `platform_infra/mass-task-runtime-redis` | Redis implementation of the task-runtime ports | task-runtime semantic module, Redis client, codec helpers, low-level Redis keyspace internals | public task-runtime contracts, starter threads, engine scheduling, transport delivery, server HTTP |
| `task-runtime starter SDK` | process bootstrap and thread/lifecycle owner for task runtime | task-runtime semantic module, chosen infra implementation modules, engine/transport ports only as host integration | task item lifecycle truth, Redis key layout, server HTTP contract |
| `xa-mass-engine` | strangler caller during migration; eventual task shell/scheduling/result orchestration only | task-runtime semantic ports, starter-owned runtime handle as needed | per-message queue/lease/retry truth, physical runtime storage, task-runtime threads |

`TaskResultRuntime` converges into `xa-mass-task-runtime` as a logical
sub-contract. The module may keep separate `work` and `result` packages/ports,
but the owner is task-runtime because duplicate/late result handling, retry
exhaustion, finality, final result read, and result-side repair are part of the
same non-best-effort convergence boundary.

If a later implementation keeps current `platform_infra/mass-runtime-api` as a
compatibility source for one slice, that must be recorded as migration residue.
The end state should not leave both old `TaskWorkRuntime` and new task-runtime
ports as two live owner tracks.

## Mechanism Model

The new module is designed around five mechanisms, not a one-to-one copy of
the old engine ports:

| Mechanism | New runtime responsibility | Main old ports closed or narrowed |
| --- | --- | --- |
| Intake / Append Commit | accepted item identity, ready frame creation, lane score update, append reconciliation outcome | `TaskCommandPort.appendTaskItems*`, old `TaskWorkRuntime.enqueue` path |
| Lane Acquire / Wakeup | due lane acquisition, runtime gate/fence validation, dispatchable-lane recovery | `TaskDispatchWakeupPort`, `TaskRuntimeRecoveryPort`, old `readyTaskIds` recovery |
| Worker Reservation Then Claim | consume worker-runtime reservation/admission evidence, convert ready frame to active lease, release reservation on rejected claim | `TaskAssignmentRuntimePort.claimReady`, dispatch compensation hooks |
| Result Apply / Finality Outcome | result callback application, retry/finality, duplicate/late/stale classification, compact outcome facts | `TaskResultIngestPort`, result/finality portions of `TaskResultService` |
| Active Lease Repair | discover active tasks, bounded scan active leases, expire through the same result/finality mutation path | `TaskLeaseMaintenancePort`, `LeaseExpireWatchdog`, result repair residue |

The old interfaces close by port; the new module is shaped by mechanisms. This
keeps closure testable without letting current engine interfaces define the new
task-runtime contract.

## Runtime Guarantee Boundary

Hard commitments:

- An accepted item has one runtime owner before append returns, subject to the
  configured storage durability profile.
- Append does not currently guarantee caller-level duplicate suppression. If an
  append response is lost and the caller retries without a stable dedupe key,
  duplicate logical items may be accepted.
- Runtime-owned accepted item identity is still idempotent. Replaying the same
  accepted `taskId + messageId` inside the runtime must not create a second
  logical item. API-level duplicate suppression through caller idempotency keys
  remains a later optional feature.
- A claimed item remains recoverable until result, retry, finality, or discard.
- Result apply, retry, finality, and duplicate/late handling are idempotent by
  `taskId + messageId + attempt evidence`.
- Final result rows are runtime-retained read state, not a durable public
  ledger. A terminal task may keep final results for a bounded retention window,
  with one day as the initial target, then cleanup may remove them.
- Active lease repair is eventually discoverable. Timeout timing may be
  best-effort, but the ability to find and repair active leases is not
  best-effort.
- Redis node-loss durability is only claimed when the selected Redis durability
  profile actually provides it.

Best-effort commitments:

- Exact timeout moment.
- Exact retry recheck timing.
- Exact fairness across tasks.
- Exact cleanup timing after terminal/discard.
- Transport result ingress delivery before task-runtime-owned retry/timeout
  compensation.

Pre-migration engine-owned retry/timeout compensation is old path residue. It
must be classified in the Old Port Closure Matrix before the affected path
migrates.

The key rule is:

```text
timing may be best-effort; convergence and discoverability may not.
```

## Append Admission And Commit Boundary

Append crosses two owners and must make that boundary explicit:

- engine/task shell owns intake-window validation, task aggregate counters,
  shell-visible append receipt, and aggregate counter reconciliation;
- task-runtime owns accepted item identity, runtime enqueue/no-loss, replay
  idempotency for `taskId + messageId`, and ready/delayed visibility;
- starter or engine assembly may call both owners during migration, but it does
  not own either side's truth.

Append must not leave accepted work silently visible only inside runtime. Batch
append must be one of:

- `all accepted`: every item has runtime ownership and shell aggregate counters
  are committed or reconciled;
- `classified partial`: each item has an explicit accepted/duplicate/rejected
  classification, accepted items are discoverable for aggregate reconciliation,
  and rejected items have not become runtime-owned work;
- `rejected before runtime ownership`: intake/admission failed before any item
  was accepted.

If shell counter update, receipt emission, or wakeup fails after runtime accepts
an item, recovery must be idempotent and bounded: the shell side can reconcile
from accepted runtime item identity without re-appending duplicate work. The
roadmap must not rely on "throw and forget" after partial runtime acceptance.

## Result Finality And Terminal Split

Task-runtime owns message-level finality. Engine owns task aggregate terminal
policy. The split is:

- task-runtime applies result, consumes retry budget, closes or reopens the
  message attempt, records visible final rows, handles duplicate/late callback
  classification, and emits compact outcome facts;
- engine consumes outcome facts to update task progress, trigger terminal
  policy, publish trace/review/projection events, and run task-shell aggregate
  convergence;
- server review and trace materialization are downstream projections, not
  runtime result truth.

Task-runtime outcome facts should be narrow and engine-neutral, for example:

```text
AttemptClosed
LogicalFinal
ProgressDirty
TerminalCandidate
ResultDuplicateOrLate
ResultRejected
```

The exact names may change during TROM-1, but the boundary may not: task-runtime
must not import engine, trace, or server review code, and engine must not keep a
second result-finality truth after a production path moves.

## Default Cost Policy

The default task-runtime path is optimized for high-cardinality task items.
It must support million-item tasks without turning every raw item into a heavy
runtime object, Redis key, durable ledger row, or view DTO.

Default behavior should pay only for high-ROI correctness:

- accepted-item ownership;
- claim exclusivity;
- active-lease recoverability;
- idempotent result/retry/finality;
- bounded final result read retention;
- bounded liveness indexes needed to find active leases or due retry work.

High-cost features are policy opt-ins, not default taxes:

- exact per-message retry due time;
- exact active-lease timeout wakeup;
- strict fairness or per-project quota fairness;
- caller-level append dedupe;
- long-term result archive;
- per-message attempt timeline queries;
- rich per-message operator views on the hot path.

If a task type needs one of these features, the task policy must name it and
the implementation slice must prove the ROI and bounded cost. Do not add a
global per-message index, history table, result ledger, or scan loop merely to
make an edge-case guarantee easier to explain.

## Interface DTO And View Boundary

The new task-runtime module must not copy the current heavy interface, DTO, or
view shapes into public or cross-module contracts by default. Current runtime
and engine views are migration sources, not target contracts.

Semantic task-runtime interfaces that cross module boundaries should expose
only:

- command inputs owned by the caller, such as task id, payload or payload ref,
  requested policy id/version, and optional caller message id;
- runtime-owned handles or evidence, such as message id, attempt number,
  lease/reservation token, retry count, and final sequence;
- compact result/read rows that are needed by the runtime contract;
- opaque payload bytes/refs rather than parsed business fields;
- narrow diagnostics summaries, not per-message view aggregates on hot paths.

Do not expose physical storage names, Redis value shapes, transport envelopes,
server review rows, trace event payloads, or old engine view objects through
the task-runtime semantic module. Rich views belong to server/review/trace
materialization and may lag runtime truth.

This is not a module-internal field-cleanup mandate. Internal implementation
records, package-private DTOs, and test fixtures may carry extra fields during
the first migration if they do not cross module boundaries, become public
contracts, or force other owners to understand task-runtime internals.

## First Real Path Proof Priority

The first production-grade proof is not server view/API completeness. It is one
real task execution path that connects task-runtime, worker-runtime, and
transport through minimal owner ports:

```text
task-runtime lane acquire / due check
  -> worker-runtime select / reserve / admit
  -> task-runtime claim with worker reservation and runtime epoch fence
  -> transport assigned-delivery handoff
  -> task-runtime result apply/retry/finality
```

The integration surface must stay narrow:

- task-runtime exposes lane-acquire outcome, claim preconditions, claimed work,
  attempt evidence, retry/finality commands, and compact final-result reads;
- worker-runtime exposes only selected worker, admission/reservation, reservation
  token, and dispatch-target evidence required by the chosen path;
- transport accepts an already assigned delivery request and returns delivery
  outcome or best-effort failure evidence;
- server view APIs consume projections after runtime acceptance. They are
  important product surfaces, but they are secondary proof for this roadmap.

If a view/API needs extra fields, add them to server/review/trace materialization
unless the field is required by task-runtime convergence, worker-runtime
admission/reservation, or transport assigned-delivery correctness.

Task-runtime claim must not create an active lease before a concrete worker
reservation/admission decision exists. A claim with stale runtime epoch,
missing worker reservation token, or mismatched dispatch-target evidence must be
rejected without making an unbound active lease.

## Non-Goals

- No rewrite of task shell/control-plane storage in the first task-runtime
  slices.
- No server HTTP route or public SDK response redesign.
- No requirement that server view/API parity blocks the first real path proof.
- No transport reliability ownership.
- No worker-runtime score-band slot redesign.
- No public Redis key contract.
- No thread creation inside the task-runtime semantic module.
- No Spring component scanning in the task-runtime semantic module.
- No compatibility aliases for superseded internal task-runtime paths once
  in-repo callers move.
- No dual live task-item lifecycle truth between old engine port/method paths
  and new task-runtime paths.
- No use of trace/review rows as runtime acceptance, retry, finality, or lease
  repair truth.
- No copy-forward of current heavy engine/runtime DTOs or view objects as the
  new task-runtime public contract.
- No module-internal field cleanup as a first-slice goal unless the fields leak
  into a cross-module contract or re-open an old owner path.
- No default high-cost consistency feature unless a task policy names it and a
  focused proof shows the ROI.

## Do Not Start With

Do not start by wiring new starter threads into the current engine. That creates
more process behavior before the runtime state machine is proven.

Do not start by implementing Redis keys. The first executable proof is the
semantic state machine and memory/Redis contract parity inside the new
task-runtime module boundary, not physical storage.

Do not start by deleting old engine lifecycle code. First create the new owner,
prove it, route one narrow caller path through it, then remove the old path.

Do not create a facade that forwards to current `TaskWorkRuntime` and call that
the new task-runtime module. The new module must own the convergence protocol,
or it is only another wrapper.

Do not implement the first real path as `task-runtime claim -> worker selection`.
Worker selection/reservation/admission must happen before task-runtime claim, or
task-runtime is forced to own unbound active leases or to reverse-drive worker
selection.

Do not implement a new mechanism path for a production entry unless the old
port/method shutdown is already named. If the shutdown path is unclear, first
converge the old engine mechanism until it can be bypassed, disabled, deleted,
or guarded for the named port/method set.

## Execution Discipline

Every executable TROM slice follows this order:

```text
1. converge the old mechanism if the port/method shutdown path is unclear
2. write the new task-runtime owner path
3. verify the new path with focused owner and cross-boundary proof
4. close the old port/method path for the migrated production entry
```

Old-mechanism convergence is allowed only when it makes shutdown possible. It is
not a general cleanup lane. A convergence slice must name the old writer, loop,
surface, or DTO path that will become closable after the slice.

New functionality must include a predeclared old port/method closure plan before
code lands. The plan must say whether the old path is deleted, disabled for the
named production entry, bypassed behind a guard, or split into a separate
legacy-convergence roadmap because the shutdown mechanism is too tangled for the
current slice.

Verification must prove both sides:

- the new path satisfies the task-runtime owner invariant; and
- the old path cannot still write, repair, claim, or publish the same runtime
  truth for the migrated port/method set or production entry.

If a slice cannot explain how the old path closes, it is not ready to implement
new task-runtime behavior. Either narrow the port/method set or create a
separate old-mechanism convergence roadmap before adding the new path.

## Relationship To Existing Roadmaps

Any retained or restored Redis task-runtime shape roadmap is subordinate to this
roadmap. It may be used only as a lower-level implementation reference after the
semantic owner is accepted. A Stream / at-least-once direction conflicts with
the current non-best-effort task-runtime goal and must not be executed as-is.

Task shell/model split is out of scope for this roadmap. A future shell/model
roadmap may own fat task shell cleanup, but TROM does not depend on a separate
active shell roadmap. TROM only touches shell validation, aggregate
reconciliation, and policy snapshot ports when a task-runtime path needs them.

Broader embedded SDK/server runtime assembly cleanup is a future follow-up, not
a TROM prerequisite. This roadmap creates a task-runtime-specific starter SDK
because task-runtime threads and external ingress/egress must not live in the
semantic module. TROM-0 must still define how that starter relates to
`xa-mass-engine-starter` and `sdk/xa-mass-embedded-sdk`; it must not rely on a
nonexistent embedded-runtime split roadmap to settle startup ownership.

There is no separate task-runtime API extraction roadmap. Engine DTO/import
cleanup is TROM residue work and should happen only when it supports a named
old port/method closure or guards a migrated task-runtime path. Do not create
`xa-mass-task-runtime-api` as an escape route beside the old engine path.

## TROM-0 Module, Caller, And Old Port Closure Matrix

Goal: decide exact module paths, classify live callers, and create the Old Port
Closure Matrix that maps current engine task ports to the five new mechanisms
before any new task-runtime behavior lands.

Scope:

- Record `xa-mass-task-runtime` as the top-level semantic owner module and
  remove any remaining wording that treats task-runtime semantics as
  `platform_infra` ownership.
- Record new implementation modules as the target:
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`. Current `mass-runtime-memory` and
  `mass-runtime-redis` remain migration sources/residue until callers move;
  do not rename or mutate them as the first implementation path.
- Inventory all current `TaskWorkRuntime` and `TaskResultRuntime` production
  callers.
- Create the Old Port Closure Matrix for current task-facing ports:
  `TaskAssignmentRuntimePort`, `TaskLeaseMaintenancePort`,
  `TaskDispatchWakeupPort`, `TaskShellLifecycleMaintenancePort`,
  `TaskRuntimeRecoveryPort`, `TaskStateRuntimePort`, `TaskQueryPort`,
  `TaskCommandPort`, and `TaskResultIngestPort`.
- Record the Old Port Closure Matrix in
  [TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_INVENTORY.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_INVENTORY.md).
  The required columns are: old port, method, current callers, truth touched,
  target mechanism, target command/outcome, closure mode, proof, guard, and
  status.
- For each old port method, record:
  current callers, current runtime truth touched, target mechanism, target new
  task-runtime command/outcome, closure mode, first proof, and guard.
- Use closure modes:
  `delete`, `delegate to new runtime`, `split shell part from runtime part`,
  `keep engine-shell`, `engine-internal only`, or
  `requires prerequisite old-mechanism convergence`.
- Treat old-mechanism convergence as a targeted prerequisite only when a port
  cannot be directly deleted, delegated, split, or guarded. Do not create one
  broad "old mechanism cleanup" roadmap.
- Initial expected matrix direction:
  `TaskAssignmentRuntimePort` maps to Worker Reservation Then Claim;
  `TaskDispatchWakeupPort` and `TaskRuntimeRecoveryPort` map to Lane Acquire /
  Wakeup; `TaskLeaseMaintenancePort` maps to Active Lease Repair;
  `TaskResultIngestPort` maps to Result Apply / Finality Outcome;
  `TaskCommandPort.appendTaskItems*` maps to Intake / Append Commit;
  `TaskCommandPort` shell methods, `TaskQueryPort`,
  `TaskStateRuntimePort`, and `TaskShellLifecycleMaintenancePort` remain
  engine-shell/internal unless the matrix proves a runtime-truth method must
  move.
- Choose the first engine cut-in port by closability, not by feature neatness.
  The default preference is claim/assignment or append intake if their old path
  can be closed without result/finality convergence; result/finality likely
  needs a prerequisite matrix entry before cutover.
- Inventory `platform_infra/mass-runtime-api` as a mixed module. Classify every
  task-runtime symbol, worker low-level SPI symbol, score-band slot contract,
  shared value, and test fixture as:
  `migrate to xa-mass-task-runtime`, `remain low-level shared SPI`,
  `owned by xa-mass-worker-runtime`, `implementation-only`, or `remove`.
- Inventory current `TaskWorkRuntimeContractTest` and
  `TaskResultRuntimeContractTest` coverage as migration seeds. Classify each
  invariant as preserved semantic contract, renamed semantic contract,
  implementation-only proof, or removal candidate.
- Inventory current engine-owned threads and loops that touch task work,
  result repair, lease expiry, or dispatch wakeups.
- Inventory SDK/starter/bootstrap code that currently creates or owns runtime
  loops.
- Produce the final module dependency graph for `xa-mass-task-runtime`,
  `platform_infra/mass-task-runtime-memory`,
  `platform_infra/mass-task-runtime-redis`, the task-runtime starter SDK,
  `xa-mass-engine-starter`, `sdk/xa-mass-embedded-sdk`, and `xa-mass-engine`.
  The graph must name the only host startup owner for each migrated
  task-runtime responsibility.
- Decide whether `sdk/xa-mass-embedded-sdk` only calls the task-runtime starter
  through a start/stop handle, whether `xa-mass-engine-starter` only supplies
  host ports/engine-facing handles, and which module is forbidden from creating
  task-runtime loops.
- Classify existing runtime APIs and values as semantic owner contract,
  implementation DTO, engine residue, projection/read model, or removal
  candidate.
- Reclassify every temporary exception in
  [ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md)
  whose slice is `TROM-0`, `TROM`, or `TROM-4`. Each must land in exactly one
  target bucket: `xa-mass-task-runtime`, engine-shell/internal, task-runtime
  starter contract, SDK public contract, or delete.
- Mark any retained older Redis task-runtime roadmap or inventory as superseded
  or subordinate after the module decision is accepted.
- Mark any required old-mechanism convergence that is too broad for TROM-0 as a
  separate prerequisite roadmap with a named closure target.

Acceptance:

- Inventory names each production caller and whether it should move to
  task-runtime, starter SDK, infra adapter, or engine shell/scheduling.
- Old Port Closure Matrix exists and covers every method on the nine current
  `TaskManager` task ports.
- The matrix is recorded in
  `TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_INVENTORY.md` using the
  required schema. TROM-0 is not complete while required rows remain pending,
  `_TBD`, or `later classify`.
- Each matrix row maps to exactly one of the five mechanisms, or is explicitly
  classified as engine-shell/internal residue.
- Each matrix row states whether closure is delete, delegate, split, keep,
  engine-internal only, or requires a prerequisite old-mechanism convergence
  roadmap.
- Any prerequisite old-mechanism convergence roadmap has a named port, method
  set, target mechanism, and closure condition. It is not a generic engine
  cleanup roadmap.
- Module-path decision is recorded with Maven artifact names.
- The starter SDK path decision records whether it follows `sdk/xa-mass-*`
  naming or intentionally uses a different path.
- TROM-0 records a final module dependency graph and single startup-owner rule
  for each migrated task-runtime responsibility. The graph must prevent
  `xa-mass-engine-starter`, task-runtime starter SDK, and
  `sdk/xa-mass-embedded-sdk` from all becoming partial loop/bootstrap owners.
- TROM-0 records whether embedded SDK may only call task-runtime starter
  start/stop handles, and whether engine-starter may only provide host ports
  and engine-facing assembly handles.
- Every ECSP temporary value/config exception marked for TROM-0, TROM, or
  TROM-4 is reclassified in the TROM inventory to a target owner/module,
  proof, guard, and removal/retention decision. None may remain as
  `later classify`.
- Existing runtime contract tests are classified as migration seeds, not ignored
  or blindly copied.
- The `mass-runtime-api` split inventory prevents the old module from remaining
  a hidden task-runtime semantic owner and prevents task-runtime from importing
  worker-runtime low-level SPI by accident.
- No code behavior changes are required in this slice.
- The old Redis-only roadmap is no longer an executable parallel direction.
- First migrated port/method set has a written old port/method closure plan
  before TROM-1/TROM-2 implementation starts.
- Any port/method set whose old path cannot be closed has a recorded
  precondition:
  narrow the port/method set, converge the old mechanism first, or split a
  separate old-mechanism convergence roadmap.

## TROM-0A Legacy Mechanism Convergence Gate

Goal: make a specific old port/method set closable when the Old Port Closure
Matrix cannot name a safe shutdown mechanism.

Scope:

- Use this gate only for legacy mechanism work that directly enables shutdown
  of an old port/method set for a named task-runtime mechanism.
- Reduce broad engine paths into port/method-addressable seams where needed.
  Examples: `TaskResultIngestPort` result/finality, `TaskLeaseMaintenancePort`
  repair, `TaskDispatchWakeupPort` wakeup, `TaskRuntimeRecoveryPort` recovery,
  or `TaskCommandPort.appendTaskItems*` append commit.
- Split a dedicated roadmap when the legacy convergence is larger than one
  bounded TROM slice. The split roadmap must name the port/method set it
  unblocks, the new mechanism it enables, and the old port/method path it will
  make closable.
- Do not add new task-runtime behavior in this gate except test fixtures or
  guards needed to prove the old path is now port/method-addressable.

Acceptance:

- The target old port/method set has a concrete shutdown mechanism: delete,
  delegate, split, disable for a named production entry, bypass behind guard,
  or route to new owner.
- The convergence work names the exact old classes/methods/loops affected and
  the TROM mechanism it unblocks.
- Focused tests or guards prove the old mechanism can be isolated without
  changing unrelated task shell, worker-runtime, transport, or server behavior.
- If the old-mechanism convergence is split out, TROM records the prerequisite
  roadmap and does not start the corresponding new path until that prerequisite
  is satisfied.

## TROM-1 Semantic Task Runtime Contract

Goal: introduce the task-runtime protocol in the new semantic module without
physical storage or threads, while preserving an old port/method closure plan
for every contract path that will replace engine behavior.

Scope:

- Define the semantic runtime surface through the five mechanisms:
  Intake / Append Commit, Lane Acquire / Wakeup, Worker Reservation Then Claim,
  Result Apply / Finality Outcome, and Active Lease Repair.
- For each semantic command/outcome, record the old port/method set it is
  intended to replace, delegate from, split from, or leave untouched. Do not add
  contracts that cannot be tied to a named owner invariant and old port/method
  closure.
- Define append admission/commit outcomes across task shell and task-runtime:
  rejected-before-runtime, all-accepted, classified-partial, duplicate replay,
  and reconciliation-needed.
- Define stable command/outcome values that do not expose Redis, memory map,
  Stream, ZSET, LIST, HASH, Lua, or queue primitive details.
- Define public/cross-module task-runtime DTOs from runtime contract needs, not
  current engine/runtime view shapes. Module-internal records may remain
  pragmatic during migration as long as they do not become public contract
  fields or leak storage/engine/trace/server ownership.
- Define the owner-level state machine:

```text
READY_BACKLOG
SCHEDULED_RETRY
LEASED
FINAL
DISCARDED
```

- Define attempt evidence: `messageId`, `attemptNo`, `leaseToken` or neutral
  reservation token, worker binding evidence, policy snapshot version, and
  retry count.
- Define lane/gate/fence semantics needed by the runtime owner:
  lane-acquire outcome, runtime gate, expected runtime epoch, terminal/discard
  fence, and paused/parked lane behavior.
- Define claim preconditions. A claim must include admitted worker evidence,
  worker reservation token or equivalent owner-neutral reservation proof,
  expected runtime epoch, max items, and lease policy. A claim must not produce
  an active lease without a concrete worker binding.
- Define append identity as accepted-item identity, not caller-level
  exactly-once submit. Caller API idempotency is deferred, but runtime replay of
  the same accepted `taskId + messageId` remains idempotent.
- Define aggregate reconciliation semantics for accepted runtime items when
  shell counters, append receipt, or wakeup fail after runtime acceptance.
- Define message-finality outcome contracts emitted by result apply/retry/
  finality. These outcomes are engine-neutral facts such as attempt closed,
  logical final, progress dirty, terminal candidate, duplicate/late, or
  rejected result; engine consumes them for trace/progress/terminal policy.
- Define final result retention as bounded runtime read state. The first target
  retention is one day after task terminal, not durable public history.
- Define default-cost behavior: million-item raw backlog support, sparse active
  state, no default caller dedupe, no default per-message due index, no default
  durable result ledger, and no rich per-message view DTO on hot paths.
- Define explicit active-lease discoverability requirements. First contract
  version requires eventual discoverability, not exact lease-expiry ordering.
- Define durability-profile metadata needed to avoid false zero-loss claims.

Acceptance:

- Semantic module compiles without Redis, Spring, engine implementation,
  transport implementation, or SDK facade dependencies.
- No class in the semantic module exposes physical key/value/storage names.
- Public contract DTOs are narrow and justified by runtime behavior; old view
  objects are not copied into the module as compatibility surfaces.
- Module-internal DTO extra fields are not blockers unless they cross a module
  boundary, leak physical/runtime-owner internals, or preserve a second owner
  path.
- State-machine transitions and failure semantics are documented in the module
  README or contract docs.
- Contract surface states that timeout timing is best-effort but repair
  discoverability is required.
- Contract surface states that worker selection/reservation precedes runtime
  claim and that stale epoch or missing reservation evidence cannot create an
  active lease.
- Contract surface states that append cannot silently leave accepted runtime
  items outside shell aggregate reconciliation.
- Contract surface splits message finality from task terminal convergence and
  forbids task-runtime dependencies on engine, trace, or server review code.
- Contract docs include the intended old port/method closure target for append,
  lane acquire/wakeup, claim/lease, result finality, repair, and final-read
  surfaces.
- No contract is added solely to mirror an old engine DTO or keep an old public
  starter surface alive.

## TROM-2 Contract Test Harness And Memory Proof

Goal: prove the non-best-effort runtime protocol before Redis implementation.

Scope:

- Create a reusable task-runtime contract test suite.
- Add a memory implementation or test adapter that passes the contract.
- Cover append id generation / accepted identity, runtime-level duplicate
  append for the same accepted `taskId + messageId`, non-guaranteed API-level
  duplicate submit behavior without a caller dedupe key, claim precondition
  rejection, claim exclusivity, result success, retryable failure, retry
  exhausted, late/duplicate result, lease repair, pause/resume boundary, discard
  cleanup, and one-day terminal final result retention.
- Cover append half-commit recovery: runtime accepted item plus failed shell
  counter/receipt/wakeup must produce a reconciliation-needed outcome or a
  bounded idempotent reconciliation path.
- Cover batch append partial classification if the implementation allows
  partial acceptance; otherwise prove rejected-before-runtime or all-accepted
  behavior.
- Cover result outcome emission for attempt-closed, logical-final,
  progress-dirty, terminal-candidate, duplicate/late, and rejected-result
  classifications without importing engine/trace/server code.
- Prove active leases remain discoverable even when no task score/due-work
  entry remains.
- Prove starter/thread absence in the semantic module through an architecture
  guard.
- Add negative contract fixtures for paths that must not accept both legacy and
  new owner writes for the same item identity.

Acceptance:

- Memory runtime passes all semantic contract tests.
- A failing active-lease discoverability implementation fails a focused test.
- Guards fail if the semantic module imports Redis, Spring, SDK, engine
  implementation, transport implementation, or creates threads/executors.
- Contract proof includes at least one double-owner negative case: the same
  item identity cannot be accepted, claimed, finalized, or repaired by both a
  legacy writer and the new semantic owner in the migrated path.

## TROM-3 Infra Adapter SPI And Redis Implementation

Goal: implement the semantic task-runtime protocol over physical infra without
leaking physical shape.

Scope:

- Create new memory and Redis infra implementation modules:
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`.
- Put Redis keyspace, codec, Lua/CAS, and physical score/list/hash decisions
  only in the Redis implementation module.
- Use `score-band-task-runtime-redis-shape.md` as the Redis implementation
  direction, but do not expose its key names or data structures to callers.
- Prove active-lease discoverability through a task-level active registry or
  equivalent bounded-discovery mechanism. The first Redis proof does not require
  exact lease-expiry ordering.
- Treat task-local earliest repair hints, per-lease expiry ZSETs, or exact
  timeout wakeup indexes as strategy upgrades. Add them only when a later policy
  or proof needs exact ordering and accepts the cost.
- Separate runtime transition no-loss from Redis node-loss durability. Expose a
  durability profile or explicit startup diagnostic for Redis guarantees.
- Prove Redis implementation can be enabled per migrated path without leaving
  the old physical `mass-runtime-*` implementation as a second live owner for
  the same path.

Acceptance:

- Redis implementation passes the same contract suite as memory.
- Redis-specific tests prove physical key count/cardinality goals without
  becoming public contract tests.
- Redis implementation proves default low-cost behavior: raw backlog storage is
  proportional to item payload frames, active state is proportional to current
  leases, and opt-in per-message indexes are absent unless policy enables them.
- Redis shape proof demonstrates that append of many ready items does not create
  per-ready-item runtime hashes.
- Redis implementation has no dependency on SDK starter, server, engine
  implementation, or transport implementation.
- Task-runtime callers cannot import Redis keyspace or codec packages.
- Redis profile tests include a fixture proving the old path is disabled or
  bypassed for any path that is enabled on the new implementation.

## TROM-4 Starter SDK Runner Surface And Thread Cutover

Goal: create the task-runtime starter SDK as the owner of new task-runtime
runner/loop-host assembly, without prematurely taking over every existing
engine production loop.

Scope:

- Add the starter SDK module under the path decided in TROM-0.
- Define bootstrap configuration for memory or Redis task-runtime adapters.
- Define starter-owned runner/loop-host surfaces, such as
  `TaskRuntimeRunner` / `TaskRuntimeLoopHost` or equivalent, for due-task
  polling, lease repair, result repair, dispatch handoff integration, and
  graceful shutdown.
- In this slice, prove starter lifecycle wiring with isolated/in-memory loop
  hosts. Do not migrate all existing engine production loops before the first
  real path proof.
- Do not start a starter-owned loop for a production responsibility until the
  old engine loop closure plan for that exact responsibility/path is
  implemented or guarded.
- Record a per-loop cutover plan for current engine-owned assignment,
  runtime-ready dispatch, lease repair, and result repair loops. Each migrated
  loop must disable or bypass the old engine loop for the same path to avoid
  double polling or double repair.
- Build this module independently first; the broader embedded-runtime split may
  consume it later, but task-runtime starter work must not wait for
  embedded-sdk cleanup.
- Expose host-facing start/stop handles and health/diagnostic summaries.
- Keep all external interaction through ports: task shell validation/policy,
  worker selection/assignment, transport dispatch, result ingress, trace, and
  optional operator diagnostics.

Acceptance:

- Semantic task-runtime module has no thread creation.
- Starter SDK owns construction and shutdown for new task-runtime loop hosts.
- Starter SDK does not own task item state transitions; it only calls
  task-runtime ports.
- Starter SDK tests prove start/stop idempotency, no leaked threads, and
  memory/Redis bootstrap profile selection for isolated loop hosts.
- No production path runs both an engine-owned loop and a starter-owned loop for
  the same task-runtime responsibility.
- A guard or fixture fails if a migrated production path registers both the old
  engine loop and the new starter loop for assignment, runtime-ready dispatch,
  lease repair, or result repair.
- Cutover tests prove the old loop is explicitly disabled or bypassed for the
  migrated path, not merely expected to stay idle.
- If a loop cannot be disabled cleanly, this slice must stop and create or link
  the required old-mechanism convergence roadmap instead of starting a duplicate
  loop host.

## TROM-5 Engine Strangler Integration And Old Path Closure

Goal: route the TROM-0-selected port/method-backed path through the new
task-runtime owner and close the old engine path for that path. BATCH remains
the default full production candidate when the closure matrix proves it can be
closed safely.

Scope:

- Treat BATCH as the default first entry-path candidate, subject to the TROM-0
  Old Port Closure Matrix:
  append admission/commit -> task-lane acquire/due check -> worker-runtime
  select/reserve/admit -> task-runtime claim with reservation token and expected
  runtime epoch -> transport handoff -> result apply -> final read, including
  retry and lease repair proof.
- If TROM-0 selects a different first port/method-backed production path because
  BATCH cannot close result/finality, lease repair, or append residue safely,
  TROM-5 must use that selected path and record the deferred mechanisms rather
  than forcing BATCH.
- A non-BATCH selection proves only the selected mechanism set. It cannot be
  counted as the roadmap-level first real path until a later TROM-5 extension
  crosses worker-runtime selection/reservation, transport assigned delivery,
  result apply/finality, and final read without dual runtime truth.
- Build an engine adapter that calls task-runtime semantic ports.
- Before routing the path, implement or reference the old port/method closure
  plan from TROM-0/TROM-0A. The slice must name the old `TaskManager`,
  `TaskLifecycleService`, `SimpleTaskDispatchBinder`, `TaskResultService`,
  `EngineRuntimeKernel`, or `mass-runtime-*` path that is being closed.
- Keep engine shell validation and scheduling decisions outside task-runtime.
- Keep engine-owned task aggregate counter reconciliation and terminal policy as
  consumers of task-runtime outcomes; do not move trace/review/progress policy
  into task-runtime.
- Add or narrow the worker-runtime integration port needed by this path. It
  should expose only selected worker/admission/reservation/dispatch-target
  evidence, not worker-runtime internal state or score-band implementation
  details.
- Add or narrow the transport handoff port needed by this path. It should accept
  already assigned delivery work and return delivery outcome/failure evidence,
  not task lifecycle ownership.
- Runtime claim must consume worker reservation/admission evidence and expected
  runtime epoch. Empty, stale, or rejected claim paths must release or expire
  worker reservations without leaking capacity.
- Keep transport as best-effort delivery only.
- Disable or bypass old per-message runtime mutation for the chosen path.
- Disable or bypass old engine-owned assignment/dispatch/repair loops for the
  migrated path when starter-owned loops take over that responsibility.
- Delete, disable, or guard old DTO/import/public-surface residue only when it
  helps prove the old path cannot be used for the migrated path.
- Emit projection/review/trace after runtime acceptance, not before.
- Treat server view/API parity as a downstream projection concern, not the
  primary proof for this slice.

Acceptance:

- Chosen path has one runtime owner for item state.
- Old engine/runtime mutation path is not also writing the same item truth.
- Closure proof names the exact old path that is deleted, disabled, bypassed, or
  guarded for the migrated path.
- Focused integration proof covers every mechanism selected by the TROM-0 Old
  Port Closure Matrix and proves the corresponding old path is closed.
- If BATCH remains selected, focused E2E proof shows append admission/commit,
  lane acquire/due check, worker-runtime select/reserve/admit, task-runtime
  claim, dispatch handoff, result apply, retry, and final read through the new
  owner.
- If the selected path includes append, append proof covers accepted-item
  runtime ownership plus shell aggregate reconciliation. No accepted item may
  remain runtime-only without a classified reconciliation path.
- If the selected path crosses worker-runtime or transport, the proof uses a
  minimal worker/admission/reservation/dispatch-target port and a minimal
  assigned-delivery handoff port.
- If the selected path includes result/finality, result proof shows
  task-runtime emits message-finality outcome facts and engine consumes them
  for trace/progress/terminal policy without owning a second result-finality
  truth.
- A stale epoch or missing/mismatched worker reservation cannot create an active
  lease.
- Empty or rejected claims do not leak worker reservations.
- Migrated path has a failing guard/fixture for duplicate old/new loop
  registration.
- Regression guard prevents the chosen path from importing Redis keyspace or
  writing old engine item lifecycle state.
- Regression guard or source test prevents callers in the migrated path from
  returning to the old engine DTO/public starter surface when that surface would
  re-open the old path.
- No server view/API parity requirement is used as a substitute for the runtime
  path proof.

## TROM-6 Result Runtime Retention And Public Read Boundary

Goal: keep final result read truth inside task-runtime while making bounded
retention explicit.

Scope:

- Keep stable final result rows inside the `xa-mass-task-runtime` owner as a
  result sub-contract.
- Define bounded retention and cleanup policy. Initial target: final result rows
  are retained until roughly one day after task terminal, then task-runtime
  cleanup may remove them.
- Record that final result rows are not durable public result history and not a
  long-term audit ledger.
- Define public/cross-module final result DTOs for runtime reads. Internal
  result rows may keep pragmatic fields during migration, but public reads must
  not copy heavy current view rows, review rows, trace payloads, or
  worker/attempt diagnostic fields unless the task-runtime contract needs them
  for duplicate/late result handling or public bounded read semantics.
- Keep stage/repair/barrier semantics as runtime-owned reliability support.
- Ensure server review/export rows remain materialized views.
- Keep trace/audit history separate from runtime result read truth.

Acceptance:

- Public result read semantics are explicit: bounded runtime retention, not
  durable public result truth.
- Terminal cleanup tests prove the one-day retention target can remove final
  result rows without affecting trace/review materialization semantics.
- Duplicate/late callback handling does not depend on server review rows.
- Result contract tests cover final row idempotency, read window ordering,
  barrier repair, and discard cleanup.

## TROM-7 Residue Removal And Guards

Goal: remove old engine/runtime residue after each port/method-backed
production path moves.

Scope:

- Delete old per-message lifecycle state owners after callers move.
- Remove compatibility aliases and hidden fallbacks.
- Retire task-runtime semantic ownership from `platform_infra/mass-runtime-api`;
  leave only explicitly classified low-level shared SPI or worker-runtime
  residue until later convergence removes it.
- Retire or archive superseded Redis-shape roadmap text after implementation
  truth moves to owner READMEs and proof registry.
- Update `doc/TASK_LIFECYCLE_BASELINE.md`,
  `doc/INFRA_TRUTH_LAYERS.md`, `platform_infra/README.md`, `sdk/README.md`,
  and module READMEs when the actual owner changes.
- Add guards for forbidden imports and forbidden second-owner paths.
- Keep ECSP boundary guards in the regression set whenever TROM touches
  `xa-mass-engine-starter`, `sdk/xa-mass-embedded-sdk`, starter handles, or
  approved starter surfaces.

Acceptance:

- No old and new task-runtime owner paths remain live for the same port/method
  set or production entry.
- Guards block task-runtime semantic module from storage/thread/bootstrap
  leakage.
- Guards block engine/server/SDK/transport from Redis task-runtime keyspace and
  physical DTO imports.
- Guards block `mass-runtime-api` from regaining task-runtime semantic ownership
  after task-runtime callers move.
- ECSP guards still fail on `MassApplication.getEngine()`,
  `MassEngine.getConfig()`, direct embedded-sdk engine internals, and
  unapproved starter-facing surface expansion.
- Proof registry names the focused non-best-effort task-runtime contract tests
  and startup/starter verification commands.

## Suggested Implementation Order

0. Complete
   [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md).
1. TROM-0: inventory, module naming, first port/method set selection, and old
   port/method closure plan.
2. Conditional TROM-0A: converge the old mechanism first when the shutdown path
   is unclear. Split a separate roadmap if the convergence is too large for one
   bounded TROM slice.
3. TROM-1: semantic contracts and state-machine docs, including old
   port/method closure targets for each new contract surface.
4. TROM-2: contract tests and memory proof, including double-owner negative
   cases.
5. TROM-5: one selected port/method-backed production path through
   worker-runtime and transport, using the memory proof path when sufficient,
   and close the old engine path for that path.
6. TROM-3: Redis implementation and active-lease discoverability proof for
   paths whose old path is already closed or guardable.
7. TROM-4: starter SDK runner/loop-host surface and thread cutover only for
   responsibilities whose old engine loop has a closure plan.
8. Repeat TROM-5 per selected port/method set or production entry.
9. TROM-6: result-read retention/durability boundary if not already converged.
10. TROM-7: residue deletion, guards, docs, proof registry, archive.

## Verification Candidates

Commands must be corrected after module names are finalized. Candidate proof
shape:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeContractTest,TaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeAppendCommitBoundaryTest,TaskRuntimeResultOutcomeContractTest"
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeDoubleOwnerGuardTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory test "-Dtest=InMemoryTaskRuntimeContractTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisTaskRuntimeContractTest,RedisTaskRuntimeKeyspaceTest"
.\mvnw.cmd -q -pl <task-runtime-starter-sdk-module> test "-Dtest=TaskRuntimeStarterLifecycleTest,TaskRuntimeStarterBootstrapTest"
.\mvnw.cmd -q -pl <task-runtime-starter-sdk-module> test "-Dtest=TaskRuntimeLoopCutoverGuardTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeStranglerIntegrationTest,EngineTaskRuntimeBoundaryGuardTest,TaskRuntimeAppendReconciliationIntegrationTest,EngineTaskRuntimeOldPathClosureGuardTest"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=EmbeddedSdkEngineDependencyGuardTest,EngineStarterBackdoorGuardTest,EngineStarterSurfaceInventoryGuardTest,EngineCallerSurfaceInventoryCompletenessGuardTest,EngineStarterWorkerTransportOwnershipGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

If any slice touches Spring/server startup, add a startup or context proof for
the relevant profile instead of relying only on direct constructor tests.

TROM-0, TROM-5, and TROM-7 must include the ECSP guard regression command when
they touch starter/SDK/engine-starter boundaries. The guard set must prove that
deleted `MassEngine` / `MassApplication` backdoors remain absent, embedded SDK
does not regain direct engine internals, and approved starter surfaces do not
expand without inventory review.

## Roadmap Completion Criteria

- A dedicated task-runtime owner module owns the non-best-effort item/result
  convergence protocol.
- Physical memory/Redis storage details live only in infra implementation
  modules.
- Append has an explicit admission/commit boundary: accepted runtime items are
  either shell-committed or shell-reconcilable, never runtime-only and silent.
- Message finality belongs to task-runtime outcome facts; task terminal policy,
  trace, and progress convergence consume those facts from engine-side owners.
- The starter SDK owns new task-runtime runner/loop-host construction,
  bootstrap, lifecycle, and host integration for migrated task-runtime
  responsibilities.
- At least one port/method-backed production path uses the new owner without
  dual runtime truth and crosses worker-runtime selection/reservation plus
  transport assigned-delivery through minimal ports.
- The first migrated port/method set followed the required order: old mechanism
  convergence if needed, new owner path, proof, and old port/method closure.
- For every migrated port/method-backed path, the old engine/runtime path is
  deleted, disabled, bypassed behind a guard, or explicitly routed to the new
  owner. No path is considered migrated while the closure mechanism is unknown.
- Migrated paths do not run duplicate engine-owned and starter-owned loops for
  the same task-runtime responsibility.
- `platform_infra/mass-runtime-api` no longer acts as hidden task-runtime
  semantic owner for migrated production paths; remaining worker low-level SPI
  is explicitly classified.
- Old engine item lifecycle residue is removed or explicitly tracked by the
  next active slice.
- Owner docs, proof registry, and guards match the implemented behavior.
- Superseded Redis-only direction is archived or rewritten as an implementation
  detail under the new module boundary.
- The prerequisite engine-starter boundary roadmap is complete, and this
  roadmap consumes its final inventory, starter handle decisions, and guard set.
- ECSP boundary guards remain green after TROM changes that touch starter,
  embedded SDK, engine-starter, or approved starter surfaces.
