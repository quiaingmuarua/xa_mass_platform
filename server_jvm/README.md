# XA Mass JVM Runtime API Server

Status: active external Runtime API with Kernel owner-contract assembly and
opt-in Scenario Worker composition.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. Task create/approve/close bind to Python
HTTP owner providers. WorkerGroup/Worker upsert, TaskItem append, last-success
reads, and Worker Delivery bind to Java Redis owner providers. Controllers and
services depend only on contracts from `kernel_jvm`.

Runtime API failures use the module-local HTTP exception:

```text
ServerException
  -> ServerErrorCode 10000..19999
  -> operation + message + cause
```

Kernel binding, Task Data, and Worker Delivery choose codes from the single
module enum; they do not define exception subclasses. `ApiExceptionHandler`
maps `ServerErrorCode` to HTTP status and emits its integer code in
`ApiErrorResponse`. Spring remains the process logging and tracing boundary,
while exceptions do not carry request IDs or context maps.

Server-side Adapter validation failures use the owner-local
`WorkerAssemblyException`. Scenario capability/resource/Worker startup failures
use the separate module-local `ScenarioWorkerAssemblyException`. Neither is an
HTTP or cross-module exception base.

```text
Task control client
  -> server_jvm /api/v1
  -> TaskRuntime / TaskLifecycleCommands
  -> Python HTTP owner providers
  -> KernelApplication / scheduling truth

Worker resource client
  -> server_jvm /api/v1
  -> WorkerRuntime / WorkerResourceCatalog
  -> Java owner Redis providers
  -> shared Worker descriptor and HOT_ACQUIRE truth

Task data client
  -> Java TaskDataService
  -> TaskRuntime + TaskResourceCatalog + WorkerResourceCatalog
  -> Java owner Redis providers
  -> Python scheduling and ResultRouting

Task RPC client
  -> append through the same TaskDataService validation
  -> optional coalesced Task Dispatch wake command
  -> Server DeferredResult waiter
  -> one shared Java virtual-thread result probe
  -> TaskRuntime single-Item last-success reads

Worker / long-lived Adapter
  -> server_jvm /api/v1/worker-delivery
  -> Java WorkerDeliveryService
  -> WorkerCommandRuntime + WorkerResultRuntime
  -> Java owner Redis providers
  -> Python ResultRouting

Configured built-in Worker bundle
  -> Server validates and starts the configured Adapter
  -> scenario_workers_jvm performs owner upserts
  -> scenario_workers_jvm starts Worker Core + concrete network Client
  -> real configured Adapter listener
  -> the same Worker Delivery HTTP/Redis path
```

The module is Java 21 and Spring Boot 4.1. It depends on `kernel_jvm` contracts
but does not start the Python process. `kernelbinding` composes Task and Worker
control/data providers. `WorkerDeliveryOwnerAssemblyConfiguration` separately
composes only the WorkerCommand and WorkerResult Redis providers. The shared
`kernelredis` package owns only connection and health. Redis key operations are
implemented in owner-local `kernel_jvm` packages. Java does not read Task
scores, invoke Pacers, append Worker commands, or consume WorkerResult queues.
Its Worker score provider implements only get/initialize/reconcile for
`WorkerRuntime.upsertWorker`; scheduling score operations remain unavailable.

Current provider matrix:

| Operation | Provider |
| --- | --- |
| WorkerGroup upsert | Java Redis |
| Worker upsert and HOT_ACQUIRE initialize/reconcile | Java Redis |
| Task create | Python HTTP |
| Task approve/close | Python HTTP application command |
| Task and WorkerGroup descriptor reads | Java Redis |
| TaskItem append and Task-scoped last-success load | Java Redis |
| Task Dispatch wake hint | Python HTTP application command |
| WorkerCommand consume | Java Redis |
| WorkerResult append | Java Redis |
| Other score, candidate, dynamic attribute, scheduling internals | explicit not implemented |

Task Data boundaries:

```text
taskdata
  cross-owner validation, use-case orchestration, error mapping
kernel_jvm task/worker contracts
  owner DTO and operation boundaries
kernel_jvm owner Redis packages
  descriptor reads, Item record/score initialization, last-success reads
```

`TaskDataService` is the only HTTP use-case facade. Re-appending a messageId
replaces its Item record but `ZADD NX` preserves any existing Item score.
Result reads return opaque last-success payload strings or `null`; they do not
infer pending or failure state. The service validates TaskType and WorkerGroup
policy across owners; `RedisTaskRuntime` does not read WorkerGroup truth.
An accepted append offers one taskId-coalesced best-effort wake hint. Queue
overflow, Kernel HTTP failure, or restart may drop it; Task score pacing remains
the liveness mechanism and append success is never rolled back.

Worker Delivery boundaries:

```text
worker_delivery_contract_jvm
  transport-neutral WorkerCommand/WorkerResult/WorkerConnectionBind contracts
  and strict codecs
api.v1.workerdelivery
  point Worker and Adapter batch HTTP access profiles
workerdelivery.application
  Kernel delivery use-case service and application errors
workerdelivery
  HTTP application and delivery-owner composition
kernel_jvm delivery contracts/providers
  WorkerCommand consume and WorkerResult append owner operations

transport/netty-adapter
  complete Adapter instances, independent Netty WebSocket/Socket listeners,
  mailbox loops, active connections, and bounded non-blocking delivery
```

The Server is the only Worker Delivery HTTP and Redis owner. Point and batch
controllers call `WorkerDeliveryService`, which calls the two Kernel delivery
runtime contracts. The shared contract module has no Spring Web or Redis
dependency.

The Adapter runtime is implemented by
[`transport/netty-adapter`](../transport/netty-adapter/README.md).
This Server reads the configured Adapter instance map, creates complete
WebSocket or Socket Adapter instances, registers them, and starts/closes the
manager at process boundaries. Each Adapter owns its Netty listener,
bounded Command/Result loops, current Channel registry, and encoded result
buffer. It consumes the existing batch HTTP API through loopback and has no
in-process or Redis shortcut. Polling continues to exchange WorkerCommand and
WorkerResult through point HTTP.

## Runtime Commands

```text
PUT  /api/v1/worker-groups/{workerGroupId}
PUT  /api/v1/worker-groups/{workerGroupId}/workers/{workerId}
POST /api/v1/tasks
POST /api/v1/tasks/{taskId}/approve
POST /api/v1/tasks/{taskId}/close
POST /api/v1/tasks/{taskId}/items
POST /api/v1/tasks/{taskId}/items:call
POST /api/v1/tasks/{taskId}/results:load
```

WorkerGroup and Worker upsert use Java Redis owner providers. Task
create/approve/close use Python HTTP owner/application providers. Item append
and result load use Java Redis owner providers. Append returns per-message
`appended / not_found / invalid / retryable` status. `TASK_DRIVEN` forbids an
Item rule; `ITEM_DRIVEN` currently accepts only a WorkerGroup-allowed
`workerId $eq/$in` rule.

Result load accepts 1 to 1000 nonblank `messageIds`, removes duplicates in
first-seen order, and returns:

```json
{
  "results": {
    "message-1": "{\"value\":1}",
    "message-2": null
  }
}
```

The payload is opaque. A missing Task returns `404`; `null` means only that no
last-success payload exists for that Task-scoped messageId.

TaskItem RPC v1 accepts one existing `TaskItemRequest` plus an optional
`waitTimeoutMillis` (30 seconds by default, 60 seconds maximum):

```json
{
  "item": {
    "messageId": "call-1",
    "eventCode": "device.rpc",
    "createdAtMillis": 1,
    "payload": {"method": "status"}
  },
  "waitTimeoutMillis": 30000
}
```

The caller supplies the scheduling identity: a new logical call uses a new
`messageId`, while retrying the same logical call reuses its original
`messageId`.

A last-success payload observed in the wait window returns `200` with
`status=succeeded`, `taskId`, `messageId`, and `opaqueResultPayload`.
Otherwise the request returns `202` with `status=pending`, `taskId`, and
`messageId`. Pending does not distinguish executing, retrying, Worker failure,
FINAL_FAILED, or a delayed success. Callers use `results:load` for later reads.
The batch `items` API never creates RPC waiters or aggregate completion state;
batch callers poll `results:load` with their own messageIds.

The Server keeps at most 10,000 HTTP waiters. Duplicate waits for one
Task-scoped messageId are allowed. Request timeout, disconnect, Server
shutdown, and success completion remove the waiter; capacity exhaustion
returns `429`. Requests do not create polling threads. One shared Java virtual
thread consumes one due `(taskId, messageId)` observation at a time and invokes
`TaskRuntime.loadTaskItemSuccessResults(taskId, List.of(messageId))`. Duplicate
HTTP waits for the same TaskItem share that one observation; different
TaskItems are never merged into a result batch. Redis read failure reschedules
only that TaskItem observation and never changes append truth. RPC waiting
does not read TaskItem records or Task/Item scores, and this version performs
no TaskItem record cleanup.

Worker Delivery:

```text
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/commands:poll
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/results
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append
```

`system-polling` may use only the point Worker operations. Bounded batch
consume and batch result append are reserved for long-lived Adapter identities.
The Adapter result request carries an array of encoded `WorkerResult` strings.
The Adapter endpoint itself is the trusted ingress and accepts valid
`200/1xxx/3xxx` results targeting `TASK`. Server appends the valid subset and
returns both `acceptedCount` and `rejectedCount`. The point Worker result
endpoint separately permits only `200/1xxx`.

Management endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness describes this JVM process. Readiness requires both the configured
Python Kernel Task Control API and the shared Kernel Redis connection.

## Run

Start the Python kernel process first:

```text
python -m kernel_design.runtime_server
```

Then start the external Runtime API Server:

```text
./gradlew :server_jvm:bootRun
```

Compose a [Worker Core](../transport/worker-core/README.md) transport with the
[JVM Worker Clients](../transport/okhttp-worker/README.md) in a JVM
application. An Android WebSocket host composes the same Worker Core transport
with the [Android Client](../transport/android-client/README.md), then calls
`WebSocketWorkerTransport.start()`. A polling host calls the Server point API
directly. A WebSocket or Socket host connects to the selected Adapter listener.
These libraries do not provide a CLI or own application lifecycle.

The default Server configuration defines only the Adapter-to-Server Gateway.
It does not create or start any Adapter instance:

```yaml
xa.mass.worker-delivery.adapter:
  gateway:
    base-url: http://127.0.0.1:18082
    request-timeout: 5s
```

An Adapter is an explicit deployment choice supplied by a profile, external
configuration, or environment variables. The instance map key is both
`adapterId` and `endpointManagerId`; the Worker declaration must use the
matching value. Each configured instance starts an independent Netty listener
during Server startup, after the HTTP server is bound and before the process
reports ready, and calls the shared Gateway `base-url`. A WebSocket Worker
connects to the instance's fixed WebSocket path; a Socket Worker connects to
its TCP port. Both send a direct
`WorkerConnectionBind` JSON value before any `WorkerCommand`; there is no
generic connection-message envelope. An empty or absent `instances` map starts
no active Adapter.

Instances must use distinct IDs and listener ports. Do not duplicate an
endpoint-manager ID for throughput. Each instance runs independent Command and
Result loops. The Command Loop refills a bounded local queue and initiates
non-blocking Channel writes. The Result Loop drains one shared bounded queue
containing validated Worker-originated results and Adapter-owned rejections,
and submits at most one pending or buffered batch per
`result-submit-interval`. There is no producer-source split.

### Built-in Worker Assembly

Built-in business Workers are opt-in. Configuration selects only an explicitly
coded bundle; it cannot provide an arbitrary class name or handler:

```yaml
xa:
  mass:
    worker-delivery:
      adapter:
        instances:
          scenario-websocket:
            type: WEBSOCKET
            listen-host: 127.0.0.1
            listen-port: 18083
    worker-assembly:
      bundles:
        phone-number:
          type: PHONE_NUMBER
          adapter-id: scenario-websocket
          worker-group-id: scenario-phone-number-workers
          worker-id-prefix: scenario-phone-number-worker-
          worker-count: 10
        string-utils:
          type: STRING_UTILS
          adapter-id: scenario-websocket
          worker-group-id: scenario-string-utils-workers
          worker-id-prefix: scenario-string-utils-worker-
          worker-count: 10
```

An absent `bundles` map starts no built-in business Worker. The checked-in
`scenario-workers` profile declares one WebSocket Adapter and two independent
Worker capability groups. It creates no Task and has no dependency on RPC,
ITEM_DRIVEN, TASK_DRIVEN, TARGETED, or PRECOMPUTED scheduling policy. Both
bundles supply bounded defaults of 10 Workers, a 10 second request timeout, a
250 millisecond reconnect interval, and a 15 second initial-connect timeout.
Their internal Workers derive the loopback WebSocket URI from the referenced
Adapter, so the port is not duplicated in bundle settings.

```text
./gradlew :server_jvm:bootRun --args="--spring.profiles.active=scenario-workers"
```

During startup the Server starts all configured Adapters and then invokes the
explicit `scenario_workers_jvm` bundle handles in declaration order. Each
bundle upserts its WorkerGroup and Workers through `WorkerResourceCatalog` and
`WorkerRuntime`, starts real WebSocket Worker transports, and waits for every
initial connection. Shutdown and partial-start recovery close bundles in
reverse order before closing the Adapter. Only `OK` and `NOOP` owner results
are accepted. Invalid configuration, duplicate generated Worker identity,
rejected owner operation, transport startup failure, or connection timeout
aborts Server startup. Already accepted owner upserts are not rolled back
across owners; deterministic declarations converge idempotently on the next
startup.

`PHONE_NUMBER` Workers all register `phonenumber.e164`,
`phonenumber.country`, and `phonenumber.original-carrier`.
`STRING_UTILS` Workers all register `string.md5`, `string.sha1`, and
`string.base64.encode`. A WorkerGroup's immutable `eventCodes` exactly match
the complete definition set registered by every Worker in that group. The new
`scenario-*` identities deliberately avoid mutating older declarations that
may remain in Redis.

Worker Assembly does not expose a new Kernel operation, access Redis, start a
second scheduler, or bypass the Adapter through an in-process path.
`ScenarioWorkerBundle` is a final lifecycle handle created only by the
explicit phone-number and string-utils factories; it is not an implementation
SPI or plugin system. The profile and Adapter remain Server-owned, while
capabilities and concrete Worker lifecycle belong to `scenario_workers_jvm`.
External Worker applications remain supported and own their own resource
registration and process lifecycle.

Defaults:

```text
Java Runtime API Server  http://127.0.0.1:18082
Python Kernel Server     http://127.0.0.1:18080
connect timeout          1s
read timeout             5s
Kernel Redis              redis://localhost:6379/15
Kernel Redis prefix        default
Adapter instances           none by default
Task RPC wait              30s default / 60s maximum
Task RPC waiter limit      10000
```

The Redis labels above use the shared `xa.mass.kernel-redis.redis-url` and
`xa.mass.kernel-redis.redis-prefix` properties. Override the Python control
address under `xa.mass.kernel`.

## Verification

```text
./gradlew :server_jvm:test
KERNEL_COMMAND_INTEGRATION_URL=http://127.0.0.1:18080 \
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:integrationTest
```

The cross-process integration proves `TASK_DRIVEN` through the real Java
polling Worker and `ITEM_DRIVEN` through Netty WebSocket and Socket Adapter
endpoints. All paths use the Server HTTP
boundary, Python scheduling/ResultRouting, Java last-success query, and exact
Worker release.
Authentication, same-endpoint multi-instance ownership, pending/ack,
failure-result projection, historical storage, tenant model, quota, and an
OpenAPI generator remain out of scope.
