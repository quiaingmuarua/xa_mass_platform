# XA Mass JVM Runtime API Server

Status: active external Runtime API with Kernel owner-contract assembly and
opt-in Scenario Worker composition.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. Task create/approve/close bind to Python
HTTP owner providers. WorkerGroup upsert, Worker registration/property update,
TaskItem append, last-success
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

Configured Scenario Workers JSON
  -> Server starts the configured Adapter Manager
  -> scenario_workers_jvm parses the opaque manifest
  -> scenario_workers_jvm performs owner registration and updates
  -> scenario_workers_jvm starts Worker Core + concrete network Clients
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
Its Worker score provider implements get/initialize for
`WorkerRuntime.registerWorker` plus a parity reconcile mechanism with no current
production caller; scheduling score operations remain unavailable.

Current provider matrix:

| Operation | Provider |
| --- | --- |
| WorkerGroup upsert | Java Redis |
| Worker registration and missing HOT_ACQUIRE initialization | Java Redis |
| Worker Properties complete replacement | Java Redis |
| Platform Properties patch | Java Redis |
| Explicit indexed-property update/point load | Java Redis |
| Task create | Python HTTP |
| Task approve/close | Python HTTP application command |
| Task and WorkerGroup descriptor reads | Java Redis |
| TaskItem append and Task-scoped last-success load | Java Redis |
| Task Dispatch wake hint | Python HTTP application command |
| WorkerCommand consume | Java Redis |
| WorkerResult append | Java Redis |
| Other score, candidate, and scheduling internals | explicit not implemented |

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
PUT  /api/v1/worker-groups/{workerGroupId}/workers/{workerId}/worker-properties
PATCH /api/v1/worker-groups/{workerGroupId}/workers/{workerId}/platform-properties
PATCH /api/v1/worker-groups/{workerGroupId}/workers/{workerId}/indexed-properties
POST /api/v1/tasks
POST /api/v1/tasks/{taskId}/approve
POST /api/v1/tasks/{taskId}/close
POST /api/v1/tasks/{taskId}/items
POST /api/v1/tasks/{taskId}/items:call
POST /api/v1/tasks/{taskId}/results:load
POST /api/v1/runtime-view/worker-groups:batch-get
POST /api/v1/runtime-view/worker-groups/{workerGroupId}/workers:preview
```

The Worker path is registration: compatible repeats are no-ops and cannot
refresh either property snapshot. The `worker-properties` PUT is the explicit
complete replacement of `workerProperties`; it preserves Platform Properties,
the endpoint coordinate, and every existing score state.

WorkerGroup PUT atomically replaces `attributes` and `eventCodes`; identical
content returns `NOOP`. `workerGroupId` is the stable catalog identity and
Kernel scheduling partition. `eventCodes` is an advisory Server directory
projection for display and future Task recommendation. It is not used by Task
admission, Matcher, Dispatch, or as proof of installed Worker Handlers.

WorkerGroup upsert and Worker registration/property update use Java Redis owner providers. Task
create/approve/close use Python HTTP owner/application providers. Item append
and result load use Java Redis owner providers. Append returns per-message
`appended / not_found / invalid / retryable` status. `TASK_DRIVEN` forbids an
Item rule. For `ITEM_DRIVEN`, Java requires only a non-empty JSON-compatible
rule and persists it opaquely. The Python matcher owns the evolving rule DSL.
Its current TARGETED path derives a bounded request-local candidate set from
`workerId $eq/$equal/$in`; additional `worker.*`, `platform.*`, and explicit
`index.*` conditions are evaluated there. Each `index.*` field is loaded from
its configured point projection only for those known Worker IDs. It is not a
candidate-discovery or multi-index intersection contract.

Worker PUT completely replaces `workerProperties` and preserves
`platformProperties`. Platform Properties are patched separately with
`{"properties": {...}}`; a `null` value deletes one field. Index updates use
qualified keys in `{"updates": {"index.worker.region": "cn-east"}}` on the
single indexed-properties route and return a status per field.
Properties and indexes are never written together implicitly.

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

## Worker Runtime View

Runtime View is a read-only operator preview over the
`WorkerResourceCatalog`. It does not expose Redis, Worker score, lease,
transport session, payload, lifecycle status, history, global discovery, or
stable pagination.

Batch WorkerGroup load accepts 1 to 20 unique, nonblank configured IDs:

```http
POST /api/v1/runtime-view/worker-groups:batch-get
```

```json
{
  "workerGroupIds": [
    "scenario-phone-number-workers",
    "scenario-string-utils-workers"
  ]
}
```

Existing `workerGroups` and `missingWorkerGroupIds` preserve request order.
The response group projection contains only `workerGroupId`, `attributes`,
and `eventCodes`.

One WorkerGroup preview accepts `sampleLimit` from 1 through 100:

```http
POST /api/v1/runtime-view/worker-groups/{workerGroupId}/workers:preview
```

```json
{"sampleLimit":100,"filter":null}
```

The owner first validates the WorkerGroup, then executes one positive-count
Redis `HRANDFIELD ... WITHVALUES` against that group's Worker descriptor HASH.
The response reports `sampledCount`, `returnedCount`, `unreadableCount`, and
`generatedAt`; each readable Worker contains only `workerId`,
`workerGroupId`, `endpointManagerId`, `workerProperties`, and
`platformProperties`. Index-only values are not joined into Runtime View.
Worker order, sample stability, completeness, totals,
and pagination are deliberately not contracts. The provider does not refill
unreadable rows. A non-null `filter` returns `422` until the separate bounded
Filter DSL slice is implemented.

Runtime View errors use `15001` for a missing WorkerGroup, `15002` for an
unavailable owner/provider (`503`), and `15003` for a filter that is not yet
available (`422`). `X-Request-Id` is retained in error responses. The default
Server bind address is `127.0.0.1`; set `SERVER_ADDRESS` explicitly only when
the deployment supplies its own access boundary.

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

The Server publishes the generated OpenAPI document and a local Scalar API
reference on the same port:

```text
Scalar API Reference  http://127.0.0.1:18082/scalar
OpenAPI JSON          http://127.0.0.1:18082/v3/api-docs
```

Only `/api/v1/**` routes are included. Scalar's JavaScript is served by the
Server; telemetry, Agent Scalar, and external web fonts are disabled.

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

Built-in business Workers are opt-in. The Server profile owns advisory
WorkerGroup directory metadata, while `scenario_workers_jvm` owns its local
Definitions and Worker execution. The two JSON documents are deliberately
independent:

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
      runtime-api-base-url: http://127.0.0.1:18082
      group-config-json: |
        {
          "scenario-phone-number-workers": {
            "attributes": {"capability":"libphonenumber"},
            "eventCodes": [
              "phonenumber.e164",
              "phonenumber.country",
              "phonenumber.original-carrier"
            ]
          }
        }
      worker-config-json: |
        {
          "scenario-phone-number-workers": {
            "eventCodes": [
              "phonenumber.e164",
              "phonenumber.country",
              "phonenumber.original-carrier"
            ],
            "endpointManagerId": "scenario-websocket",
            "websocketUri": "ws://127.0.0.1:18083/api/v1/worker-delivery/websocket",
            "workers": [{
              "workerId": "scenario-phone-number-worker-001",
              "workerProperties": {"runtime":"java","region":"local"},
              "indexedPropertyUpdates": {"index.worker.region":"local"}
            }]
          }
        }
```

Both JSON values default to `{}`. The checked-in
`scenario-workers` profile declares one WebSocket Adapter and two independent
Worker capability groups. It creates no Task and has no dependency on RPC,
ITEM_DRIVEN, TASK_DRIVEN, TARGETED, or PRECOMPUTED scheduling policy. Both
groups explicitly list 10 Workers. Omitted timeout fields use a 10 second
request timeout, a 250 millisecond reconnect interval, and a 15 second
initial-connect timeout. The final WebSocket URI and endpoint manager are
deployment configuration; Server does not derive one from the other.

```text
./gradlew :server_jvm:bootRun --args="--spring.profiles.active=scenario-workers"
```

During startup the Server initializes WorkerGroup directory entries through the
WorkerGroup owner, then starts configured Adapters, then invokes one aggregate
`ScenarioWorkers` handle. Scenario starts every real WebSocket transport and
waits for all initial connections before registering Workers, replacing Worker
Properties, and applying best-effort Index updates through the public Runtime
Resource HTTP API. Shutdown closes Scenario transports before Adapters;
WorkerGroup directory entries are not rolled back or removed.

The phone-number group references `phonenumber.e164`,
`phonenumber.country`, and `phonenumber.original-carrier`. The string-utils
group references `string.md5`, `string.sha1`, and `string.base64.encode`.
Every Worker in a group receives the same immutable Definition list and shared
Handler instances. Worker identity remains outside Event Definitions and
business result payloads. WorkerGroup `eventCodes` in `group-config-json` are a
display/recommendation summary and may lag the local Definition list in
`worker-config-json`; neither Server nor Kernel enforces equality.

Worker Assembly does not expose a new Kernel operation, access Redis, start a
second scheduler, or bypass the Adapter through an in-process path.
`ScenarioWorkers` is the only public local lifecycle handle; it is not an
implementation SPI or plugin system. Server does not import Scenario business
Definitions or Handlers. Scenario does not import Kernel or Server
implementation types. Closing the handle
only releases local network resources and does not change Kernel Worker truth.
The profile and Adapter remain Server-owned, while capabilities and concrete
Worker resource lifecycle belong to `scenario_workers_jvm`.
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

Property-index implementations are opt-in. Java and Python read the same
process environment value with explicit `index.*` keys:

```powershell
$env:XA_MASS_WORKER_PROPERTY_INDEX_REGISTRY_JSON = `
  '{"index.worker.region":"redis-hash","index.platform.pool":"redis-hash"}'
```

The default map is empty. Malformed JSON, invalid fields, or unknown
implementations fail startup. WorkerGroup does not declare indexes. Updates and
point reads for an unconfigured field fail explicitly. Both processes log the
same canonical registry fingerprint; no registry is stored in Redis.

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
failure-result projection, historical storage, tenant model, and quota remain
out of scope.
