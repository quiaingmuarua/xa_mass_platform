# Kernel Core Design Workspace

Status: new-kernel design workspace, not current implementation truth and not
an implementation roadmap.

This directory is primarily for the next kernel design. The current Java
project is a historical reference for failure modes, invariants, and
anti-patterns; it is not the architecture to preserve.

Treat this workspace as kernel-core design notes and Python executable specs
for a clean rewrite, not as a roadmap for incrementally repairing the current
codebase.

This is not a simplified-kernel exercise. It is a stricter clean-kernel redesign
after months of accumulated implementation complexity. Fewer interfaces mean
higher ownership pressure on each interface, not lower design quality.

Every interface should be able to explain:

```text
why it exists
who owns it
which mechanism it protects
which facts it refuses to carry
which callers or owners it refuses to replace
```

It is a place to keep target mechanisms, runtime memory models,
state-transition models, and owner-boundary design that are larger than one
module owner doc and broader than a single roadmap.

The goal is precise, consistent agent alignment before and during executable
spec changes. A kernel design note should make clear:

```text
what the target mechanism is
which owner owns which truth
which current implementation facts are only failure-mode references
which runtime structures and transitions are expected
which proof boundaries future executable specs must satisfy
```

## Core Axioms

The kernel core should stay small:

```text
kernel_core moves between logical states
owners maintain truth
policies decide mapping
transports carry evidence
```

Scheduling mechanisms should be mostly score-band state transitions, bounded
claim/result mutations, and invariant checks. Do not introduce production-style
framework layers, bridge modules, CRUD owners, attempt aggregates, or lifecycle
facades just to make the design look complete.

Small kernel does not mean reduced mechanism quality. The standard is:

```text
v0 can have narrow policy coverage
v0 must not use the wrong mechanism to get a quick loop
mechanism boundaries must stay owner-correct
truth, evidence, policy, transport, query, and diagnostics must stay separate
unsupported policy features are explicit omissions, not design shortcuts
```

Examples:

```text
limited DSL operators are acceptable; making eventCode a worker-match field is not
simple retry policy is acceptable; hiding TaskItem retry truth in Task score is not
in-process handoff is acceptable; merging owner truth is not
bounded fallback is acceptable; event-only liveness is not
```

The kernel is score-band scheduled, not event triggered:

```text
score-bands are the scheduling clock
score-bands decide bounded acquire / recheck opportunities
owners validate truth before moving state
policies map evidence to the next score
external events only provide business evidence or optional acceleration
```

### Three Score Axes

The new kernel uses three independent score axes with one shared mechanism:

```text
one scheduling identity -> one ZSET member -> one ordered score coordinate
```

The score is the only scheduling truth for that identity. It is not the
complete resource object and does not absorb descriptors, payload, result
reason, transport session, trace, or query projection truth.

| Axis | Scheduling identity | Encoded direction | Time rule | Final/recovery rule |
| --- | --- | --- | --- | --- |
| Task | global `taskId` | positive lifecycle tag decreases | same-band `timeSlot` normally increases | negative score is immutable terminal |
| Worker | `workerId` inside one home bucket / WorkerGroup | sign is kernel-owned TaskItem scheduling serviceability: positive HOT_ACQUIRE, negative RECOVERY_RECHECK | same-polarity `timeSlot` normally increases | polarity may toggle because Worker is long-lived; there is no terminal band |
| TaskItem | `(taskId, messageId)` | outcome tag increases | ACTIVE same-band `timeSlot` increases | outcome precedence advances toward final success; there is no release |

The shared contract is:

```text
bounded range + limit discovers due identities
public owner methods accept millisecond time, not internal timeSlot
the score owner mints encoded scores and Redis ranges
score values crossing an owner boundary are opaque observation / lease fences
business meaning stays above the score primitive
the score owner still enforces field width, monotonic direction, suffix rules,
and stale-write safety
score absence is not a hidden parked, paused, terminal, or scheduling-unavailable state
```

Ordinary time movement is monotonic. A lower time coordinate is allowed only
through a declared exact-fence exception such as Task/Worker release or Worker
recovery exhaustion to an owner-minted cold coordinate. TaskItem deliberately
has no release: claim or retry remains held until its future coordinate becomes
due.

Concurrency strength is operation-specific. Monotonic range minting is enough
when a stale writer can only move the coordinate safely forward. Exact
observed-score comparison is required when old evidence could overwrite a
newer lease, consume suffix/budget, release a hold, or change Worker polarity.
TaskItem cross-tag outcome promotion intentionally uses numeric precedence
instead of a claim fence.

Current executable-spec implementations use 100ms slots while all owner-facing
time inputs remain milliseconds. Slot resolution and score packing are private,
but changing either against persisted Redis data is an encoding/keyspace
migration, not an in-place reinterpretation of existing scores.

## Score Mutation Authority

Score is a scheduling coordinate, not a resource mutation lock. Ordinary owner
truth must remain writable without reading, acquiring, leasing, or rewriting a
task or worker score:

```text
task descriptor / allocation metadata update
Worker platform / declared / dynamic attribute update
task item append
Item result / transport evidence write
read projection / trace materialization
```

Those writes may affect facts read by a later scheduling round, but they do not
become score transitions and must not wait for score ownership. In particular,
TaskRuntime append owns canonical TaskItem persistence and invokes
TaskItemScoreBandCore for initial Item-score creation. It does not make a Task
schedulable, refresh Task score, or emit a required wakeup.

Append acceptance is deliberately narrow:

```text
append accepted
  = TaskRuntime stored the latest TaskItem record
  + TaskItemScoreBandCore initialized ACTIVE Item score or confirmed that the
    messageId scheduling identity already exists

append accepted
  != task is live or schedulable
  != dispatcher will consume the item
  != item is guaranteed to produce a result
  != terminal Task must reopen
```

Ingress/product policy, currently expected at the server boundary, decides
whether a caller may append to a closed or intake-disabled Task. It may use a
bounded status cache, close-append tag, tombstone, or another business-owned
gate. Small observation delay is acceptable: a late item accepted after Task
terminal is invalid residue, not a kernel lifecycle failure. Task score remains
terminal and the item must not recreate scheduling visibility.

Physical cleanup races are retention concerns. If terminal cleanup removes Item
keys and a delayed append recreates them, retention may use TTL, repeated
cleanup, generation-scoped keys, or an ingress tombstone. The kernel must not
solve this by making every append read or lease Task score.

```text
ingress owner decides append eligibility
TaskRuntime owns TaskItem persistence
TaskItemScoreBandCore owns Item-score initialization and movement
Task score owns Task scheduling visibility
dispatcher owns consumption opportunity
retention owner removes terminal residue
```

Score write authority is deliberately narrow:

```text
initialization owner
  establishes the first score during task creation or first Worker upsert

scheduling plane
  is the only routine writer while a score is in an acquirable scheduling lane
  owns acquire classification, next time, retry/budget consumption, hold, and
  ordinary same-lane rewrite

lifecycle command owner
  may perform only explicit control transitions such as approve, reject,
  pause, resume, cancel, close, serviceability demotion, or validated recovery
```

The lifecycle-command exception does not create a second scheduling mainline.
It cannot calculate ordinary retry cadence, consume scheduling-round budget, or
perform generic score refresh. It validates command-owner truth and invokes one
declared transition primitive protected by current-score validation or an exact
observed-score fence.

Worker dirty is also not a resource-update lease. A worker attribute update
commits independently. Only when a real persisted assignment continuation
depends on the changed field may the owner additionally attempt a bounded dirty
mark. Dirty failure cannot reject or roll back the attribute update.

The hard owner rule is:

```text
resource owners write resource truth without a score lease
TaskRuntime appends TaskItem through TaskItemScoreBandCore initialization
scheduling owns routine acquirable-score evolution
command owners own explicit lifecycle transitions only
score never becomes a global mutation lock
```

The kernel protects scheduling invariants, not every product-level acceptance
promise. It must not absorb business fallback logic merely to guarantee that
every accepted item is eventually consumed.

The kernel must close its own loop without ordinary external events:

```text
task score-band recheck
worker score-band acquire / admission
assignment-dispatch bounded claim
result classification -> TaskItemScoreBandCore transition
Item score lease expiry / retry-budget exhaustion
terminal / policy closure
```

## Event Cost And Correctness Contract

External event emission is high-cost and opt-in. The default kernel design is
no event emission for ordinary observations or high-frequency mutations.

```text
event emission is high-cost
event emission must be explicitly justified
event emission must fast-fail
event emission is allowed only for key owner state changes
external events must not be required for correctness
external events must not be required for automatic liveness
external events must not be the only state-transition trigger
external events may only accelerate scheduling that already has a fallback
```

The kernel must still converge when ordinary external observations are not
emitted, or when an explicitly allowed event is lost, duplicated, delayed, or
delivered out of order.

Allowed event emission must satisfy all of these constraints:

```text
owner-local state has already changed
the change is a key state transition, not routine evidence
the event carries evidence only, not truth
the event only shortens scheduling / recheck latency
the scheduler has a non-event fallback path
the emit path is bounded and fast-fail
emit failure cannot roll back or block the owner state transition
there is a cheaper score/recheck/repair path for correctness and liveness
```

Every event-accelerated path must still be reachable through owner-local
scheduling:

```text
score scan
score-state recheck
current-truth validation
bounded repair
policy timeout / closure
```

Forbidden event shapes:

```text
per-append wakeup fanout
per-heartbeat scheduling wakeup
per-result global reschedule
unbounded event queue as scheduling backlog
retrying emit until success inside the owner mutation
event-only scheduling with no score/recheck fallback
```

These shapes create avalanche risk: a burst of routine observations can create
more scheduling pressure than the scheduler itself can bound. Event delivery
may be dropped, coalesced, sampled, or rate-limited without changing kernel
correctness.

Routine observations and high-frequency writes must not emit by default:

```text
append
heartbeat / keepalive
transport ack
read-model update
trace materialization
generic dirty marker
```

The hard rule is:

```text
Events may report that truth moved, but only owners may move truth.
```

Explicit human gates are the exception because they intentionally suspend
automatic liveness:

```text
manual review
approval / rejection
manual resume / cancel
operator-required unresolved handling
```

These are not ordinary evidence events. They are authoritative commands whose
owner transaction still validates the current state before changing truth.

## Design Standard

Design notes describe the production-grade target design, not the current
implementation shape.

The current codebase may be useful as:

```text
failure-mode evidence
invariant inventory
proof-gap inventory
migration-cost input
anti-pattern examples
```

It must not lower the kernel design standard. If the current implementation is
confusing, inefficient, or owner-mixed, the design note should say so indirectly
by defining the cleaner target owner split and runtime mechanism. Do not
normalize current design debt into the target architecture just because it is
what exists today.

### Interface Contract Standard

Kernel interfaces are design artifacts, not conveniences shaped around the
current implementation. Once caller, owner, inputs, output, side effects, and
concurrency semantics have been aligned, that contract is frozen until an
explicit interface decision changes it.

Before adding or changing a kernel-facing method, record this contract:

| Question | Required answer |
| --- | --- |
| Owner | Which owner is allowed to perform the operation? |
| Caller | Which scheduling plane or owner invokes it? |
| Inputs | Which values can the caller construct, validate, and legitimately own? |
| Output | Which fact or bounded evidence does the result represent? |
| Side effects | Which owner truth, score, lease, queue, or key may change? |
| Bounds | What limits scans, batches, retries, or memory growth? |
| Concurrency | Is the operation best-effort, monotonic, lease-guarded, or exact CAS? |
| Refusals | Which adjacent policy or owner responsibility must remain outside? |

The implementation must follow these rules:

```text
caller-owned bounded input stays an explicit input
callee-owned internal truth stays hidden
policy chooses bounds and mapping
mechanism validates and performs only its declared mutation
removing a helper or wrapper preserves the existing owner split
test convenience does not justify a new interface or owner
```

Reducing argument count is not inherently an improvement. A stable value such
as `workerIds`, selected and bounded by the caller, is a valid contract input.
Moving its acquisition into a matcher would add hidden I/O and move scheduling
policy into the matching mechanism even if the new signature looked smaller.

Likewise, deleting a pass-through helper means inlining its existing sequence:

```text
before:
  helper = owner A operation + owner B call

after:
  caller performs owner A operation, then calls owner B directly
```

It does not authorize changing owner B's signature, moving owner A's operation
into owner B, adding a facade, or changing side effects. Hidden I/O is a
contract change even when the method signature is unchanged.

The following require explicit agreement before code changes:

- adding or removing a public/kernel-facing parameter or return field;
- moving score acquisition, descriptor lookup, queue access, lease mutation,
  policy evaluation, or persistence across an owner boundary;
- changing bounded input into discovery, or discovery into caller input;
- changing best-effort behavior into atomic/CAS behavior or the reverse;
- adding a bridge, facade, wrapper, registry, callback, background loop, or
  second runtime path;
- changing which component owns limits, ordering, retries, or fairness.

If a request has two plausible interpretations and one changes any item above,
stop and compare the alternatives before implementation. Do not choose a new
contract merely because it makes the current function shorter or the tests
easier to satisfy.

Executable-spec proof must cover both behavior and boundary shape:

```text
signature/DTO shape is locked by a contract test
the expected owner performs each external read or mutation
the callee does not discover inputs that the caller must supply
no removed wrapper survives under a new name
no hidden compatibility path or second mainline remains
```

## Current Design Notes

- [Kernel Design Documents](doc/README.md)
  - routing index for scheduling, resource-model, and runtime-Redis notes.
- [Kernel Core Scheduling](doc/scheduling/README.md)
  - four scheduling planes for the new kernel core: task score-band, worker
    score-band, assignment-dispatch, and result-routing.
- [Worker Resource Model](doc/resource-model/worker-resource-model.md)
  - v0 metadata/query projection for worker groups, workers, and dynamic
    attribute allowlists.
- [Worker HOT_ACQUIRE Lease Protocol](doc/scheduling/worker-hot-acquire-lease-protocol.md)
  - canonical allocation lease, dispatch exact recheck, result-driven
    release/recovery classification, reconnect dirty fence, and natural expiry.
- [Task Resource Model](doc/resource-model/task-resource-model.md)
  - v0 task allocation metadata, start conditions, allocation-rule routing,
    Item retry policy, and bounded task descriptor reads.
- [Task Item Score-Band Scheduling](doc/scheduling/task-item-score-band-scheduling.md)
  - one canonical TaskItem from append through finality, with TaskItem record, ACTIVE
    claim/retry, and monotonic result outcome movement; no second Work model.
- [Assignment-Dispatch Scheduling](doc/scheduling/assignment-dispatch-scheduling.md)
  - plane contract for independent allocation and Item-dispatch pacing, plus
    running-activation transition handling.
- [Task-Worker Allocation Pacer](doc/scheduling/task-worker-allocation-pacer.md)
  - oldest-first allocation fairness, batch matching, activation, and candidate
    publication.
- [Task Item Dispatch Pacer](doc/scheduling/task-item-dispatch-pacer.md)
  - candidate consumption, Item score claim, dispatch-time Worker lease
    disposition, and DeliverSeed queue append; the current executable spec has
    not yet implemented the Worker lease disposition branch.
- [Kernel Application Assembly](doc/kernel-application-assembly.md)
  - independent resource upsert and scheduling-process boundaries,
    private Redis composition, background scheduling lifecycles, built-in CLI,
    and the FastAPI protocol example.
- [DeliverSeed Outbound Delivery](doc/scheduling/deliver-seed-outbound-delivery.md)
  - queued seed consumption, external endpoint-manager execution, and opaque
    result handoff without adapter-owned score mutation.
- [Local Function Transport Adapter](examples/local_function_adapter/README.md)
  - independent process startup, local Worker registration, shared event
    handlers, platform resource upsert, and SeedResult submission.
- [Result-Routing Scheduling](doc/scheduling/result-routing-scheduling.md)
  - unified SeedResult consumption, success/retry selection, Task Item score
    invocation, and Worker exact-release handoff.
- [Seed Result Runtime Redis Shape](doc/runtime-redis/seed-result-runtime-redis-shape.md)
  - unified best-effort SeedResult queue encoding and bounded Redis operations.

## Boundary

These design notes are not proof that the current implementation already
behaves this way. Use them as target mechanism constraints. Use current code
only as legacy evidence when checking why the old design failed or which
invariants the new kernel must preserve.

This workspace is isolated from the current Java project. Current
implementation docs, active roadmaps, architecture explanations, proof
registries, and runbooks should not deep-link these internal design notes as
execution input, implementation proof, or migration direction. If a current
Java roadmap needs a mechanism or Redis shape, define it inside that roadmap or
the owning module contract instead.

The current Python executable spec lives under `kernel_design/executable_spec/` and
is governed by these design contracts. A future production package may move to
a dedicated `kernel_core/` root only through an explicit packaging decision.
Current Java project roadmaps must not execute these design notes or Python
specs as if they were current-platform migration steps.

## Agent Rules

- Read the relevant design note before drafting a new-kernel executable-spec
  plan that changes the same owner plane.
- Do not treat a design note as implementation evidence.
- Do not constrain a design note to current engine/module shape unless the
  constraint is a deliberate production requirement.
- Do not implement directly from a design note without a scoped executable-spec
  plan or explicit owner decision when the change crosses owners, runtime
  truth, storage shape, or a kernel-facing interface.
- If code reality diverges from the design note, report the gap; do not silently
  bend the design note into current behavior.
- If a future executable spec proves part of a design note and makes it current
  new-kernel truth, migrate that fact to the owning kernel-core documentation
  and leave the design note as target context only.
