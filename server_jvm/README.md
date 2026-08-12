# XA Mass JVM Runtime API Server

Status: active external Runtime API with Kernel owner-contract assembly and
opt-in Scenario Worker composition.

`server_jvm` owns the versioned HTTP contract, request validation, error
mapping, timeouts, and process health. Task create/approve/close bind to Python
HTTP owner providers. WorkerGroup upsert, Kernel Worker upsert, TaskItem append,
last-success
reads, and Worker Delivery bind to Java Redis owner providers. Controllers and
services depend only on contracts from `kernel_jvm`.

Worker identity is a separate Server-owned control-plane boundary. It maps a
stable `workerGroupId + workerProperties.clientWorkerKey` to a long-lived
canonical UUID in the `wi:{prefix}:...` namespace. It is not a Kernel owner
contract and does not create a scheduler-visible Worker by itself. UUID format
validation stays in this Server owner; Worker and Adapter layers treat the
returned workerId as an opaque non-blank routing value.

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

Worker identity client
  -> Server Register API with workerGroupId + complete workerProperties
  -> extract workerProperties.clientWorkerKey
  -> long-lived platform-issued workerId

Worker Bind control
  -> Server verifies workerGroupId + workerProperties.clientWorkerKey + workerId
  -> persist or reuse one Endpoint Manager
  -> WorkerRuntime.upsertWorker with endpointManagerId + workerProperties
  -> return the public endpoint URI

Worker Connect through Polling or Adapter
  -> point request or Adapter verifies current Endpoint Binding
  -> WorkerResourceCatalog
  -> Java owner Redis providers
  -> split Worker metadata/properties and HOT_ACQUIRE truth

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
  -> Server initializes WorkerGroup catalog and starts Adapter Manager
  -> scenario_workers_jvm parses the opaque manifest
  -> scenario_workers_jvm registers and binds each Worker
  -> long-lived Worker sends an Adapter-directed identity Report
  -> scenario_workers_jvm starts Worker Core + concrete network Clients
  -> real configured Adapter listener
  -> the same Worker Delivery HTTP/Redis path
```

The module is Java 21 and Spring Boot 4.1. It depends on `kernel_jvm` contracts
but does not start the Python process. `kernelbinding` composes Task and Worker
control/data providers. `WorkerDeliveryOwnerAssemblyConfiguration` separately
composes only the DeliveryCommand and DeliveryReport Redis providers. The shared
`kernelredis` package owns only connection and health. Redis key operations are
implemented in owner-local `kernel_jvm` packages. Java does not read Task
scores, invoke Pacers, append Worker commands, or consume DeliveryReport queues.
Its Worker score provider implements get/initialize for
`WorkerRuntime.upsertWorker` plus a parity reconcile mechanism with no current
production caller; scheduling score operations remain unavailable.

Current provider matrix:

| Operation | Provider |
| --- | --- |
| WorkerGroup upsert | Java Redis |
| Worker identity registration coordinate | Server-owned Java Redis |
| Persistent Worker Endpoint Binding | Server-owned Java Redis |
| Worker Bind upsert, Properties replacement, and missing HOT_ACQUIRE initialization | Java Redis |
| Platform Properties patch | Java Redis |
| Explicit indexed-property update/point load | Java Redis |
| Task create | Python HTTP |
| Task approve/close | Python HTTP application command |
| Task and WorkerGroup descriptor reads | Java Redis |
| TaskItem append and Task-scoped last-success load | Java Redis |
| Task Dispatch wake hint | Python HTTP application command |
| DeliveryCommand consume | Java Redis |
| DeliveryReport append | Java Redis |
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
  transport-neutral DeliveryCommand/DeliveryReport contracts and strict codecs
api.v1.workerdelivery
  point Worker and Adapter batch HTTP access profiles
workerdelivery.application
  Kernel delivery use-case service and application errors
workerdelivery
  HTTP application and delivery-owner composition
kernel_jvm delivery contracts/providers
  DeliveryCommand consume and DeliveryReport append owner operations

transport/netty-adapter
  finite Adapter construction factory, package-private WebSocket/Socket
  Adapter aggregates, independently owned physical Network Servers, mailbox
  pumps, child Channels, bound routes, and bounded non-blocking delivery
```

The Server is the only Worker Delivery HTTP and Redis owner. Point and batch
controllers call `WorkerDeliveryService`, which calls the two Kernel delivery
runtime contracts. The shared contract module has no Spring Web or Redis
dependency.

The Adapter runtime is implemented by
[`transport/netty-adapter`](../transport/netty-adapter/README.md).
This Server reads the configured Adapter instance map, uses the finite Netty
factory to create complete
WebSocket or Socket Adapter instances, registers them, and starts/closes the
manager at process boundaries. Each Adapter owns its Netty listener,
bounded Command/Report pumps, every accepted child Channel, the current bound
route directory, and encoded Report buffer through one shared Netty-specific
runtime. It consumes the existing batch HTTP API through loopback and has no
in-process or Redis shortcut. Polling continues to exchange DeliveryCommand and
DeliveryReport through point HTTP.

## Runtime Commands

```text
PUT  /api/v1/worker-groups/{workerGroupId}
POST /api/v1/worker-groups/{workerGroupId}/workers:register
POST /api/v1/worker-groups/{workerGroupId}/workers/{workerId}:bind
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

Identity registration accepts the WorkerGroup path coordinate and complete
Worker Properties:

```json
{"workerProperties":{"clientWorkerKey":"installation-1","runtime":"java"}}
```

The Server extracts the exact, case-sensitive
`workerProperties.clientWorkerKey`. Repeating the same key in the same
WorkerGroup returns the same canonical UUID; other Properties may change and
do not enter the identity coordinate. Register creates no Kernel Worker,
score, or endpoint Binding. This is long-lived identity allocation in the
current trusted deployment, not an authentication system.

Authentication, authorization, and transport protection are separate ingress
policies. A future deployment may apply HTTP sessions or tokens, mTLS, Gateway
service identity, and permission checks before these operations. Neither a
registered Worker ID nor Endpoint Binding is a credential.

Bind receives `transportType` and the same complete `workerProperties`, uses
its `clientWorkerKey` to validate the registration coordinate, persists one
Endpoint Manager for the global `workerId`, and calls Kernel Worker upsert with
that endpoint and the complete snapshot. Repeated Bind reuses the same endpoint
and refreshes Properties. A different requested transport conflicts; endpoint
migration is not implicit. `POLLING` is reserved to the single
`system-polling` directory identity because point Client paths intentionally do
not carry a selected endpoint-manager ID. WebSocket and Socket may each have
multiple independently addressed directory entries.

The Endpoint Directory is address and selection configuration, not endpoint
liveness or a revocation mechanism. Removing an entry prevents Bind from
returning that endpoint, but it does not delete an existing persistent Binding,
deactivate an Adapter Channel, or migrate the Worker. Those require explicit
future owner operations.

WorkerGroup PUT atomically replaces `attributes` and `eventCodes`; identical
content returns `NOOP`. `workerGroupId` is the stable catalog identity and
Kernel scheduling partition. `eventCodes` is an advisory Server directory
projection for display and future Task recommendation. It is not used by Task
admission, Matcher, Dispatch, or as proof of installed Worker Handlers.

WorkerGroup upsert and Worker Bind upsert use Java Redis owner providers. Task
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

Every accepted Bind completely replaces `workerProperties`, preserves
`platformProperties`, and leaves an existing score unchanged. The Server
Endpoint Directory selects and persists the immutable `endpointManagerId`;
Workers receive only its public URI. Platform Properties are patched separately with
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
Redis `HRANDFIELD ... WITHVALUES` against that group's Worker metadata HASH,
then loads the matching Worker Properties rows.
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
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}:verify-binding
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append
```

`system-polling` may use only the point Worker operations. Bounded batch
consume and batch result append are reserved for long-lived Adapter identities.
Each point poll/result request verifies that the Worker is persistently bound to
`system-polling`. A long-lived Adapter calls `verify-binding` for every new
Channel and exposes it to command delivery only when the persisted Binding
matches the Adapter's endpoint-manager identity.
The Adapter result request carries an array of encoded `DeliveryReport` strings.
Server accepts `WORKER` success/failure Reports targeting `TASK`; it accepts an
`ADAPTER` `2...` Report only when `sourceId` equals the path
`endpointManagerId`. Server appends the valid subset and
returns both `acceptedCount` and `rejectedCount`. The point Worker result
endpoint separately requires `src=WORKER`, `sourceId` equal to the path
workerId, and an outcome of `200` or Worker-owned `3...`.

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

Use the [Java Worker](../transport/java-worker/README.md) assembly in a JVM
application, or compose lower-level [Worker Core](../transport/worker-core/README.md)
transports and Java Clients directly. An Android host builds
[Android Worker](../transport/android-worker/README.md) with its Properties
function and event definitions, then owns its `start/stop/close` lifecycle.
Android Worker performs Register/Bind and composes Core's WebSocket Transport
internally. A Java polling host calls the Server point API through its point
Client. A Java WebSocket or Socket host connects to the selected Adapter
listener. These libraries do not provide a CLI or own host-process lifetime.

The default Server configuration defines only the Adapter-to-Server Gateway.
It does not create or start any Adapter instance:

```yaml
xa.mass.worker-delivery.adapter:
  gateway:
    base-url: http://127.0.0.1:18082
    request-timeout: 5s

xa.mass.worker-binding:
  endpoints:
    scenario-websocket:
      transport-type: WEBSOCKET
      public-uri: ws://127.0.0.1:18083/api/v1/worker-delivery/websocket
```

An Adapter is an explicit deployment choice supplied by a profile, external
configuration, or environment variables. The instance map key is both
`adapterId` and `endpointManagerId`; Workers only target the instance listener
and do not declare that identity. Each configured instance starts an independent Netty listener
during Server startup, after the HTTP server is bound and before the process
reports ready, and calls the shared Gateway `base-url`. A WebSocket Worker
connects to the instance's fixed WebSocket path; a Socket Worker connects to
its TCP port. Both first send
`DeliveryReport(src=WORKER,sourceId=workerId,dst=ADAPTER,`
`messageType=worker.connection.identify,payload="null")`;
there is no generic connection-message envelope or identity ACK. Reads remain
paused until Server route verification succeeds. Adapter keeps no identity or
Binding cache; each new connection is checked. A definite route rejection may
produce `ADAPTER/worker.connection.close`, while Gateway unavailability only
closes the physical connection. An empty or absent `instances` map starts no
active Adapter.

Instances must use distinct IDs and listener ports. Do not duplicate an
endpoint-manager ID for throughput. Each instance runs independent Command and
Report pumps. The Command Pump refills a bounded local queue and initiates
non-blocking Channel writes. The Report Pump drains one shared bounded queue
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
    worker-binding:
      endpoints:
        scenario-websocket:
          transport-type: WEBSOCKET
          public-uri: ws://127.0.0.1:18083/api/v1/worker-delivery/websocket
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
            "workers": [{
              "clientWorkerKey": "scenario-phone-number-worker-001",
              "sandboxDirectory":
                "data/scenario-workers/scenario-phone-number-worker-001",
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
groups explicitly list 10 Workers. The `worker-001` entry in each group owns a
local sandbox under `data/scenario-workers`; the other 18 Workers remain
ephemeral. Omitted timeout and retry fields use a 10 second request timeout, a
10-attempt prepare budget at one-second intervals, a 20-attempt connection
budget at 500-millisecond intervals with a 10-second stable window, and a 15
second initial-connect timeout. A sandbox Worker registers only when its local
`identity.json` is absent. Later starts reuse the persisted Worker ID, load the
complete snapshot from `worker-properties.json`, and Bind again. An ephemeral
Worker continues to register on each start. Scenario then builds each
WebSocket Client from the public URI returned by Bind; the Worker manifest does
not contain an endpoint-manager ID or Adapter URI.

```text
./gradlew :server_jvm:bootRun --args="--spring.profiles.active=scenario-workers"
```

During startup the Server initializes WorkerGroup directory entries through the
WorkerGroup owner, then starts configured Adapters, then invokes one aggregate
`ScenarioWorkers` handle. Scenario resolves each Worker ID from its sandbox or
the Identity API, binds it with complete Worker Properties,
starts every real WebSocket transport against the returned URI, waits for
initial network connections, and applies best-effort Index updates through the
public Runtime Resource HTTP API. Adapter route verification only compares the
persisted Endpoint Binding; successful verification is followed by process-local
connection activation.
Shutdown closes Scenario transports before Adapters;
WorkerGroup directory entries are not rolled back or removed.

The sandbox is a writable local state directory, not a security boundary.
Profile Properties only initialize a missing `worker-properties.json`; later
file edits are submitted on the next process start. Property Index updates
remain separate and are never derived from that file. If the Server Identity
registry is reset, remove the affected sandbox explicitly before allowing a
new long-lived Worker ID to be issued.

The phone-number group references `phonenumber.e164`,
`phonenumber.country`, and `phonenumber.original-carrier`. The string-utils
group references `string.md5`, `string.sha1`, and `string.base64.encode`.
Every Worker in a group receives the same immutable business Definition
extension list and shared Handler instances. Core composes the final registry;
its fixed built-in set is currently empty. Connection identity remains a
Transport-generated Adapter Result, while connection close is a Transport
lifecycle instruction; neither is a business Definition. WorkerGroup `eventCodes` in
`group-config-json` are a
display/recommendation summary and may lag the local extension list in
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
External Worker applications remain supported. They persist a client key,
recover their platform-issued Worker ID through Identity registration, build
one Bind control request for every start session, connect with
an Adapter-directed `worker.connection.identify` Result, and own their own
process lifecycle.

### Android Worker Demo Profile

The `android-worker-demo` profile exists for the installable
[`integrations/android-websocket-worker`](../integrations/android-websocket-worker/)
application. It initializes the advisory `android-demo-workers` catalog entry,
starts only the `android-demo-websocket` Adapter on `127.0.0.1:18085`, and
leaves Scenario Worker configuration empty. The Android App performs its own
public Register, Endpoint Bind, and Worker connection flow; Server does not
construct or manage that Worker.

The demo App restores its long-lived Worker ID but performs Bind on every
Worker start. The returned Endpoint URI is reused only by the WebSocket
Client's bounded temporary reconnects. Exhaustion returns control to Core,
which prepares and Binds again; the URI is not persisted by Android.

The profile publishes a device-local URI because the documented real-device
path uses `adb reverse` for ports `18082` and `18085`. This is a debug
deployment address, not an Adapter discovery or authentication mechanism.

```text
./gradlew :server_jvm:bootRun \
  --args="--spring.profiles.active=android-worker-demo"
```

Defaults:

```text
Java Runtime API Server  http://127.0.0.1:18082
Python Kernel Server     http://127.0.0.1:18080
connect timeout          1s
read timeout             5s
Kernel Redis              redis://localhost:6379/15
Kernel Redis prefix        default
Adapter instances          none by default
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

The finite Scenario Worker acceptance is owned by
[`integrations/worker-capability-rpc`](../integrations/worker-capability-rpc/).
The repository JVM workflow starts this Server with the `scenario-workers`
profile and proves Identity Register, Endpoint Bind, Adapter route validation,
20 WebSocket Worker connections, and 60 targeted single-Item RPC results. This
cross-process proof complements rather than replaces the Server integration
suite above.

Authentication, same-endpoint multi-instance ownership, pending/ack,
failure-result projection, historical storage, tenant model, and quota remain
out of scope.
