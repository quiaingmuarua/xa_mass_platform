# XA Mass JVM Runtime API Server

Status: active external Runtime API with Kernel owner-contract assembly.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. WorkerGroup/Worker upsert and Task
create/approve/close bind to Python HTTP owner providers. TaskItem append,
last-success reads, and Worker Delivery bind to Java Redis owner providers.
Controllers and services depend only on contracts from `kernel_jvm`.

Server failures use one module exception over the narrow
[`foundation_jvm`](../foundation_jvm/README.md) contract:

```text
ServerException
  -> ServerErrorCode 10000..19999
  -> operation + message + cause
```

Kernel binding, Task Data, and Worker Delivery choose codes from the single
module enum; they do not define exception subclasses. `ApiExceptionHandler`
maps `ServerErrorCode` to HTTP status and emits its integer code in
`ApiErrorResponse`. Foundation does not own that policy. Spring remains the
process logging and tracing boundary, while exceptions do not carry request
IDs or context maps.

```text
Control client
  -> server_jvm /api/v1
  -> TaskRuntime / WorkerRuntime / WorkerResourceCatalog
  -> Python HTTP owner providers
  -> KernelApplication / scheduling truth

Task data client
  -> Java TaskDataService
  -> TaskRuntime + TaskResourceCatalog + WorkerResourceCatalog
  -> Java owner Redis providers
  -> Python scheduling and ResultRouting

Worker / long-lived Adapter
  -> server_jvm /api/v1/worker-delivery
  -> Java WorkerDeliveryService
  -> WorkerCommandRuntime + WorkerResultRuntime
  -> Java owner Redis providers
  -> Python ResultRouting
```

The module is Java 21 and Spring Boot 4.1. It depends on `kernel_jvm` contracts
but does not start the Python process. `kernelbinding` composes Task and Worker
control/data providers. `WorkerDeliveryOwnerAssemblyConfiguration` separately
composes only the WorkerCommand and WorkerResult Redis providers. The shared
`kernelredis` package owns only connection and health. Redis key operations are
implemented in owner-local `kernel_jvm` packages. Java does not read Task or
Worker scheduling scores, invoke Pacers, append Worker commands, or consume
WorkerResult queues.

Current provider matrix:

| Operation | Provider |
| --- | --- |
| WorkerGroup upsert | Python HTTP |
| Worker upsert | Python HTTP |
| Task create | Python HTTP |
| Task approve/close | Python HTTP application command |
| Task and WorkerGroup descriptor reads | Java Redis |
| TaskItem append and last-success load | Java Redis |
| WorkerCommand consume | Java Redis |
| WorkerResult append | Java Redis |
| Score, candidate, dynamic attribute, scheduling internals | no Server bean / explicit not implemented |

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
  complete Adapter instances, independent Netty WebSocket listeners,
  mailbox loops, active connections, and bounded delivery
```

The Server is the only Worker Delivery HTTP and Redis owner. Point and batch
controllers call `WorkerDeliveryService`, which calls the two Kernel delivery
runtime contracts. The shared contract module has no Spring Web or Redis
dependency.

The Adapter runtime is implemented by
[`transport/netty-adapter`](../transport/netty-adapter/README.md).
This Server reads the configured Adapter instance map, creates complete
WebSocket or Socket Adapter instances, registers them, and forwards
process-ready/process-close events. Each Adapter owns its Netty listener,
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
POST /api/v1/tasks/{taskId}/results:load
```

The first five operations use Kernel owner/application contracts backed by
Python HTTP providers. Item append and result load use the same owner contracts
backed by Java Redis providers. Append returns per-message
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
Python Kernel Control API and the shared Kernel Redis connection.

## Run

Start the Python kernel process first:

```text
python -m kernel_design.runtime_server
```

Then start the external Runtime API Server:

```text
./gradlew :server_jvm:bootRun
```

Host the [OkHttp Worker](../transport/okhttp-worker/README.md) in a JVM
application. An Android WebSocket host composes the same Worker transport with
the [Android Client](../transport/android-client/README.md), then calls
`WebSocketWorkerTransport.start()`. A polling host calls the Server point API
directly. A WebSocket or Socket host connects to the selected Adapter listener.
These libraries do not provide a CLI or own application lifecycle.

One WebSocket and one Socket Adapter instance:

```yaml
xa.mass.worker-delivery.adapter:
  gateway:
    base-url: http://127.0.0.1:18082
    request-timeout: 5s
  instances:
    websocket-1:
      type: WEBSOCKET
      listen-host: 0.0.0.0
      listen-port: 18083
      command-loop-interval: 100ms
      command-consume-limit: 100
      command-queue-capacity: 1000
      result-submit-interval: 1s
      result-queue-capacity: 1000
      send-time-limit: 5s
    socket-1:
      type: SOCKET
      listen-host: 0.0.0.0
      listen-port: 18084
```

The instance map key is both `adapterId` and `endpointManagerId`; the Worker
declaration must use the matching value. Each instance starts an independent
Netty listener after the Server is ready and calls the shared Gateway
`base-url`. A WebSocket Worker connects to the instance's fixed WebSocket path;
a Socket Worker connects to its TCP port. Both send `WORKER_BIND` before
business messages. An empty `instances` map starts no active Adapter.

Instances must use distinct IDs and listener ports. Do not duplicate an
endpoint-manager ID for throughput. Each instance runs independent Command and
Result loops. The Command Loop refills a bounded local queue and initiates
non-blocking Channel writes; the Result Loop aggregates Worker results and
Adapter rejections separately and submits at most one batch per source per
`result-submit-interval`.

Defaults:

```text
Java Runtime API Server  http://127.0.0.1:18082
Python Kernel Server     http://127.0.0.1:18080
connect timeout          1s
read timeout             5s
Kernel Redis              redis://localhost:6379/15
Kernel Redis prefix        default
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
