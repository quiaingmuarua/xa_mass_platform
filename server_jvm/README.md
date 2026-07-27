# XA Mass JVM Runtime API Server

Status: active external Runtime API with Java-owned TaskData and Worker
Delivery access.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. WorkerGroup/Worker upsert and Task
create/approve/close still proxy to the Python kernel process. TaskItem append,
last-success result reads, and Worker Delivery operate directly on their
current Redis owner shapes.

```text
Control client
  -> server_jvm /api/v1
  -> KernelCommandClient
  -> Python Kernel Control API
  -> KernelApplication / scheduling truth

Task data client
  -> Java TaskDataService
  -> TaskItem record + ACTIVE score initialization / last-success HASH read
  -> Redis
  -> Python scheduling and ResultRouting

Worker / long-lived Adapter
  -> server_jvm /api/v1/worker-delivery
  -> Java WorkerDeliveryService
  -> WorkerCommand consume / SeedResult append
  -> Redis
  -> Python ResultRouting
```

The module is Java 21 and Spring Boot 4.1. It has no dependency on
`kernel_jvm` and does not start the Python process. The shared
`kernelredis` package owns only connection and health. Owner key operations
are confined to `taskdata.redis` and `workerdelivery.redis`. Java does not
read Task or Worker scheduling scores, invoke Pacers, append Worker commands,
or consume SeedResult queues.

Task Data boundaries:

```text
taskdata
  application service, runtime port, error mapping
taskdata.redis
  Task/WorkerGroup declaration reads, Item record/score initialization,
  last-success result reads
```

`TaskDataService` is the only HTTP use-case facade. Re-appending a messageId
replaces its Item record but `ZADD NX` preserves any existing Item score.
Result reads return opaque last-success payload strings or `null`; they do not
infer pending or failure state.

Worker Delivery boundaries:

```text
worker_delivery_contract_jvm
  transport-neutral WorkerCommand/DeliverSeed/SeedResult contracts and codec
workerdelivery
  application service, runtime port, error mapping, Bean composition
workerdelivery.http
  point Worker and Adapter batch HTTP access profiles
workerdelivery.redis
  WorkerCommand consume and SeedResult append implementation
workerdelivery.websocket
  one configured Adapter mailbox pump, Worker sessions, and result buffering
```

HTTP and WebSocket call `WorkerDeliveryService`; neither imports the Redis
implementation. The shared contract module has no Spring Web or Redis
dependency. The WebSocket Adapter is an access profile inside this server
module, not a second runtime owner or independently published module.

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

The first five operations are control commands routed to Python. Item append
and result load are Java TaskData operations. Append returns per-message
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
