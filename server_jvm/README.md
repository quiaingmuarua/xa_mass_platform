# XA Mass JVM Runtime API Server

Status: active external Runtime API with Java-owned Worker Delivery access.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. Task and resource commands still proxy
to the Python kernel process. Worker Delivery is implemented directly in Java:
it consumes WorkerCommand mailbox fields and appends SeedResult queue entries.

```text
Task/resource client
  -> server_jvm /api/v1
  -> KernelCommandClient
  -> Python Kernel Runtime Server
  -> KernelApplication / scheduling truth

Worker / long-lived Adapter
  -> server_jvm /api/v1/worker-delivery
  -> Java WorkerDeliveryService
  -> WorkerCommand consume / SeedResult append
  -> Redis
  -> Python ResultRouting
```

The module is Java 21 and Spring Boot 4.1. It has no dependency on
`kernel_jvm` and does not start the Python process. Redis dependencies are
confined to `com.xa.mass.server.workerdelivery.redis`; Java does not read
scores, invoke Pacers, append Worker commands, or consume SeedResult queues.

Worker Delivery package boundaries:

```text
workerdelivery
  application service, runtime port, error mapping, Bean composition
workerdelivery.protocol
  transport-neutral WorkerCommand/SeedResult contracts and JSON codec
workerdelivery.http
  point Worker and Adapter batch HTTP access profiles
workerdelivery.redis
  WorkerCommand consume and SeedResult append implementation
```

HTTP and a future WebSocket Adapter call `WorkerDeliveryService`; neither may
import the Redis implementation. `protocol` has no Spring Web or Redis
dependency. A WebSocket package will be added only with a real session and
delivery-loop slice.

## Runtime Commands

```text
PUT  /api/v1/worker-groups/{workerGroupId}
PUT  /api/v1/worker-groups/{workerGroupId}/workers/{workerId}
POST /api/v1/tasks
POST /api/v1/tasks/{taskId}/approve
POST /api/v1/tasks/{taskId}/close
POST /api/v1/tasks/{taskId}/items
```

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

Management endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness describes this JVM process. Readiness requires both the configured
Python Kernel Runtime Server and Worker Delivery Redis.

## Run

Start the Python kernel process first:

```text
python -m kernel_design.runtime_server
```

Then start the external Runtime API Server:

```text
./gradlew :server_jvm:bootRun
```

Defaults:

```text
Java Runtime API Server  http://127.0.0.1:18082
Python Kernel Server     http://127.0.0.1:18080
connect timeout          1s
read timeout             5s
Worker Delivery Redis     redis://localhost:6379/15
Worker Delivery prefix    default
```

Override them with Spring properties under `xa.mass.kernel` and
`xa.mass.worker-delivery`.

## Verification

```text
./gradlew :server_jvm:test
KERNEL_COMMAND_INTEGRATION_URL=http://127.0.0.1:18080 \
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:integrationTest
```

The cross-process integration proves both TaskTypes through Python scheduling,
Java command polling/result ingress, Python ResultRouting, result HASH storage,
and exact Worker release. The first release intentionally has no
authentication, WebSocket transport, query API, historical storage, result
view, tenant model, quota, or OpenAPI generator.
