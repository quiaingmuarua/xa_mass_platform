# XA Mass JVM Runtime API Server

Status: active external Runtime API with Kernel owner-contract assembly.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. WorkerGroup/Worker upsert and Task
create/approve/close bind to Python HTTP owner providers. TaskItem append,
last-success reads, and Worker Delivery bind to Java Redis owner providers.
Controllers and services depend only on contracts from `kernel_jvm`.

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
  -> WorkerCommandRuntime + SeedResultRuntime
  -> Java owner Redis providers
  -> Python ResultRouting
```

The module is Java 21 and Spring Boot 4.1. It depends on `kernel_jvm` contracts
but does not start the Python process. `kernelbinding` composes Task and Worker
control/data providers. `WorkerDeliveryOwnerAssemblyConfiguration` separately
composes only the WorkerCommand and SeedResult Redis providers. The shared
`kernelredis` package owns only connection and health. Redis key operations are
implemented in owner-local `kernel_jvm` packages. Java does not read Task or
Worker scheduling scores, invoke Pacers, append Worker commands, or consume
SeedResult queues.

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
| SeedResult append | Java Redis |
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
  transport-neutral WorkerCommand/DeliverSeed/SeedResult contracts and codec
api.v1.workerdelivery
  point Worker and Adapter batch HTTP access profiles
workerdelivery.application
  transport-neutral application service, access policy, application errors
workerdelivery.websocket
  one configured Adapter mailbox pump, Worker sessions, and result buffering
workerdelivery
  application and delivery-owner composition
kernel_jvm delivery contracts/providers
  WorkerCommand consume and SeedResult append owner operations
```

HTTP and WebSocket call `WorkerDeliveryService`; neither imports the Redis
implementation. The shared contract module has no Spring Web or Redis
dependency. The WebSocket Adapter is an access profile inside this server
module, not a second runtime owner or independently published module.

The current Server loads Worker Delivery together with the control/data API.
An isolated composition test fixes the complete Gateway dependency set:
Kernel Redis connection/health, delivery owner assembly, Worker Delivery
application, point/batch HTTP API, WebSocket Adapter, and common HTTP error
support. A future standalone Gateway therefore needs only a new Spring Boot
composition root. It does not require package moves, copied DTOs, or another
runtime interface. That standalone process would host point polling, Adapter
batch access, and WebSocket together; `system-polling` remains only a logical
mailbox binding.

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

`system-polling` may use only the point Worker operations. Cursor consume and
batch result append are reserved for long-lived Adapter identities.

WebSocket Worker access:

```text
GET /api/v1/worker-delivery/websocket/workers/{workerId}
```

One enabled server instance owns one configured non-`system-polling`
endpoint-manager mailbox. The Worker first uses the resource API to upsert
itself with that endpoint-manager identity, then connects. Server-to-Worker
text frames are exact `WorkerCommandEnvelope` JSON; Worker-to-Server frames
are exact `SeedResult` JSON limited to `200/1xxx`.

There is no application ACK. Results are buffered and retried only in bounded
process memory; a JVM failure may lose them. A command consumed without a
current Worker session produces trusted `3001` evidence. A send attempted
before an ambiguous failure remains unknown and does not produce `3xxx`.

Management endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness describes this JVM process. Readiness requires both the configured
Python Kernel Control API and the shared Kernel Redis connection.
The isolated Worker Delivery composition has no Python Kernel dependency; if
deployed later as its own process, its readiness requires only process
liveness and Kernel Redis.

## Run

Start the Python kernel process first:

```text
python -m kernel_design.runtime_server
```

Then start the external Runtime API Server:

```text
./gradlew :server_jvm:bootRun
```

Run the Java reference Worker through polling or WebSocket as documented in
[worker_jvm](../worker_jvm/README.md).

Defaults:

```text
Java Runtime API Server  http://127.0.0.1:18082
Python Kernel Server     http://127.0.0.1:18080
connect timeout          1s
read timeout             5s
Kernel Redis              redis://localhost:6379/15
Kernel Redis prefix        default
WebSocket Adapter          disabled
```

The Redis labels above use the shared `xa.mass.kernel-redis.redis-url` and
`xa.mass.kernel-redis.redis-prefix` properties. Override the Python control
address under `xa.mass.kernel`; WebSocket settings remain under
`xa.mass.worker-delivery`. Enable one WebSocket Adapter with:

```yaml
xa:
  mass:
    worker-delivery:
      websocket:
        enabled: true
        endpoint-manager-id: websocket-adapter-1
```

## Verification

```text
./gradlew :server_jvm:test
KERNEL_COMMAND_INTEGRATION_URL=http://127.0.0.1:18080 \
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:integrationTest
```

The cross-process integration proves `TASK_DRIVEN` with the real Java polling
Worker and `ITEM_DRIVEN` with the real Java WebSocket Worker through Python
scheduling, Java TaskItem append, Java Worker Delivery, Python ResultRouting,
Java last-success query, and exact Worker release. The first release
intentionally has no authentication, multi-instance Adapter ownership,
pending/ack, failure-result projection, historical storage, tenant model,
quota, or OpenAPI generator.
