# Task Runtime Owner Module And Starter SDK Convergence Roadmap

Status: proposed direction document.

This roadmap creates a task-runtime owner boundary before any large engine
cleanup. The target is a non-best-effort task runtime that owns logical work
convergence while keeping physical runtime storage and all thread/bootstrap
assembly outside the semantic owner module.

Read with:

- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [platform_infra/README.md](../platform_infra/README.md)
- [sdk/README.md](../sdk/README.md)
- [EMBEDDED_RUNTIME_SDK_BOUNDARY_CONVERGENCE_ROADMAP.md](EMBEDDED_RUNTIME_SDK_BOUNDARY_CONVERGENCE_ROADMAP.md)

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
- Current embedded SDK / starter assembly owns much of the process bootstrap
  and runtime thread creation.
- Any target Redis shape for this roadmap must be defined inside the infra
  adapter slice. Physical key names and data structures must not become the
  public task-runtime module contract.
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
- Transport result ingress delivery before engine-owned retry/timeout
  compensation.

The key rule is:

```text
timing may be best-effort; convergence and discoverability may not.
```

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
view shapes by default. Current runtime and engine views are migration sources,
not target contracts.

Semantic task-runtime interfaces should expose only:

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
- No dual live task-item lifecycle truth between old engine paths and new
  task-runtime paths.
- No use of trace/review rows as runtime acceptance, retry, finality, or lease
  repair truth.
- No copy-forward of current heavy engine/runtime DTOs or view objects as the
  new task-runtime public contract.
- No default high-cost consistency feature unless a task policy names it and a
  focused proof shows the ROI.

## Do Not Start With

Do not start by wiring new starter threads into the current engine. That creates
more process behavior before the runtime state machine is proven.

Do not start by implementing Redis keys. The first executable proof is the
semantic state machine and memory/Redis contract parity, not physical storage.

Do not start by deleting old engine lifecycle code. First create the new owner,
prove it, route one narrow caller path through it, then remove the old path.

Do not create a facade that forwards to current `TaskWorkRuntime` and call that
the new task-runtime module. The new module must own the convergence protocol,
or it is only another wrapper.

Do not implement the first real path as `task-runtime claim -> worker selection`.
Worker selection/reservation/admission must happen before task-runtime claim, or
task-runtime is forced to own unbound active leases or to reverse-drive worker
selection.

## Relationship To Existing Roadmaps

Any retained or restored Redis task-runtime shape roadmap is subordinate to this
roadmap. It may be used only as a lower-level implementation reference after the
semantic owner is accepted. A Stream / at-least-once direction conflicts with
the current non-best-effort task-runtime goal and must not be executed as-is.

`TASK_SHELL_RUNTIME_MODEL_CONVERGENCE_ROADMAP.md` remains separate. It owns the
fat task shell/model split. This roadmap owns runtime item/result convergence.
The two roadmaps meet only at narrow shell-validation and policy snapshot
ports.

`EMBEDDED_RUNTIME_SDK_BOUNDARY_CONVERGENCE_ROADMAP.md` remains separate. It
owns broad embedded SDK/server runtime assembly split. This roadmap creates a
task-runtime-specific starter SDK because task-runtime threads and external
ingress/egress must not live in the semantic module.

## TROM-0 Module And Caller Inventory

Goal: decide exact module paths and classify live callers before any code move.

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
- Inventory current `TaskWorkRuntimeContractTest` and
  `TaskResultRuntimeContractTest` coverage as migration seeds. Classify each
  invariant as preserved semantic contract, renamed semantic contract,
  implementation-only proof, or removal candidate.
- Inventory current engine-owned threads and loops that touch task work,
  result repair, lease expiry, or dispatch wakeups.
- Inventory SDK/starter/bootstrap code that currently creates or owns runtime
  loops.
- Classify existing runtime APIs and values as semantic owner contract,
  implementation DTO, engine residue, projection/read model, or removal
  candidate.
- Mark any retained older Redis task-runtime roadmap or inventory as superseded
  or subordinate after the module decision is accepted.

Acceptance:

- Inventory names each production caller and whether it should move to
  task-runtime, starter SDK, infra adapter, or engine shell/scheduling.
- Module-path decision is recorded with Maven artifact names.
- The starter SDK path decision records whether it follows `sdk/xa-mass-*`
  naming or intentionally uses a different path.
- Existing runtime contract tests are classified as migration seeds, not ignored
  or blindly copied.
- No code behavior changes are required in this slice.
- The old Redis-only roadmap is no longer an executable parallel direction.

## TROM-1 Semantic Task Runtime Contract

Goal: introduce the task-runtime protocol without physical storage or threads.

Scope:

- Define the semantic runtime surface for append, task-lane acquire/due check,
  claim, result apply, retry/finality, lease repair, final result read, and
  discard.
- Define stable command/outcome values that do not expose Redis, memory map,
  Stream, ZSET, LIST, HASH, Lua, or queue primitive details.
- Define compact task-runtime DTOs from first principles. Do not preserve
  current engine/runtime view shapes unless a field is required by the new
  runtime contract.
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
- State-machine transitions and failure semantics are documented in the module
  README or contract docs.
- Contract surface states that timeout timing is best-effort but repair
  discoverability is required.
- Contract surface states that worker selection/reservation precedes runtime
  claim and that stale epoch or missing reservation evidence cannot create an
  active lease.

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
- Prove active leases remain discoverable even when no task score/due-work
  entry remains.
- Prove starter/thread absence in the semantic module through an architecture
  guard.

Acceptance:

- Memory runtime passes all semantic contract tests.
- A failing active-lease discoverability implementation fails a focused test.
- Guards fail if the semantic module imports Redis, Spring, SDK, engine
  implementation, transport implementation, or creates threads/executors.

## TROM-3 Infra Adapter SPI And Redis Implementation

Goal: implement the semantic task-runtime protocol over physical infra without
leaking physical shape.

Scope:

- Create new memory and Redis infra implementation modules:
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`.
- Put Redis keyspace, codec, Lua/CAS, and physical score/list/hash decisions
  only in the Redis implementation module.
- Define the Redis implementation direction inside this infra adapter slice,
  without exposing key names or data structures to callers.
- Prove active-lease discoverability through a task-level active registry or
  equivalent bounded-discovery mechanism. The first Redis proof does not require
  exact lease-expiry ordering.
- Treat task-local earliest repair hints, per-lease expiry ZSETs, or exact
  timeout wakeup indexes as strategy upgrades. Add them only when a later policy
  or proof needs exact ordering and accepts the cost.
- Separate runtime transition no-loss from Redis node-loss durability. Expose a
  durability profile or explicit startup diagnostic for Redis guarantees.

Acceptance:

- Redis implementation passes the same contract suite as memory.
- Redis-specific tests prove physical key count/cardinality goals without
  becoming public contract tests.
- Redis implementation proves default low-cost behavior: raw backlog storage is
  proportional to item payload frames, active state is proportional to current
  leases, and opt-in per-message indexes are absent unless policy enables them.
- Redis implementation has no dependency on SDK starter, server, engine
  implementation, or transport implementation.
- Task-runtime callers cannot import Redis keyspace or codec packages.

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
- Record a per-loop cutover plan for current engine-owned assignment,
  runtime-ready dispatch, lease repair, and result repair loops. Each migrated
  loop must disable or bypass the old engine loop for the same lane to avoid
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
- No production lane runs both an engine-owned loop and a starter-owned loop for
  the same task-runtime responsibility.

## TROM-5 Engine Strangler Integration

Goal: route one narrow production path through the new task-runtime owner and
connect it to worker-runtime and transport through minimal ports, without dual
runtime truth.

Scope:

- Choose BATCH as the first entry path:
  append -> task-lane acquire/due check -> worker-runtime select/reserve/admit
  -> task-runtime claim with reservation token and expected runtime epoch ->
  transport handoff -> result apply -> final read, including retry and lease
  repair proof.
- Build an engine adapter that calls task-runtime semantic ports.
- Keep engine shell validation and scheduling decisions outside task-runtime.
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
- Emit projection/review/trace after runtime acceptance, not before.
- Treat server view/API parity as a downstream projection concern, not the
  primary proof for this slice.

Acceptance:

- Chosen path has one runtime owner for item state.
- Old engine/runtime mutation path is not also writing the same item truth.
- Focused E2E or integration proof shows append, lane acquire/due check,
  worker-runtime select/reserve/admit, task-runtime claim, dispatch handoff,
  result apply, retry, and final read through the new owner.
- The same proof crosses worker-runtime through a minimal worker/admission/
  reservation/dispatch-target port and crosses transport through a minimal
  assigned-delivery handoff port.
- A stale epoch or missing/mismatched worker reservation cannot create an active
  lease.
- Empty or rejected claims do not leak worker reservations.
- Regression guard prevents the chosen path from importing Redis keyspace or
  writing old engine item lifecycle state.
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
- Define compact final result DTOs for runtime reads. Do not copy heavy current
  view rows, review rows, trace payloads, or worker/attempt diagnostic fields
  unless the task-runtime contract needs them for duplicate/late result handling
  or public bounded read semantics.
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

Goal: remove old engine/runtime residue after each production lane moves.

Scope:

- Delete old per-message lifecycle state owners after callers move.
- Remove compatibility aliases and hidden fallbacks.
- Retire or archive superseded Redis-shape roadmap text after implementation
  truth moves to owner READMEs and proof registry.
- Update `doc/TASK_LIFECYCLE_BASELINE.md`,
  `doc/INFRA_TRUTH_LAYERS.md`, `platform_infra/README.md`, `sdk/README.md`,
  and module READMEs when the actual owner changes.
- Add guards for forbidden imports and forbidden second-owner paths.

Acceptance:

- No old and new task-runtime owner paths remain live for the same production
  lane.
- Guards block task-runtime semantic module from storage/thread/bootstrap
  leakage.
- Guards block engine/server/SDK/transport from Redis task-runtime keyspace and
  physical DTO imports.
- Proof registry names the focused non-best-effort task-runtime contract tests
  and startup/starter verification commands.

## Suggested Implementation Order

1. TROM-0: inventory, module naming, and old roadmap supersession.
2. TROM-1: semantic contracts and state-machine docs.
3. TROM-2: contract tests and memory proof.
4. TROM-3: Redis implementation and active-lease discoverability proof.
5. TROM-4: starter SDK runner/loop-host surface and cutover plan.
6. TROM-5: one engine strangler lane through worker-runtime and transport.
7. Repeat TROM-5 per task contract / dispatch lane.
8. TROM-6: result-read retention/durability boundary if not already converged.
9. TROM-7: residue deletion, guards, docs, proof registry, archive.

## Verification Candidates

Commands must be corrected after module names are finalized. Candidate proof
shape:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime test "-Dtest=TaskRuntimeContractTest,TaskRuntimeArchitectureGuardTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-memory test "-Dtest=InMemoryTaskRuntimeContractTest"
.\mvnw.cmd -q -pl platform_infra/mass-task-runtime-redis test "-Dtest=RedisTaskRuntimeContractTest,RedisTaskRuntimeKeyspaceTest"
.\mvnw.cmd -q -pl <task-runtime-starter-sdk-module> test "-Dtest=TaskRuntimeStarterLifecycleTest,TaskRuntimeStarterBootstrapTest"
.\mvnw.cmd -q -pl xa-mass-engine test "-Dtest=TaskRuntimeStranglerIntegrationTest,EngineTaskRuntimeBoundaryGuardTest"
```

If any slice touches Spring/server startup, add a startup or context proof for
the relevant profile instead of relying only on direct constructor tests.

## Roadmap Completion Criteria

- A dedicated task-runtime owner module owns the non-best-effort item/result
  convergence protocol.
- Physical memory/Redis storage details live only in infra implementation
  modules.
- The starter SDK owns new task-runtime runner/loop-host construction,
  bootstrap, lifecycle, and host integration for migrated lanes.
- At least one production lane uses the new owner without dual runtime truth and
  crosses worker-runtime selection/reservation plus transport assigned-delivery
  through minimal ports.
- Migrated lanes do not run duplicate engine-owned and starter-owned loops for
  the same task-runtime responsibility.
- Old engine item lifecycle residue is removed or explicitly tracked by the
  next active slice.
- Owner docs, proof registry, and guards match the implemented behavior.
- Superseded Redis-only direction is archived or rewritten as an implementation
  detail under the new owner boundary.
