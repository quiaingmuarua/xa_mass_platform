# Kernel Application Assembly

Status: active new-kernel application contract; Python executable spec implemented.

## Purpose

The current system exposes narrow control, data, and delivery boundaries:

```text
Direct Python SDK / executable-spec support
  -> ResourcesCommandClient
     -> WorkerGroup registration and Worker upsert

Java-supervised Python Pacer CLI
  -> KernelApplication
     -> private Redis composition root
     -> assignment-dispatch and serviceability background applications
     -> Result Routing and Serviceability Result are explicitly disabled
  -> Redis URL and scope injected by the Java parent
  -> Java-minted HOT eligibility floor injected for the remaining Pacers
  -> policy JSON cannot declare Redis coordinates in managed mode
  -> exact ready-file token after all Pacers start
  -> stdin EOF stops the application

Java Runtime API Server
  -> controllers/services depend on Kernel owner contracts
  -> assembly binds Task control, Task data, Worker resource, and delivery
     operations to Java Redis providers
  -> starts fixed Java Result Routing and Serviceability Result before Python
  -> owns their aggregate lifecycle and Kernel readiness
```

External callers see commands, not runtime objects. Inside the Java process,
controllers and services depend on owner contracts rather than route-shaped
clients or Redis implementations. Callers cannot obtain Task/Worker score
cores, candidate runtime, matcher, pacers, Redis keys, suffixes, or lane ranks.
Standalone `KernelApplication` starts the complete Python oracle scheduling
set. Managed production starts fixed Java Result Routing and Worker
Serviceability Result, then uses Python only for Assignment and Serviceability
Dispatch. Java's direct Redis
providers implement caller-driven Task commands, Worker resource changes,
Task Item append/result read, and Worker Delivery bridge operations. Java does
not implement Assignment or Serviceability candidate/dispatch Score
operations.

## Application And Executable-Spec Commands

```text
ResourcesCommandClient
register_worker_group
upsert_worker

KernelApplication
create_task
approve_task
close_task
submit_task_call_items(taskId, items)

WorkerCommandConsumerClient
consume_worker_command(endpointManagerId, workerId)
  -> DeliveryCommand | None
consume_worker_commands(endpointManagerId, limit)
  -> workerId -> DeliveryCommand

WorkerResultCommandClient
append_worker_results(DeliveryReport...)

JVM TaskRuntime provider
createTask(descriptor)
appendItems(taskId, items)
loadTaskItemSuccessResults(taskId, messageIds)
scanTaskItemSuccessResults(taskId, cursor, countHint)

JVM Task lifecycle / Call commands
approveTask(taskId)
closeTask(taskId)
submit(taskId, items)
```

`KernelApplication` remains the Python executable command surface and Pacer
assembly.
`ResourcesCommandClient` and the two Worker Delivery clients remain stable
Python executable-spec and test-support surfaces; they are not mounted as
Python HTTP routes. Python has no production network host. The Java Server
Worker Delivery application
implements the public Worker Delivery operations against the same Redis shape.
`TaskRuntime.append_items` and the Task-scoped
`load_task_item_success_results` and the one-page
`scan_task_item_success_results` likewise remain the Python mechanism oracle.
The public ordinary Task data HTTP operations are orchestrated by Java
`TaskDataService` and delegated to the Java `RedisTaskRuntime` provider through
the same owner contract. Create, lifecycle and the bounded Task Call
composition now execute in Java against the same owner keys; there is no
Python Task HTTP fallback.

Direct executable-spec use may still construct `KernelApplicationConfig` with
Redis coordinates. Managed production mode is narrower: Java's
`xa.mass.redis` configuration is authoritative and is copied into the fixed
child environment as `XA_MASS_KERNEL_PACER_REDIS_URL` and
`XA_MASS_KERNEL_PACER_REDIS_SCOPE`. A managed Pacer config containing a
`redis` object is rejected before startup. This is infrastructure address and
data-boundary handoff, not Server interpretation of scheduling policy.

The JVM incremental assembly is explicit per operation:

```text
WorkerGroup register            -> Java Redis WorkerResourceCatalog provider
Worker upsert                     -> Java Redis WorkerRuntime provider
Platform Properties patch       -> Java Redis WorkerResourceCatalog provider
Worker upsert score operations      -> Java Redis WorkerScoreCore provider
Task create                     -> Java Redis TaskRuntime provider
Task approve / close            -> Java Task commands + Redis Score provider
Task / WorkerGroup reads        -> Java Redis catalog providers
ordinary TaskItem append / result load -> Java Redis TaskRuntime provider
Task Call Item submission       -> Java bounded command over Java owners
DeliveryCommand offer / consume   -> Java Redis WorkerCommandRuntime provider
DeliveryReport append               -> Java Redis WorkerResultRuntime provider
Adapter evidence append / consume   -> Java Redis WorkerServiceability provider
Result Routing / Serviceability Result -> fixed Java Pacers over owner contracts
other score/candidate/scheduling -> no Server provider
```

Unimplemented JVM owner operations fail explicitly. They are not forwarded to
Python and do not silently select another provider.

The JVM delivery slice implements the generic non-overwriting
`offerWorkerCommands` operation used by Server DIRECT_CALL and the existing
consume operations. Authoritative `appendWorkerCommands` remains an explicit
JVM gap; Python scheduling continues to own that publication path.

WorkerGroup registration reuses `WorkerGroupDescriptor`. Worker upsert accepts
the caller-owned `WorkerDeclaration`; the complete `WorkerDescriptor` remains
a query projection containing Worker and Platform property snapshots. The
Kernel Runtime Server owns its HTTP request models because they are
protocol-edge translations.

The WorkerGroup owner step atomically creates `attributes` and `eventCodes`;
identical content is a no-op and different content conflicts without
replacement. Server then provisions the Group's fixed Task Call through the
independent Task owners. `workerGroupId` remains the stable scheduling
partition identity. The declared fields are control-plane catalog metadata and
are not consulted by Matcher or Dispatch.

First Worker upsert fixes lane rank at zero and initializes
the Worker HOT score without requiring the scheduling process to be running.
Compatible repeat upsert repairs a missing owner, metadata, properties row, or
score and replaces the complete `workerProperties` snapshot while preserving
`platformProperties` and every existing score. The external Server invokes it
while processing Worker Prepare; the operation itself is not durable connectivity,
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
`close_task` is the common explicit termination command for both allocation
mechanisms and
all positive bands. It returns `TaskCloseResult`, chooses terminal score
internally, is idempotent after terminal, and does not retract existing Item,
DeliveryCommand, or result evidence.
The caller owns an explicit close decision and its business evidence. Task
Dispatch independently applies the persisted `TaskIdleDisposition` only after
proving that the complete ACTIVE Item band is empty: it either exact-closes the
Task or exact-parks it at the Kernel-private idle coordinate.
Java public TaskData append is limited to the public
`PRECOMPUTED_TASK_RULE + CLOSE_WHEN_IDLE` combination and enforces its stable
allocation-rule location contract: Task-level rules are allowed and Item rules
are forbidden. WorkerGroup Task Call submits a `DIRECT_ITEM_RULE` Item through
the separate bounded command and preserves its JSON-compatible rule as opaque
scheduling input. The
Python matcher owns the evolving rule DSL, including candidate derivation,
operators, and fail-closed behavior. Item rules cannot change WorkerGroup.
Ordinary append does not alter Task score. WorkerGroup Task Call uses the
bounded Kernel `TaskCallItemSubmission`. It calls
`try_release_idle_park`, appends at most 100 Items, then calls the same
idempotent operation again. A recognized private park becomes a due RUNNING
score; any valid nearer positive coordinate is a no-op. The command does not
load Task metadata, inspect ACTIVE Items, interpret a Server registration, or
create an urgent scheduling lane. The second call repairs a park installed by
Task Dispatch during the append window.

The Kernel descriptor stores allocation mechanism and idle disposition as
orthogonal facts. Generic public Server Task creation maps only to
`PRECOMPUTED_TASK_RULE + CLOSE_WHEN_IDLE`. WorkerGroup registration also
converges one derived internal
`DIRECT_ITEM_RULE + PARK_WHEN_IDLE` Task. The Kernel owns neither that
Server-managed Task use case nor its deterministic coordinate derivation.

Candidate matching reads canonical Worker and Platform Properties only after a
bounded candidate source has supplied Worker IDs. DIRECT obtains candidates
from either an empty rule's bounded Group score query or an explicit
`workerId` condition; it never scans Worker descriptors. The removed `index.*`
requirement namespace fails closed.

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
    "scope": "profile_default"
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
    "taskScanLimit": 100,
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
absent; an empty object enables it with defaults. `taskScanLimit` defaults to
`100` and must be in `1..100`; it bounds the due `RUNNING_VISIBLE` Task page
from which the Dispatch Pacer derives current WorkerGroups without modifying
Task score. Its HOT plus RECOVERY scan
limits may total at most 100. `probeExcludedEndpointManagerIds` accepts zero to
100 unique non-empty ids; the default excludes `system-polling`. Unknown
fields, malformed JSON, empty strings,
wrong types, and non-positive numeric values fail during construction. Batch,
scan, lease, claim, score, lane, and ADMISSION priority-recheck step remain
internal constants.
`systemPolicy.runningTaskSoftLimit` is the one public policy setting in this
slice; it defaults to `100` and must be a positive integer. It is a soft
admission bound, not an atomic permit or hard capacity promise.

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
redis-py pool while sharing the same URL, scope, and Redis owner truth. The
latter two remain Python executable-spec/test-support clients; Java owns the
public Worker Delivery HTTP operations.

## Background Loop Contract

`KernelApplication` constructs Assignment Dispatch and Result Routing. The
standalone Oracle starts the complete set. Managed CLI mode receives fixed
switches for all three migrated applications and starts only Python Assignment
Dispatch:

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
  -> due-Task-derived Group stale-score discovery loop
```

The Python composition root creates one `ResultRoutingBuiltinPolicies`, obtains its
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
`CandidateWarmupSchedule`. PRECOMPUTED_TASK_RULE activation and PRECOMPUTED dispatch emit
derived warmup hints; the worker-allocation loop consumes those hints and never
uses Task score as its own cursor or writer. It only batch-validates current
RUNNING/non-hard-pause suffix-zero state. Task dispatch owns RUNNING same-band
pacing, exact private idle park/unpark, and exact idle close. Task creation does
not accept or synthesize an idle timestamp.

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

Production replaces both Result loops and Serviceability Dispatch with Java.
`KernelPacerAssembly` starts Java `ResultRoutingApplication`, optional Java
`WorkerServiceabilityResultApplication`, optional Java
`WorkerServiceabilityDispatchApplication`, then the managed Python Assignment
child. Shutdown closes the child and stops the three Java applications in
reverse order using one shared deadline. Java mints the one process-local HOT
eligibility floor and passes it to Java Serviceability Result, Java
Serviceability Dispatch, and the remaining Python Assignment Pacer.
The Python Oracle keeps its complete standalone order and self-minted floor for
parity tests.

`stopTimeoutMillis` is one shared deadline for joining all internal threads.
Stop signals interrupt loop waits but do not cancel an in-flight Redis call or
claim a blocked round stopped. A timeout is reported rather than hidden.

The application lifecycle owns timers and process coordination only. It does
not construct policy inside a pacer, combine rounds into one sequential loop,
own score or runtime truth, or consume DeliveryCommand mailboxes. Task Call
submission is a synchronous bounded application command, not a background
process, inbox, or second Task selector.

## Process-Boundary E2E Proof

Cross-process integration proves both `PRECOMPUTED_TASK_RULE` and `DIRECT_ITEM_RULE` through
the current external process boundaries:

```text
Java Worker resource API -> Java Redis owner providers
Java Task control API -> Java Redis Task owners
  -> ordinary Java TaskData append, or bounded Java Task Call submission
  -> Redis scheduling truth
  -> Java Server Worker Delivery HTTP command access
  -> Java polling Worker or Netty WebSocket/Socket Adapter instance + Worker
  -> Java phone tool execution
  -> Java Server Worker Delivery HTTP DeliveryReport ingress
  -> Result-Routing
  -> TaskItem FINAL_SUCCESS + result HASH + Worker lease release
  -> Java single-Item last-success result probe / result query
```

The proof starts Worker resource and Task commands at the Java API. Both write
through Java Redis owner providers. The same Java Server supervises the Python
CLI child that runs Pacers against the shared Redis state, and its readiness
reflects that child's lifecycle. The proof then uses the Java Server's Worker
Delivery owner providers.
`PRECOMPUTED_TASK_RULE` polling
calls the point HTTP API directly. The proof registers each WorkerGroup and its
attached Task Call, then `DIRECT_ITEM_RULE` uses configured WebSocket or Socket Adapter
instances, each of which still calls the same batch HTTP contract through
loopback. The Task-ID call path holds one bounded synchronous HTTP wait
while a shared Java virtual thread probes one
Task-scoped messageId at a time. Duplicate waits for that same TaskItem may
share the observation. Finite Task callers do not use that waiter or probe:
they append caller-bounded ordinary Item chunks and may export success Results
only after observing Task terminality. Java TaskData and
transport code never parse Task or Worker score state. The Java
`RedisWorkerRuntime` alone invokes the bounded Worker score operations needed
for resource declaration: score read and missing-score initialization.
Separate Redis proofs cover TaskData Item-score initialization,
`PRECOMPUTED_TASK_RULE + CLOSE_WHEN_IDLE` releasing RUNNING soft-limit capacity,
`DIRECT_ITEM_RULE + PARK_WHEN_IDLE` exact park/unpark followed by dispatch, and
the generic finite public close remaining terminal. The managed Task Call Task
is not exposed through generic lifecycle routes.

## Production Host

The built-in CLI remains directly usable for executable-spec work:

```text
python -m kernel_design.executable_spec.assembly
python -m kernel_design.executable_spec.assembly --config kernel.json
```

Production does not invoke a Python HTTP host. Java Server starts the same fixed
module with a config path, instance token, and ready-file path:

```text
python -u -m kernel_design.executable_spec.assembly \
  --config kernel.json \
  --instance-token <java-generated-uuid> \
  --ready-file <java-owned-state-directory>/ready \
  --without-result-routing \
  --without-worker-serviceability-result \
  --without-worker-serviceability-dispatch \
  --hot-eligibility-floor-millis <java-owned-floor>
```

The managed-only switches and floor are rejected without the token/ready-file
protocol and do not bypass normal config validation. The floor is present only
when Worker Serviceability is configured. The CLI writes the exact token only
after all remaining Python Pacers start, then blocks on stdin. EOF or
interruption stops the application and removes its owned ready file. Java owns
startup timeout,
bounded shutdown, non-destructive historical-state checks, readiness, and final
termination of the exact child it started. The process owner does not interpret
Pacer policy; fixed `kernel_jvm` config decoders read the fields owned by the
three Java applications. Worker/Adapter assembly starts only after the child is
ready and closes before the child. A dead or PID-reused owner record is
cleaned. A record matching a live PID and start instant blocks startup; Java
never kills a process recovered only from disk state. If the operating system
cannot expose the start instant, the live process is left untouched and Server
startup fails for explicit operator recovery.

The Java Runtime API Server exposes Worker resources, Task data, and Worker
Delivery at port `18082`:

```text
POST /api/v1/worker-groups/{workerGroupId}:register
POST /api/v1/worker-groups/{workerGroupId}/workers:prepare
POST /api/v1/runtime-view/worker-groups:preview
POST /api/v1/tasks
POST /api/v1/tasks/{taskId}/items
POST /api/v1/tasks/{taskId}/items:call
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

WorkerGroup registration creates the Group through its owner, derives one
internal Task ID, and converges the exact
`DIRECT_ITEM_RULE + PARK_WHEN_IDLE` descriptor plus approval through existing
Task owners. It adds no Server Redis key or mutable WorkerGroup-to-Task mapping.
Success guarantees both resources; an exact repeat is `already_registered`
only when both already exist, while a missing Task is backfilled and descriptor
drift conflicts. Group create, Task create and approval are separate owner
operations rather than a cross-key transaction. A Task-side failure may leave
the Group created; retrying the exact registration re-reads owner truth and
converges. Registration returns the managed Task ID; callers use that value or
the configured Runtime projection rather than deriving the deterministic ID.
The Task call route validates that descriptor once and submits `1..100`
standard Items through the bounded Task Call command. WorkerGroup remains Task
descriptor scope and is not copied into an Item; the call response exposes no
selected Worker. The request waits only for its bounded interval. Once
submission is accepted, HTTP `200` returns one outcome per Message ID: observed
successes are `succeeded`, while entries missing at the end of the bounded
observation are `not_observed`. The shared Task result route supports a later
bounded read by Message ID.

Generic Task creation is scoped by an existing WorkerGroup. Server generates
the `task-{UUID}` coordinate and always assembles
`PRECOMPUTED_TASK_RULE + CLOSE_WHEN_IDLE`; the request carries WorkerGroup ID
but no Task ID, mechanism or profile selector. Public Item requests
retain caller-owned Message IDs while Server stamps creation time and derives
optional absolute expiry from `ttlMillis`. Lifecycle, Item and result routes
continue under `/api/v1/tasks/{taskId}/...`. Managed Task lifecycle and normal
append remain non-public, while Task Call and result load use the returned
managed Task ID.

WorkerGroup registration is create-only control-plane setup with attached Task
Call provisioning: an equivalent complete registration is idempotent and a
different declaration conflicts without changing the existing Group. The
bounded Runtime View preview performs one random HASH sample and makes no list,
order, count, or completeness claim.

Worker Prepare receives complete Worker Properties and maps
`workerGroupId + workerProperties.clientWorkerKey` to a long-lived
platform-issued Worker UUID in a Server-owned namespace; other Properties do
not enter that coordinate. It then verifies or establishes the separate
Endpoint Binding and invokes Kernel Worker upsert with the same snapshot.
Prepare is deliberately multi-owner and retry-convergent rather than a
cross-key transaction. It is the canonical Worker Properties refresh point.
Transparent Client reconnect sends only connection identity, while Adapter
properties snapshots remain process-local observation and never invoke a
Kernel write owner.
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

The Worker host knows its WorkerGroup/client key for Prepare, then
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
Workers Prepare before connecting; the one control call carries the complete
Worker Properties snapshot and returns the resolved identity and endpoint. The connection
identity Report carries WorkerId in `sourceId` and exact `null` payload; the
Adapter asks Server only whether that WorkerId's persisted
Binding points to the receiving Endpoint Manager before activating the Channel
without an ACK. Effective route changes return through the existing Adapter
Result API into the Kernel-owned bounded evidence LIST. Server validates and
appends but does not interpret the event. The fixed Java Worker Serviceability
Result Pacer consumes the evidence and composes Worker score owner operations.

Python remains the scheduling mechanism oracle and the temporary production
implementation of Assignment Dispatch. Java owns production Result Routing,
Serviceability Result, and Serviceability Dispatch, is the only production
process entry, and supervises the fixed CLI child. Java also owns the external Task commands, Worker
resource ingress, TaskData, and Worker Delivery operations, without becoming a
second scheduler. The Java
Worker is the only external Worker demonstration mainline. Authentication,
same-endpoint Adapter HA,
Task query/list, failure-result projection, reliable pending/ack delivery, and
API compatibility remain out of scope.

## Guardrails

- Do not re-export the private Redis composition root.
- Do not expose owner runtime instances as application properties.
- Do not add scheduler lifecycle methods to `ResourcesCommandClient`.
- Do not add an `index.*` projection owner or matching fallback through
  assembly.
- Do not add a second operator-facing environment-variable or CLI
  configuration path. The fixed parent-to-child Redis environment is internal
  lifecycle handoff and must never compete with a policy value.
- Do not let HTTP handlers perform score reads or transitions.
- Do not restore any Python production HTTP host or Task business route.
- Server process supervision passes the Pacer JSON through; only the Java
  Result Routing and Worker Serviceability Result config decoders read their
  finite owned fields. Server must not interpret score, candidate, dispatch,
  retry, recovery, or result policy.
- Do not expose Worker Delivery methods on `KernelApplication`.
- Do not restore Python Worker Delivery HTTP routes. Python transport clients
  remain executable-spec and test-support surfaces.
- Java Worker Delivery Redis code may append and consume its Result LISTs, but
  must not decode Result Context or access score/Pacer state.
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
