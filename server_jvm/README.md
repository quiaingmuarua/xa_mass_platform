# XA Mass JVM Runtime API Server

Status: active external Runtime Command API over the Python kernel process.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. It does not own scheduling truth,
historical records, result projections, or Redis state.

```text
External Client
-> server_jvm /api/v1
-> KernelCommandClient
-> Python Kernel Runtime Server
-> KernelApplication / Redis runtime truth
```

The module is Java 21 and Spring Boot 4.1. It has no dependency on
`kernel_jvm` and does not start the Python process.

## Runtime Commands

```text
PUT  /api/v1/worker-groups/{workerGroupId}
PUT  /api/v1/worker-groups/{workerGroupId}/workers/{workerId}
POST /api/v1/tasks
POST /api/v1/tasks/{taskId}/approve
POST /api/v1/tasks/{taskId}/close
POST /api/v1/tasks/{taskId}/items
```

Management endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness describes this JVM process. Readiness also requires the configured
Python Kernel Runtime Server to answer `/health`.

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
```

Override them with Spring properties under `xa.mass.kernel`.

## Verification

```text
./gradlew :server_jvm:test
KERNEL_COMMAND_INTEGRATION_URL=http://127.0.0.1:18080 \
  ./gradlew :server_jvm:integrationTest
```

The first release intentionally has no authentication, query API, historical
storage, result view, tenant model, quota, or OpenAPI generator.
