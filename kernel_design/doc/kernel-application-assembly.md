# Kernel Application Assembly

Status: active new-kernel application contract; Python executable spec implemented.

## Purpose

The current system exposes narrow control, data, and delivery boundaries:

```text
FastAPI / SDK
  -> ResourcesCommandClient
     -> WorkerGroup and Worker upsert

CLI / FastAPI
  -> KernelApplication
     -> Task lifecycle commands
     -> private Redis composition root
     -> assignment-dispatch and result-routing background applications

Java Runtime API Server
  -> controllers/services depend on Kernel owner contracts
  -> assembly binds control operations to Python HTTP providers
  -> assembly binds selected data/delivery operations to Java Redis providers
```

External callers see commands, not runtime objects. Inside the Java process,
controllers and services depend on owner contracts rather than route-shaped
clients or Redis implementations. Callers cannot obtain Task/Worker score
cores, candidate runtime, matcher, pacers, Redis keys, suffixes, or lane ranks.
Only `KernelApplication` starts background scheduling. Java's direct Redis
providers are limited to Task Item append/result read and Worker Delivery
consume/result-ingress operations.

## Application And Executable-Spec Commands

```text
ResourcesCommandClient
upsert_worker_group
upsert_worker

KernelApplication
create_task
approve_task
close_task

WorkerCommandConsumerClient
consume_worker_command(endpointManagerId, workerId)
  -> WorkerCommandEnvelope | None
consume_worker_commands(endpointManagerId, cursor, scanCount)
  -> WorkerCommandConsumePage

SeedResultCommandClient
append_seed_results(SeedResult...)

JVM TaskRuntime provider
appendItems(taskId, items)
loadTaskItemSuccessResults(taskId, messageIds)
```

`ResourcesCommandClient` and `KernelApplication` back the Python command host.
The two Worker Delivery clients remain stable Python executable-spec and test
support surfaces; they are not mounted as Python HTTP routes. The Java Gateway
implements the public Worker Delivery operations against the same Redis shape.
`TaskRuntime.append_items` and `load_task_item_success_results` likewise remain
the Python mechanism oracle. The public Task data HTTP operations are
orchestrated by Java `TaskDataService` and delegated to the Java
`RedisTaskRuntime` provider through the same owner contract; Python exposes no
TaskItem append or result-query route.

The JVM incremental assembly is explicit per operation:

```text
WorkerGroup upsert              -> Python HTTP WorkerResourceCatalog provider
Worker upsert                   -> Python HTTP WorkerRuntime provider
Task create                     -> Python HTTP TaskRuntime provider
Task approve / close            -> Python HTTP application commands
Task / WorkerGroup reads        -> Java Redis catalog providers
TaskItem append / result load   -> Java Redis TaskRuntime provider
WorkerCommand consume           -> Java Redis WorkerCommandRuntime provider
SeedResult append               -> Java Redis SeedResultRuntime provider
score / candidate / scheduling  -> no Server provider
```

Unimplemented JVM owner operations fail explicitly. They are not forwarded to
Python and do not silently select another provider.

WorkerGroup upsert reuses `WorkerGroupDescriptor`. Worker upsert accepts the
caller-owned `WorkerDeclaration`; the complete `WorkerDescriptor` remains a
query projection containing platform-owned attributes. The Kernel Runtime
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
Java TaskData append enforces the Task's immutable `taskType`.
`TASK_DRIVEN` forbids Item rules. `ITEM_DRIVEN` requires a complete rule and
the first Java cutover supports only bounded `workerId $eq/$in`, declared by
the selected WorkerGroup `itemAllocationFields`. Dynamic candidate sources
remain supported by the Python mechanism oracle and tests but are not exposed
through the first Java TaskData ingress. Item rules cannot change WorkerGroup.

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
worker_commands = WorkerCommandConsumerClient()
worker_results = SeedResultCommandClient()
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

Task commands require a successful application start. Worker command
consumption and Worker result ingress do not own scheduler lifecycle.
Construction establishes the composition graph but performs no Redis I/O. The
private process root does not close the Redis client on stop, so a clean
application instance may restart.

`ResourcesCommandClient`, `WorkerCommandConsumerClient`, and
`SeedResultCommandClient` have no `start` or `stop`. Each may use a separate
redis-py pool while sharing the same URL, prefix, and Redis owner truth. The
latter two remain Python executable-spec/test-support clients; Java owns the
public Worker Delivery HTTP operations.

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
own score or runtime truth, consume WorkerCommand mailboxes, or turn
append/result/heartbeat events into required wakeups.

## Process-Boundary E2E Proof

Cross-process integration proves both `TASK_DRIVEN` and `ITEM_DRIVEN` through
the current external process boundaries:

```text
Java control API -> Python KernelApplication
  -> Java TaskData append
  -> Redis scheduling truth
  -> Java Server Worker Delivery HTTP command access
  -> Java polling Worker or HTTP-consuming WebSocket Adapter
  -> Java phone tool execution
  -> Java Server Worker Delivery HTTP SeedResult ingress
  -> Result-Routing
  -> TaskItem FINAL_SUCCESS + result HASH + Worker lease release
  -> Java last-success result query
```

The proof starts resource and Task-control commands at the Java API, crosses
the Python Kernel Control API, appends TaskItems through Java TaskData, then
uses the Java Server's Worker Delivery owner providers. Polling calls the point
HTTP API directly. WebSocket uses the same Server batch HTTP API in both
embedded and standalone Adapter deployments. Java never parses Task or Worker
score state. Separate Redis proofs cover TaskData Item-score
initialization, TASK_DRIVEN default empty close with RUNNING soft-limit
release, ITEM_DRIVEN future-threshold empty recheck followed by append and
dispatch, shared explicit threshold close, and public close remaining
terminal.

## External Hosts

The built-in CLI starts the application with defaults or one JSON file:

```text
python -m kernel_design.executable_spec.assembly
python -m kernel_design.executable_spec.assembly --config kernel.json
```

The Python Kernel Control API constructs `KernelApplication` and
`ResourcesCommandClient` from one resolved configuration. Lifespan starts and
stops only `KernelApplication`:

```text
python -m kernel_design.runtime_server
python -m kernel_design.runtime_server --config kernel.json
```

The Java Runtime API Server exposes Task data and Worker Delivery at port
`18082`:

```text
POST /api/v1/tasks/{taskId}/items
POST /api/v1/tasks/{taskId}/results:load
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/commands:poll
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/results
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append
```

The two Worker-specific operations are point Worker access. Pure polling
Workers bind to the fixed logical `system-polling` endpoint manager. They
cannot scan the mailbox. Cursor consume and batch result append are
long-lived Adapter operations and reject the built-in polling identity.

`WorkerCommandEnvelope` is the Kernel-defined transport-neutral outbound
command DTO. Task Dispatch generates its canonical UUID `commandId`,
`TASK_ITEM` message type, execute-before deadline, and opaque DeliverSeed.
Worker results use `SeedResult` directly and copy only `commandId` for trace
correlation. Result ingress does not carry command message type or deadline.

The Java Worker is a separate process. It knows only its WorkerId, optional
polling endpoint-manager binding, Java Runtime API URL, Worker Delivery
envelopes, and the `telecom.phone.inspect` tool. It does not register resources
or import Kernel owners. Polling and WebSocket share one serial command
execution core.

Polling is a base request-driven protocol, not an independently deployed
Adapter. The Java WebSocket Adapter owns one configured non-`system-polling`
endpoint manager, cursor-consumes that sparse mailbox through the Server batch
HTTP API, maintains one process-local session generation per WorkerId, and
pushes the same WorkerCommandEnvelope. It can be embedded in `server_jvm` or
started independently on port `18083`; embedded mode deliberately uses HTTP
loopback instead of an in-process shortcut. Workers upsert before connecting.
The Adapter has no Kernel/Redis dependency, login, or authorization protocol,
and KernelApplication does not own or expose session facts.

The Python Runtime Server remains the scheduling command host and mechanism
oracle. Java TaskData and Worker Delivery own their current external HTTP
operations, not a second scheduler. The Java Worker is the only external
Worker demonstration mainline. Authentication, multi-instance WebSocket
ownership, Task query/list, failure-result projection, reliable pending/ack
delivery, and API compatibility remain out of scope.

## Guardrails

- Do not re-export the private Redis composition root.
- Do not expose owner runtime instances as application properties.
- Do not add scheduler lifecycle methods to `ResourcesCommandClient`.
- Do not restore dynamic attribute mutation until a real handler owner and
  assembly contract exist.
- Do not add a second environment-variable or CLI configuration path.
- Do not let HTTP handlers perform score reads or transitions.
- Do not restore Python TaskItem append or result-query HTTP routes.
- Do not expose Worker Delivery methods on `KernelApplication`.
- Do not restore Python Worker Delivery HTTP routes. Python transport clients
  remain executable-spec and test-support surfaces.
- Do not let Java Worker Delivery Redis code append WorkerCommand, consume
  SeedResult, or access score/Pacer state.
- Do not let Java TaskData Redis code access Task score, Worker score,
  candidate cache, WorkerCommand mailbox, SeedResult queues, or Pacer state.
- Do not expose cursor scanning through the polling Worker endpoint.
- Do not let the WebSocket Adapter bypass Server batch HTTP through Redis or
  an embedded in-process call.
- Do not turn internal Pacer configuration into public JSON without a concrete
  operational requirement.
- Keep result-routing and assignment-dispatch as separate internal application
  lifecycles even though `KernelApplication` composes both.
