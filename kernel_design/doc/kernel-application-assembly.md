# Kernel Application Assembly

Status: active new-kernel application contract; Python executable spec implemented.

## Purpose

The executable spec exposes four narrow process boundaries:

```text
FastAPI / SDK
  -> ResourcesCommandClient
     -> WorkerGroup and Worker upsert

CLI / FastAPI
  -> KernelApplication
     -> Task lifecycle commands
     -> private Redis composition root
     -> assignment-dispatch and result-routing background applications

Worker Adapter Server
  -> DeliverSeedConsumerClient
  -> SeedResultCommandClient
     -> WorkerId poll and opaque SeedResult append without KernelApplication lifecycle
```

All four surfaces expose commands, not runtime objects. Callers cannot obtain
Task/Worker score cores, candidate runtime, matcher, pacers, Redis keys,
suffixes, or lane ranks.

## Public Commands

```text
ResourcesCommandClient
upsert_worker_group
upsert_worker

KernelApplication
create_task
approve_task
append_task_items
close_task

DeliverSeedConsumerClient
consume_deliver_seed(endpointManagerId, workerId) -> DeliverSeed | None
consume_deliver_seeds(endpointManagerId, cursor, scanCount)
  -> DeliverSeedConsumePage

SeedResultCommandClient
append_seed_results
```

WorkerGroup upsert reuses `WorkerGroupDescriptor`. Worker upsert accepts the
caller-owned `WorkerDeclaration`; the complete `WorkerDescriptor` remains a
query projection containing platform-owned attributes. The Kernel Command
Server owns its HTTP request models because they are protocol-edge translations.

First Worker upsert selects the default lane rank internally and initializes
the Worker HOT score without requiring the scheduling process to be running.
Reconnect replaces Worker attributes and supplies trusted serviceability
evidence. Existing scores converge to HOT_ACQUIRE, preserve timeSlot/laneRank,
and set dirty=1 without releasing a hold.
`create_task` selects the initial PRE_REVIEW owner code internally. It is a
create-only command: an existing descriptor conflicts and is never overwritten.
It may complete a score-only interrupted creation only while the score remains
PRE_REVIEW and the descriptor key is absent.
`approve_task` is an explicit lifecycle command that validates Task metadata
and current score band, then requests `PRE_REVIEW -> ADMISSION_VISIBLE`, using
the Task priority as the admission suffix and the approval time as the new lane
coordinate. It
returns `TaskApprovalResult` without exposing score evidence.
`close_task` is the common explicit termination command for both Task types and
all positive bands. It returns `TaskCloseResult`, chooses terminal score
internally, is idempotent after terminal, and does not retract existing Item,
DeliverSeed, or result evidence.
The caller owns the close decision and its business evidence. For
`ITEM_DRIVEN`, a server or other control-plane owner may call this command from
deadline or completion evidence; `KernelApplication` does not infer completion
from an empty Item set.
`append_task_items` enforces the Task's immutable `taskType`.
`TASK_DRIVEN` forbids Item rules; `ITEM_DRIVEN` requires them and validates each rule
against the selected WorkerGroup `itemAllocationFields` and installed
candidate-query handlers before delegating valid records to TaskRuntime. Item
rules cannot change WorkerGroup. The common `TASK_DRIVEN` path performs no WorkerGroup
or dynamic-index read during append.

The assembly does not accept acquisition strategy, cache participation, or
rule-owner configuration. Scheduling derives those decisions from the two
fixed Task types through the internal task scheduling profile resolver.

Dynamic attribute mutation is not a public assembly command. The executable
spec has no installed external handler registry, so exposing that command would
advertise a route that cannot perform a real owner update.
Zero-config assembly also installs no dynamic candidate index; declared
`workerId` `$eq/$in` remains the built-in TARGETED Item source.

## Zero Configuration

Both forms use the same immutable internal defaults:

```python
application = KernelApplication()
application = KernelApplication.from_json("{}")
resources = ResourcesCommandClient()
resources = ResourcesCommandClient.from_json("{}")
deliver_seeds = DeliverSeedConsumerClient()
seed_results = SeedResultCommandClient()
```

The optional JSON contract is:

```json
{
  "redis": {
    "url": "redis://localhost:6379/15",
    "prefix": "default"
  },
  "assignmentDispatch": {
    "workerAllocationIntervalMillis": 100,
    "runningActivationIntervalMillis": 100,
    "taskDispatchIntervalMillis": 100
  },
  "resultRouting": {
    "intervalMillis": 100
  },
  "systemPolicy": {
    "runningTaskSoftLimit": 100
  },
  "stopTimeoutMillis": 5000
}
```

Every field may be omitted. Unknown fields, malformed JSON, empty strings,
wrong types, and non-positive numeric values fail during construction. Batch,
scan, lease, claim, score, lane, ADMISSION priority-recheck step, maximum
empty-recheck count, and empty-recheck interval remain internal constants.
`systemPolicy.runningTaskSoftLimit` is the one public policy setting in this
slice; it defaults to `100` and must be a positive integer. It is a soft
admission bound, not an atomic permit or hard capacity promise.

## Lifecycle

```text
KernelApplication.start()
  -> reject duplicate start
  -> Redis PING fail-fast
  -> start result-routing loop
  -> start allocation, activation, and Task-dispatch loops

KernelApplication.stop()
  -> no-op before start or after clean stop
  -> stop assignment-dispatch loops
  -> stop result-routing loop
  -> keep the application started if stop times out
```

Task commands require a successful application start. DeliverSeed consumption
and SeedResult append use independent clients with no `start` or `stop`, so an
Worker Delivery Dispatch process does not own scheduler lifecycle. Construction
establishes the composition graph but performs no Redis I/O. The private process
root does not close the Redis client on stop, so a clean application instance
may restart.

`ResourcesCommandClient`, `DeliverSeedConsumerClient`, and
`SeedResultCommandClient` have no `start` or `stop`. Each may use a separate
redis-py pool while sharing the same URL, prefix, and Redis owner truth.

## Background Loop Contract

`KernelApplication` composes two independent internal applications:

```text
AssignmentDispatchApplication
  -> worker-allocation loop
  -> running-activation loop
  -> Task-dispatch loop

ResultRoutingApplication
  -> SeedResult-routing loop
```

The composition root creates one `ResultRoutingBuiltinPolicies`, obtains its
default Task and Worker handler mappings, and injects them into
`ResultRoutingPacer`. The Pacer itself depends only on `SeedResultRuntime` and
the stable handler contracts; Task runtime, TaskItem score, and Worker score
dependencies belong to the selected policy object.

For Task activation, the composition root installs
`DueTaskItemAdmissionPolicy` and
`RunningSoftLimitSystemAdmissionPolicy`. The activation Pacer receives these
policies as dependencies; it does not inspect Worker capacity or candidate
queues. An ADMISSION Task with a due Item may enter RUNNING before any Worker
is registered. Worker allocation starts only after that transition.

The composition root also installs one Redis-backed
`CandidateWarmupSchedule`. TASK_DRIVEN activation and PRECOMPUTED dispatch emit
derived warmup hints; the worker-allocation loop consumes those hints and never
uses Task score as its own cursor or writer. It only batch-validates current
RUNNING/non-hard-pause suffix-zero state. Task dispatch owns RUNNING same-band
pacing, exact empty-count increment/reset, and shared threshold-based empty
close. `KernelApplication.create_task` resolves omitted `emptyCloseAtMillis` to
zero for TASK_DRIVEN or creation time plus three days for ITEM_DRIVEN before
calling TaskRuntime.

Each assignment-dispatch loop has one non-daemon thread and its own configured
interval. A loop executes its first bounded round immediately, runs at most one
round at a time, waits for its interval after the round returns, and logs a
failed round before continuing. A slow round therefore cannot create overlap or
a catch-up burst, and one pacer's latency or failure does not block another
pacer.

Result routing has a separate non-daemon thread and cadence. Kernel startup
starts result routing before assignment-dispatch; shutdown stops
assignment-dispatch before result routing. Partial startup rolls back any
already-started internal application.

`stopTimeoutMillis` is one shared deadline for joining all internal threads.
Stop signals interrupt loop waits but do not cancel an in-flight Redis call or
claim a blocked round stopped. A timeout is reported rather than hidden.

The application lifecycle owns timers and process coordination only. It does
not construct policy inside a pacer, combine rounds into one sequential loop,
own score or runtime truth, consume DeliverSeed mailboxes, or turn append/result/
heartbeat events into required wakeups.

## Process-Boundary E2E Proof

The executable spec proves both `TASK_DRIVEN` and `ITEM_DRIVEN` through the
same external process boundaries:

```text
ResourcesCommandClient
  -> KernelApplication
  -> Redis scheduling truth
  -> Worker Adapter HTTP command
  -> Worker Adapter HTTP result
  -> Result-Routing
  -> TaskItem FINAL_SUCCESS + result HASH + Worker lease release
```

The proof uses the Worker Adapter HTTP boundary with real Redis clients.
HTTP remains protocol translation rather than scheduling truth. Separate Redis
proofs cover TASK_DRIVEN default empty close with RUNNING soft-limit release,
ITEM_DRIVEN future-threshold empty recheck followed by append and dispatch,
shared explicit threshold close, and public close remaining terminal. A
separate hard-deadline scanner remains deferred.

## External Hosts

The built-in CLI starts the application with defaults or one JSON file:

```text
python -m kernel_design.executable_spec.assembly
python -m kernel_design.executable_spec.assembly --config kernel.json
```

The Kernel Command Server constructs `KernelApplication` and
`ResourcesCommandClient` from one resolved configuration. Lifespan starts and
stops only `KernelApplication`; it has no DeliverSeed or SeedResult route.

The independent Worker Adapter Server is configured with one
`endpointManagerId` and constructs only
`DeliverSeedConsumerClient` and `SeedResultCommandClient`. It exposes:

```text
POST /workers/{workerId}/commands:poll
POST /workers/{workerId}/results
```

Its command id and message type are Adapter-private wire fields. They are not
added to DeliverSeed, SeedResult, score, or result context.

These are independent caller boundaries, not a claim that production must
deploy exactly two processes. The current Worker Adapter slice has no login,
session, or authorization protocol. The configured endpoint manager selects the
mailbox bucket; the path WorkerId selects one field inside that bucket.
A future Worker-facing API may compose login/session, poll, and result under
the same Adapter owner, but KernelApplication must not own or expose that
session.

The examples are not production services. Authentication, Task query/list,
result projection, production transport, and API compatibility remain out of
scope.

## Guardrails

- Do not re-export the private Redis composition root.
- Do not expose owner runtime instances as application properties.
- Do not add scheduler lifecycle methods to `ResourcesCommandClient`.
- Do not restore dynamic attribute mutation until a real handler owner and
  assembly contract exist.
- Do not add a second environment-variable or CLI configuration path.
- Do not let HTTP handlers perform score reads or transitions.
- Do not expose DeliverSeed consume or SeedResult ingress from the Kernel
  Command Server.
- Do not turn internal Pacer configuration into public JSON without a concrete
  operational requirement.
- Keep result-routing and assignment-dispatch as separate internal application
  lifecycles even though `KernelApplication` composes both.
