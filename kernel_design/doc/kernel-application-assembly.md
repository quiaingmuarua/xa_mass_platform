# Kernel Application Assembly

Status: active new-kernel application contract; Python executable spec implemented.

## Purpose

The current system exposes narrow control, data, and delivery boundaries:

```text
Direct Python SDK / executable-spec support
  -> ResourcesCommandClient
     -> WorkerGroup upsert and Worker upsert

CLI / task-only FastAPI
  -> KernelApplication
     -> Task lifecycle commands
     -> private Redis composition root
     -> assignment-dispatch and result-routing background applications

Java Runtime API Server
  -> controllers/services depend on Kernel owner contracts
  -> assembly binds Task control operations to Python HTTP providers
  -> assembly binds Worker resource, Task data, and delivery operations to
     Java Redis providers
```

External callers see commands, not runtime objects. Inside the Java process,
controllers and services depend on owner contracts rather than route-shaped
clients or Redis implementations. Callers cannot obtain Task/Worker score
cores, candidate runtime, matcher, pacers, Redis keys, suffixes, or lane ranks.
Only `KernelApplication` starts background scheduling. Java's direct Redis
providers implement WorkerGroup upsert, Worker upsert,
Task Item append/result read, and Worker Delivery consume/result-ingress
operations. Java Worker upsert uses only score get/initialize.

## Application And Executable-Spec Commands

```text
ResourcesCommandClient
upsert_worker_group
upsert_worker

KernelApplication
create_task
approve_task
close_task
wake_task_dispatch(taskIds)

WorkerCommandConsumerClient
consume_worker_command(endpointManagerId, workerId)
  -> DeliveryCommand | None
consume_worker_commands(endpointManagerId, limit)
  -> workerId -> DeliveryCommand

WorkerResultCommandClient
append_worker_results(DeliveryReport...)

JVM TaskRuntime provider
appendItems(taskId, items)
loadTaskItemSuccessResults(taskId, messageIds)
```

`KernelApplication` backs the task-only Python command host.
`ResourcesCommandClient` and the two Worker Delivery clients remain stable
Python executable-spec and test-support surfaces; they are not mounted as
Python HTTP routes. The Java Server Worker Delivery application
implements the public Worker Delivery operations against the same Redis shape.
`TaskRuntime.append_items` and the Task-scoped
`load_task_item_success_results` likewise remain the Python mechanism oracle.
The public Task data HTTP operations are
orchestrated by Java `TaskDataService` and delegated to the Java
`RedisTaskRuntime` provider through the same owner contract; Python exposes no
TaskItem append or result-query route.

The JVM incremental assembly is explicit per operation:

```text
WorkerGroup upsert              -> Java Redis WorkerResourceCatalog provider
Worker upsert                     -> Java Redis WorkerRuntime provider
Platform Properties patch       -> Java Redis WorkerResourceCatalog provider
Explicit index update/load      -> Java Redis WorkerPropertyIndexRuntime provider
Worker upsert score operations      -> Java Redis WorkerScoreCore provider
Task create                     -> Python HTTP TaskRuntime provider
Task approve / close            -> Python HTTP application commands
Task / WorkerGroup reads        -> Java Redis catalog providers
TaskItem append / result load   -> Java Redis TaskRuntime provider
Task Dispatch wake hint         -> Python HTTP application command
DeliveryCommand offer / consume   -> Java Redis WorkerCommandRuntime provider
DeliveryReport append               -> Java Redis WorkerResultRuntime provider
other score/candidate/scheduling -> no Server provider
```

Unimplemented JVM owner operations fail explicitly. They are not forwarded to
Python and do not silently select another provider.

The JVM delivery slice implements the generic non-overwriting
`offerWorkerCommands` operation used by Server DIRECT_CALL and the existing
consume operations. Authoritative `appendWorkerCommands` remains an explicit
JVM gap; Python scheduling continues to own that publication path.

WorkerGroup upsert reuses `WorkerGroupDescriptor`. Worker upsert accepts
the caller-owned `WorkerDeclaration`; the complete `WorkerDescriptor` remains
a query projection containing Worker and Platform property snapshots. The
Kernel Runtime Server owns its HTTP request models because they are
protocol-edge translations.

An explicit WorkerGroup upsert atomically replaces its `attributes` and
`eventCodes`; identical content is a no-op. `workerGroupId` remains the stable
scheduling partition identity. The replaced fields are control-plane catalog
metadata and are not consulted by Matcher or Dispatch.

First Worker upsert fixes lane rank at zero and initializes
the Worker HOT score without requiring the scheduling process to be running.
Compatible repeat upsert repairs a missing owner, metadata, properties row, or
score and replaces the complete `workerProperties` snapshot while preserving
`platformProperties` and every existing score. The external Server invokes it
while processing Worker Bind; the operation itself is not durable connectivity,
activation, or serviceability evidence.
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
DeliveryCommand, or result evidence.
The caller owns the close decision and its business evidence. For
`ITEM_DRIVEN`, a server or other control-plane owner may call this command from
deadline or completion evidence; `KernelApplication` does not infer completion
from an empty Item set.
Java TaskData append enforces only the stable TaskType location contract:
`TASK_DRIVEN` forbids Item rules and `ITEM_DRIVEN` requires a non-empty Item
rule. It preserves that JSON-compatible rule as opaque scheduling input. The
Python matcher owns the evolving rule DSL, including candidate derivation,
operators, and fail-closed behavior. Item rules cannot change WorkerGroup.

The assembly does not accept acquisition strategy, cache participation, or
rule-owner configuration. Scheduling derives those decisions from the two
fixed Task types through the internal task scheduling profile resolver.

The Server exposes one explicit indexed-property update command.
Properties and index projections are independent and no assembly operation
automatically writes both. Each configured field owns one property-index
instance. The Redis HASH implementation stores JSON-compatible point values.
DIRECT obtains candidates from either an empty rule's bounded Group score
query or an explicit `workerId` condition; Property indexes never discover
candidates.

## Zero Configuration

Both forms use the same immutable internal defaults:

```python
application = KernelApplication()
application = KernelApplication.from_json("{}")
resources = ResourcesCommandClient()
resources = ResourcesCommandClient.from_json("{}")
worker_commands = WorkerCommandConsumerClient()
worker_results = WorkerResultCommandClient()
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
  "workerServiceability": {
    "workerGroupIds": ["scenario-phone-number-workers"],
    "dispatchIntervalMillis": 1000,
    "resultIntervalMillis": 100,
    "recoveryRetryIntervalMillis": 60000,
    "probeSweepRestartDelayMillis": 10000,
    "evidenceMaxAgeMillis": 30000,
    "maxRecoveryAttempts": 5,
    "hotScanLimit": 80,
    "recoveryScanLimit": 20,
    "resultReportLimit": 10,
    "probeExcludedEndpointManagerIds": ["system-polling"]
  },
  "systemPolicy": {
    "runningTaskSoftLimit": 100
  },
  "stopTimeoutMillis": 5000
}
```

Every top-level field may be omitted. `workerServiceability` is disabled when
absent; when present it requires `workerGroupIds` with 1..100 unique explicit
Groups and all other fields use the shown defaults. Its HOT plus RECOVERY scan
limits may total at most 100. `probeExcludedEndpointManagerIds` accepts zero to
100 unique non-empty ids; the default excludes `system-polling`. Unknown
fields, malformed JSON, empty strings,
wrong types, and non-positive numeric values fail during construction. Batch,
scan, lease, claim, score, lane, ADMISSION priority-recheck step, maximum
empty-recheck count, and empty-recheck interval remain internal constants.
`systemPolicy.runningTaskSoftLimit` is the one public policy setting in this
slice; it defaults to `100` and must be a positive integer. It is a soft
admission bound, not an atomic permit or hard capacity promise.

Property Index registration is not part of Kernel application JSON. Python and
Java read the same process environment value:

```text
XA_MASS_WORKER_PROPERTY_INDEX_REGISTRY_JSON=
  {"index.worker.region":"redis-hash","index.platform.pool":"redis-hash"}
```

The missing value defaults to `{}`. The value must be a JSON object whose keys
are explicit `index.*` projection identities; the current implementation value
is `redis-hash`. Malformed JSON, invalid fields, and unknown implementations
fail process startup. Both processes canonicalize the map, log its field count
and SHA-256 fingerprint, and do not publish a registry through Redis.
WorkerGroup declarations do not create or migrate indexes; an unconfigured
field remains an explicit update/read failure.

## Lifecycle

```text
KernelApplication.start()
  -> reject duplicate start
  -> Redis PING fail-fast
  -> start result-routing loop
  -> if configured, start serviceability-result loop
  -> if configured, start serviceability-dispatch loop
  -> start allocation, activation, and Task-dispatch loops

KernelApplication.stop()
  -> no-op before start or after clean stop
  -> stop assignment-dispatch loops
  -> stop serviceability-dispatch loop
  -> stop serviceability-result loop
  -> stop result-routing loop
  -> keep the application started if stop times out
```

Task commands require a successful application start. Worker command
consumption and Worker result ingress do not own scheduler lifecycle.
Construction establishes the composition graph but performs no Redis I/O. The
private process root does not close the Redis client on stop, so a clean
application instance may restart.

`ResourcesCommandClient`, `WorkerCommandConsumerClient`, and
`WorkerResultCommandClient` have no `start` or `stop`. Each may use a separate
redis-py pool while sharing the same URL, prefix, and Redis owner truth. The
latter two remain Python executable-spec/test-support clients; Java owns the
public Worker Delivery HTTP operations.

## Background Loop Contract

`KernelApplication` always composes Assignment Dispatch and Result Routing. It
also composes two independent Worker Serviceability applications only when the
optional configuration is present:

```text
AssignmentDispatchApplication
  -> worker-allocation loop
  -> running-activation loop
  -> Task-dispatch loop

ResultRoutingApplication
  -> DeliveryReport-routing loop

WorkerServiceabilityResultApplication
  -> Adapter route-snapshot Result loop

WorkerServiceabilityDispatchApplication
  -> configured-Group stale-score discovery loop
```

The composition root creates one `ResultRoutingBuiltinPolicies`, obtains its
default Task and Worker handler mappings, and injects them into
`ResultRoutingPacer`. The Pacer itself depends only on `WorkerResultRuntime` and
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

Result routing has a separate non-daemon thread and cadence. Each enabled
Serviceability application owns one additional non-daemon thread. Kernel
startup orders Result Routing, Serviceability Result, Serviceability Dispatch,
then Assignment Dispatch; shutdown reverses that order. Partial startup rolls
back any already-started internal application. The Serviceability Runtime is
not constructed when the optional configuration is absent.

`stopTimeoutMillis` is one shared deadline for joining all internal threads.
Stop signals interrupt loop waits but do not cancel an in-flight Redis call or
claim a blocked round stopped. A timeout is reported rather than hidden.

The application lifecycle owns timers and process coordination only. It does
not construct policy inside a pacer, combine rounds into one sequential loop,
own score or runtime truth, or consume DeliveryCommand mailboxes. Its bounded
Task Dispatch wake inbox is optional acceleration: it coalesces taskIds and
may ask Task Dispatch to exact-release an existing future empty-recheck hold.
It does not make append acceptance or scheduling liveness depend on an event.

## Process-Boundary E2E Proof

Cross-process integration proves both `TASK_DRIVEN` and `ITEM_DRIVEN` through
the current external process boundaries:

```text
Java Worker resource API -> Java Redis owner providers
Java Task control API -> Python KernelApplication
  -> Java TaskData append
  -> optional HTTP Task Dispatch wake hint
  -> Redis scheduling truth
  -> Java Server Worker Delivery HTTP command access
  -> Java polling Worker or Netty WebSocket/Socket Adapter instance + Worker
  -> Java phone tool execution
  -> Java Server Worker Delivery HTTP DeliveryReport ingress
  -> Result-Routing
  -> TaskItem FINAL_SUCCESS + result HASH + Worker lease release
  -> Java single-Item last-success result probe / result query
```

The proof starts Worker resource and Task-control commands at the Java API.
Worker declarations go directly to the Java Redis providers; only Task control
crosses the Python Kernel Task Control API. It then appends TaskItems through
Java TaskData and uses the Java Server's Worker Delivery owner providers.
`TASK_DRIVEN` polling
calls the point HTTP API directly. `ITEM_DRIVEN` uses configured WebSocket
or Socket Adapter instances, each of which still calls the same batch HTTP
contract through loopback. The WorkerGroup Point RPC path holds one
asynchronous HTTP waiter while a shared Java virtual thread probes one
Task-scoped messageId at a time. Duplicate waits for that same TaskItem may
share the observation. The Server Task Batch Lab path does not use that waiter
or probe: it appends one caller-bounded Item batch, then loads the
remaining message IDs together on each polling round. Java TaskData and
transport code never parse Task or Worker score state. The Java
`RedisWorkerRuntime` alone invokes the bounded Worker score operations needed
for resource declaration: score read and missing-score initialization.
Separate Redis proofs cover TaskData Item-score
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

The Python Kernel Task Control API constructs one `KernelApplication` from the
resolved configuration. Lifespan starts and stops only that application:

```text
python -m kernel_design.runtime_server
python -m kernel_design.runtime_server --config kernel.json
```

The Java Runtime API Server exposes Worker resources, Task data, and Worker
Delivery at port `18082`:

```text
PUT  /api/v1/worker-groups/{workerGroupId}
POST /api/v1/worker-groups/{workerGroupId}/workers:register
POST /api/v1/worker-groups/{workerGroupId}/workers/{workerId}:bind
POST /api/v1/tasks/{taskId}/items
POST /api/v1/worker-groups/{workerGroupId}/items:call
POST /api/v1/tasks/{taskId}/results:load
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/commands:poll
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/results
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}:verify-binding
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append
```

The Group call route resolves a Server profile-owned persistent Task and sends
the standard Item unchanged through Task data. WorkerGroup is the URL
coordinate; it is not copied into the Item, and the response exposes neither
the internal Task ID nor the selected Worker.

Identity registration receives complete Worker Properties and maps
`workerGroupId + workerProperties.clientWorkerKey` to a long-lived
platform-issued Worker UUID in a Server-owned namespace; other Properties do
not enter that coordinate and Register does not call a Kernel owner. Bind
receives the same complete snapshot, verifies its client key, persists an
Endpoint Manager, and invokes Kernel Worker upsert with that snapshot.
Pure polling Workers bind to the fixed logical `system-polling` endpoint
manager and cannot scan the mailbox. Point calls and Adapter connections verify
the persisted route; this is routing consistency, not authentication.
Bounded no-cursor consume and batch result append are long-lived Adapter
operations and reject the built-in polling identity.

`DeliveryCommand` is the Kernel-defined transport-neutral outbound
command DTO. Task Dispatch uses the TaskItem eventCode as `messageType`, writes
the Item payload directly, sets the execute-before deadline, and stores
ResultContext in opaque `forward`. Worker Transports use
`DeliveryReport.fromCommand()` to preserve `messageType` and `forward`, then
declare `src=WORKER` and
`sourceId=workerId`. Adapter pre-delivery rejections declare
`src=ADAPTER + sourceId=adapterId`. Result ingress does not carry a deadline.
Delivery has no outer message or correlation ID.

The Worker host knows its WorkerGroup/client key for Register and Bind, then
receives the platform-issued WorkerId and public endpoint URI. The Worker
Transport knows the WorkerId, endpoint URI, Worker Delivery contracts, and
statically provided event definitions. It does not know a WorkerGroup or an
endpoint-manager ID or import Kernel owners. Polling, WebSocket, and Socket share one serial
execution core; long-lived transports first send an Adapter-directed identity
`DeliveryReport(src=WORKER, sourceId=workerId,
payload="null")` before command exchange.

Polling is a base request-driven protocol, not an independently deployed
Adapter. Each configured Java Adapter instance owns one non-`system-polling`
endpoint-manager mailbox, one independent Netty listener, one scheduled
DeliveryCommand Process, one timed DeliveryReport Process, one current bound
connection per WorkerId, and
one private bounded local queue per Process. A finite scheduled Process list
is owned by the Adapter-local `AdapterProcessManager`, together with its one
same-lifetime scheduler, round isolation, shutdown phase, and reverse finish
order. The Adapter aggregate still owns lifecycle and network ordering. The Server only
parses instance configuration, registers concrete
instances, and invokes Adapter `start()`/`close()` at process boundaries.
Workers Register and establish Endpoint Binding before connecting; the Bind
control call carries the complete Worker Properties snapshot. The connection
identity Report carries WorkerId in `sourceId` and exact `null` payload; the
Adapter asks Server only whether that WorkerId's persisted
Binding points to the receiving Endpoint Manager before activating the Channel
without an ACK. Effective route changes return through the existing Adapter
Result API into a Server-owned bounded inbox, where Server separately validates
the Adapter source and current Binding. Kernel has no
contract, provider, consumer or score policy for that evidence in this slice.

The Python Runtime Server remains the Task scheduling command host and
mechanism oracle. Java Worker resource ingress, TaskData, and Worker Delivery
own their current external HTTP operations, not a second scheduler. The Java
Worker is the only external Worker demonstration mainline. Authentication,
same-endpoint Adapter HA,
Task query/list, failure-result projection, reliable pending/ack delivery, and
API compatibility remain out of scope.

## Guardrails

- Do not re-export the private Redis composition root.
- Do not expose owner runtime instances as application properties.
- Do not add scheduler lifecycle methods to `ResourcesCommandClient`.
- Do not make Properties and indexed-property updates an implicit dual write.
- Do not expose index provider storage or operator selection through assembly.
- Do not add a second environment-variable or CLI configuration path.
- Do not let HTTP handlers perform score reads or transitions.
- Do not restore Python TaskItem append or result-query HTTP routes.
- Do not expose Worker Delivery methods on `KernelApplication`.
- Do not restore Python Worker Delivery HTTP routes. Python transport clients
  remain executable-spec and test-support surfaces.
- Do not let Java Worker Delivery Redis code append DeliveryCommand, consume
  DeliveryReport, or access score/Pacer state.
- Do not let Java TaskData Redis code access Task score, Worker score,
  candidate cache, DeliveryCommand mailbox, DeliveryReport queues, or Pacer state.
- Do not expose batch mailbox acquisition through the polling Worker endpoint.
- Do not let the WebSocket Adapter bypass Server batch HTTP through Redis or
  an in-process call.
- Do not move Adapter consume cadence, current-connection selection,
  result-buffer, Adapter rejection, or `UNKNOWN` policy into the Server
  lifecycle host.
- Do not turn internal Pacer configuration into public JSON without a concrete
  operational requirement.
- Keep result-routing and assignment-dispatch as separate internal application
  lifecycles even though `KernelApplication` composes both.
