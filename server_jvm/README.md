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

WorkerGroup RPC client
  -> resolve the profile-owned internal Task from the WorkerGroup path
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
transport/worker-delivery-contract
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
  finite Adapter construction factory, one package-private scheduling
  mechanism per isolated instance, one Netty resource-lifecycle owner per
  instance, finite WebSocket/line-Socket framing protocols, scheduled Processes,
  child Channels, bound routes, and bounded non-blocking delivery
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
an `AdapterProcessManager` with bounded Command/Report Processes, every
accepted child Channel, the current bound route Registry, and encoded Report
buffer through three Netty-specific layers: the Adapter aggregate, one shared
connection mechanism, and one complete protocol-specific physical Server. It
consumes the existing batch HTTP API through loopback and has no
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
POST /api/v1/worker-groups/{workerGroupId}/items:call
POST /api/v1/tasks/{taskId}/results:load
POST /api/v1/runtime-view/worker-groups:batch-get
POST /api/v1/runtime-view/worker-groups/{workerGroupId}/workers:preview
GET  /api/v1/runtime-view/configured-resources
POST /api/v1/task-batches/input-files/{fileName}
POST /api/v1/task-batches/runs
GET  /api/v1/task-batches/output-files/{fileName}
```

These `task-batches` routes exist only under the `scenario-workers` Profile.
Inputs are create-only UTF-8 `.txt` files below `data/rpc-task/input`. A run
selects one configured WorkerGroup, one EventCode, and one top-level Payload
key. Server resolves the Group's existing long-lived Task, appends every line
as one ordinary Item in a single batch, and loads only pending Result IDs at a
fixed 100 ms interval until the request's wait budget expires. Each completed
round is flushed to a temporary JSONL. Completion atomically publishes
`{runId}.jsonl`; exhausted waiting publishes `{runId}.partial.jsonl`. Runs may
execute concurrently. Published input and output files remain until manually
removed. The API does not use advisory Group `eventCodes` as authorization.

The Server-served Vue entry points are:

```text
http://127.0.0.1:18082/runtime/workers
http://127.0.0.1:18082/runtime/tasks
http://127.0.0.1:18082/runtime/task-batches
http://127.0.0.1:18082/scalar
http://127.0.0.1:18082/overview.htm
```

The Task Batch page is a desktop Lab client for the API above. It selects Group
and Event from the configured-resource directory, uploads one text input, runs
one batch, keeps only the current browser session's terminal summaries, and
downloads published JSONL on demand. Direct navigation and refresh of the Task
Batch route forward to the Vue entry point.
The frontend sidebar links to Scalar and the static architecture overview on
the same Server origin.

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
Item rule. For `ITEM_DRIVEN`, Java requires a JSON-compatible object and
persists it opaquely; `{}` explicitly means no Worker restriction within the
Task's WorkerGroup, while `null` remains invalid. The Python matcher owns the
evolving rule DSL. Empty rules use one bounded due-HOT Worker Score query and
exact score CAS. Rules with explicit `workerId $eq/$equal/$in` retain the
bounded point path. Other non-empty rules cannot discover a Worker universe and
fail closed. Descriptor and explicit `index.*` reads are bounded point reads
for the already selected Worker IDs, never Group scans or index intersections.

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

## Runtime View

Runtime View is a read-only operator view over Profile-configured resources
and bounded owner reads. It does not expose Redis, Worker or Task score, lease,
transport session, payload, lifecycle status, history, global discovery, or
stable pagination.

The configured resource directory accepts no caller-provided identities:

```http
GET /api/v1/runtime-view/configured-resources
```

It preserves `group-config-json` order and returns each configured
`workerGroupId -> taskId` coordinate with nullable WorkerGroup and Task
descriptors. The Server reads only those manifest-bounded identities through
`WorkerResourceCatalog` and `TaskResourceCatalog`. Missing descriptors remain
visible as `null`; identity drift or provider failure returns `15002 / 503`.
The Task projection contains `taskId`, `workerGroupId`, `taskType`,
`allocationRule`, `config`, and `emptyCloseAtMillis`. It does not expose Task
approval, running state, Item totals, Result totals, or Score.

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

WorkerGroup RPC v1 accepts one existing `TaskItemRequest` plus an optional
`waitTimeoutMillis` (30 seconds by default, 60 seconds maximum):

```json
{
  "item": {
    "messageId": "call-1",
    "eventCode": "device.rpc",
    "createdAtMillis": 1,
    "payload": {"method": "status"},
    "allocationRule": {}
  },
  "waitTimeoutMillis": 30000
}
```

The `workerGroupId` exists only in the URL. The Server resolves the
Profile-owned internal Task and sends the Item unchanged through the normal
Task data path; neither the request Item nor response exposes that Task ID or a
selected Worker. Unknown configured Groups return `404`. The caller supplies
the scheduling identity: a new logical call uses a new
`messageId`, while retrying the same logical call reuses its original
`messageId`.

A last-success payload observed in the wait window returns `200` with
`status=succeeded`, `messageId`, and `opaqueResultPayload`.
Otherwise the request returns `202` with `status=pending` and `messageId`.
Pending does not distinguish executing, retrying, Worker failure,
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
`system-polling`. A long-lived Adapter calls `verify-binding` when its
process-local route Registry first sees a workerId and exposes the Channel to
command delivery only when the persisted Binding matches the Adapter's
endpoint-manager identity. Every physical connection still sends identity;
verified reconnects skip this Server read until the Adapter restarts.
The Adapter result request carries an array of encoded `DeliveryReport` strings.
Server accepts `WORKER` success/failure Reports targeting `TASK`; it accepts an
`ADAPTER` `2...` Report only when `sourceId` equals the path
`endpointManagerId`. Server appends the valid subset and
returns both `acceptedCount` and `rejectedCount`. The point Worker result
endpoint separately requires `src=WORKER`, `sourceId` equal to the path
workerId, and an outcome of `200` or Worker-owned `3...`.

Server-local CONTROL_ONLY calls:

```text
POST /api/v1/worker-groups/{workerGroupId}/workers/controls:call
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/controls:call
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     control-commands:consume
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     control-results:append
```

The Worker batch request applies one Command to an explicit, input-ordered
set of Workers:

```json
{
  "workerIds": ["worker-1", "worker-2"],
  "messageType": "worker.properties.snapshot",
  "opaquePayload": "{}",
  "waitTimeoutMillis": 3000
}
```

A valid request always returns one HTTP `200` aggregate, including rejected or
timed-out targets:

```json
{
  "controlBatchId": "5d8abf59-8048-4b7f-8b20-a149384c7964",
  "status": "partial",
  "results": {
    "worker-1": {
      "status": "observed",
      "outcomeCode": "200",
      "opaqueResultPayload": "{\"battery\":87}"
    },
    "worker-2": {
      "status": "rejected",
      "reason": "control-only-required"
    }
  }
}
```

This is a bounded, best-effort Server memory channel, not Kernel scheduling or
reliable delivery. One Worker request names `1..100` unique Worker IDs in one
Group and applies one Command payload to all of them. Server performs one
bounded Worker descriptor read, one bounded score read, and one bounded
Binding read, then returns one ordered aggregate response. Targets may span
multiple Adapters. A valid request returns HTTP `200`: aggregate status is
`observed` only when every target produced valid evidence and otherwise is
`partial`. Per-target status is `observed`, `unobserved`, or `rejected`;
Worker-owned failure outcomes are still observed evidence.

Each Adapter has one unconsumed Command slot per target. A later call for that
target replaces only that slot and marks the old batch target
`unobserved/replaced`; other targets in the old batch continue independently.
Adapter consumption is destructive. Matching `DeliveryReport(dst=SYSTEM)`
evidence completes the current target; late, duplicate, or mismatched evidence
is rejected and never retained. Timeout marks every unresolved batch target
`unobserved/timeout`. HTTP disconnect removes that batch's still-unconsumed
slots, and shutdown marks unresolved targets `unobserved/shutdown`. There is no
Redis mailbox, result query, retry ledger, or background cleanup thread.

A Worker-targeted batch calls
`WorkerScoreCore.getScoreStates(workerGroupId, workerIds)` exactly once and
admits a Worker only when the decoded `timeMillis` equals the Kernel pause
time. It does not write, release, or reinterpret the opaque score. The pause
check is best-effort admission evidence rather than an execution lock.
Missing Workers, scores, Bindings, configured endpoints, Polling endpoints,
and non-paused Workers become per-target rejections; an owner read failure
rejects the whole request. Adapter-targeted calls use the same aggregate
response contract but do not read Worker state. Neither route creates
pause/resume behavior.
The current Netty Adapter does not yet consume these new paths, so this Server
slice is API-ready but not a completed Adapter/Worker feature.

```yaml
xa.mass.control-call:
  default-wait-timeout-millis: 3000
  max-wait-timeout-millis: 10000
  max-commands-per-adapter: 1000
  max-pending-calls: 10000
```

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

The default Server configuration defines only Adapter Remote API connection
defaults. It does not construct a Client or create/start any Adapter instance:

```yaml
xa.mass.worker-delivery.adapter:
  http-client:
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
reports ready. Server passes `base-url + request-timeout` to the finite factory;
each Adapter creates one private HTTP client behind its three Remote APIs. A WebSocket Worker
connects to the instance's fixed WebSocket path; a Socket Worker connects to
its TCP port. Both first send
`DeliveryReport(src=WORKER,sourceId=workerId,dst=ADAPTER,`
`messageType=worker.connection.identify,payload="null")`;
there is no generic connection-message envelope or identity ACK. The first
occurrence of a workerId in one Adapter process is checked against Server
Binding. Input arriving behind that identity during the asynchronous check is
dropped. Successful verification is cached in that Adapter's protocol route
directory; ordinary disconnect removes only the active Channel, and a later
identity reconnects without another Server read. The cache is process-local,
has no TTL, is cleared by Adapter close/restart, and is neither persistent
Binding nor authentication or online truth. A definite route rejection may
produce `ADAPTER/worker.connection.close`, while remote API unavailability only
closes the physical connection. An empty or absent `instances` map starts no
active Adapter.

Instances must use distinct IDs and listener ports. Do not duplicate an
endpoint-manager ID for throughput. Each instance declares exactly one
`TASK_COMMAND` and one `TASK_REPORT` Process. Both are scheduled from one finite
Process list owned by `AdapterProcessManager` on the Adapter's existing two
scheduler threads. Server only supplies the declarations; it does not manage
the scheduler or shutdown phases. The Command
Process owns remote consumption, one bounded local queue, expiry, and
non-blocking Channel delivery. The Report Process owns one separate bounded
queue, pending-batch retry, and remote submission. Their intervals and queue
capacities live under each Process entry; there are no flat pump fields or a
combined HTTP façade owner. There is no producer-source split.

### Built-in Worker Assembly

Built-in business Workers are opt-in. The Server profile owns advisory
WorkerGroup directory metadata, while `scenario_workers_jvm` owns its local
Definitions, persistent Lab files, and Worker execution. The two JSON documents
remain deliberately independent:

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
            processes:
              - type: TASK_COMMAND
                interval: 100ms
                consume-limit: 100
                queue-capacity: 1000
              - type: TASK_REPORT
                interval: 1s
                queue-capacity: 1000
    worker-binding:
      endpoints:
        scenario-websocket:
          transport-type: WEBSOCKET
          public-uri: ws://127.0.0.1:18083/api/v1/worker-delivery/websocket
    worker-assembly:
      runtime-api-base-url: http://127.0.0.1:18082
      sandbox-root: data/scenario-workers
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
      capability-assembly-json: |
        {
          "scenario-phone-number-workers": {
            "eventCodes": [
              "phonenumber.e164",
              "phonenumber.country",
              "phonenumber.original-carrier"
            ]
          }
        }
```

Both JSON values default to `{}`. The checked-in
`scenario-workers` profile declares one WebSocket Adapter and three advisory
Worker capability groups. It creates or reuses one deterministic long-lived
`ITEM_DRIVEN` RPC Task per configured Group. Those Tasks are internal
coordinates: clients call the WorkerGroup route and never receive a Task ID.
`capability-assembly-json` selects the Groups and concrete built-in Event
Definitions hosted by the local `scenario_workers_jvm` aggregate; it is not a
Worker state or Worker Properties document.
The two JVM Scenario groups discover their replicas from the fixed local Lab root
`data/scenario-workers`; `capability-assembly-json` contains no Worker entries.
The third catalog entry, `android-demo-workers`, is reserved for the external
Android App and is deliberately absent from `capability-assembly-json`.
Its advisory catalog lists `android.state.read`, `android.battery.read`, and
`android.string.digest`; the last one accepts
`{"algorithm":"MD5","value":"..."}` and is implemented by the Android App
rather than the local JVM Scenario Worker aggregate.
Omitted runtime fields use a 10-second request timeout and the default bounded
connection policy.

The Lab applies one rule independently to each configured WorkerGroup. A
missing `data/scenario-workers/{workerGroupId}` directory is initialized from
that Group's checked-in defaults through a validated staged directory. An
existing Group directory is never seeded, merged, repaired, or upgraded; its
exact direct JSON contents are loaded, and an empty directory means zero local
Workers. Delete one Group directory to reset that Group on the next start, or
delete the Lab root to reset all configured Groups. Unconfigured directories
are ignored.

```text
./gradlew :server_jvm:bootRun --args="--spring.profiles.active=scenario-workers"
```

The `bootRun` task resolves the Lab to the repository-level
`data/scenario-workers` directory. When running the boot JAR directly, launch
it from the repository root so the same relative path is used.

During startup the Server initializes WorkerGroup directory entries through the
WorkerGroup owner, creates or validates and approves each Group's persistent
RPC Task, starts configured Adapters, then invokes one aggregate
`ScenarioWorkers` handle. An existing Task is reused only when its descriptor
exactly matches the Profile contract; a conflict fails startup. Scenario
preflights the configured Group directories,
loads or registers each persistent Worker ID, binds it with its complete Worker
Properties, and starts every real WebSocket transport against the returned URI.
Aggregate start does not wait for initial Adapter verification. Adapter route
verification only compares the persisted Endpoint Binding; successful
verification is followed by process-local connection activation.
Shutdown closes Scenario transports before Adapters. WorkerGroup directory
entries and persistent RPC Tasks are not rolled back, closed, or removed.

Every Worker owns one file named `{clientWorkerKey}.json` under its configured
Group directory. It contains schema version 1, optional persisted `workerId`,
and the complete `workerProperties`. Scenario Workers do not configure or
update Property Indexes.
Register writes the first Worker ID back to that same file atomically; later
starts reuse it and Bind again. File edits take effect on the next Server start.
The Lab is writable local test state, not a security boundary or multi-process
store. If the Identity registry is reset, remove `workerId` from the affected
Worker JSON files, or delete the affected Group directory when restoring that
Group's checked-in defaults is intended.

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
`capability-assembly-json`; neither Server nor Kernel enforces equality.

Worker Assembly does not expose a new Kernel operation, access Redis, start a
second scheduler, or bypass the Adapter through an in-process path.
`ScenarioWorkers` is the only public local lifecycle handle; it is not an
implementation SPI or plugin system. Server does not import Scenario business
Definitions or Handlers. Scenario does not import Kernel or Server
implementation types. Closing the handle
only releases local network resources and preserves the Worker JSON files.
The profile and Adapter remain Server-owned, while capabilities and concrete
Worker resource lifecycle belong to `scenario_workers_jvm`.
External Worker applications remain supported. They persist a client key,
recover their platform-issued Worker ID through Identity registration, build
one Bind control request for every start session, connect with
an Adapter-directed `worker.connection.identify` Result, and own their own
process lifecycle.

### Unified Scenario Demo Profile

The installable
[`xa-android/worker-demo`](../xa-android/worker-demo/)
application uses the same `scenario-workers` profile as the JVM demo. That
profile initializes the advisory `android-demo-workers` catalog entry and
shares the `scenario-websocket` Adapter on `127.0.0.1:18083`. The Android App
performs its own public Register, Endpoint Bind, and Worker connection flow;
Server and `scenario_workers_jvm` do not construct or manage that Worker.

The demo App restores its long-lived Worker ID but performs Bind on every
Worker start. The returned Endpoint URI is reused only by the WebSocket
Client's bounded temporary reconnects. Exhaustion returns control to Core,
which prepares and Binds again; the URI is not persisted by Android.

The profile publishes a device-local URI because the documented real-device
path uses `adb reverse` for ports `18082` and `18083`. This is a debug
deployment address, not an Adapter discovery or authentication mechanism.

```text
./gradlew :server_jvm:bootRun \
  --args="--spring.profiles.active=scenario-workers"
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
WorkerGroup RPC wait       30s default / 60s maximum
WorkerGroup RPC waiter limit 10000
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
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:redisOwnerIntegrationTest
KERNEL_COMMAND_INTEGRATION_URL=http://127.0.0.1:18080 \
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```

The Redis Owner proof runs without Python. The Runtime Boundary proof runs
against an already healthy Python Kernel and proves `TASK_DRIVEN` through the
real Java polling Worker and `ITEM_DRIVEN` through Netty WebSocket and Socket
Adapter endpoints. All paths use the Server HTTP boundary, Python
scheduling/ResultRouting, Java last-success query, and exact Worker release.

The finite Scenario Worker acceptance is owned by
[`integrations/worker-capability-rpc`](../integrations/worker-capability-rpc/).
The repository Task Batch lane starts this Server with the `scenario-workers`
profile. That Profile also enables `/api/v1/task-batches`: it accepts bounded
text inputs under `data/rpc-task/input`, resolves a configured Group's
long-lived Task, appends each run as one Item batch, polls pending Results in
batches, and incrementally writes an atomically published JSONL under
`data/rpc-task/output`. Scheduling concurrency remains owned by Kernel
scheduling and Worker leases. The acceptance proves 20
persistent, globally unique Worker identities plus Identity Register, Endpoint
Bind, Adapter route validation, six Task Batch runs, and 60 results. Results are
not attributed to a specific Worker. This
cross-process proof complements rather than replaces the Server integration
suite above.

Authentication, same-endpoint multi-instance ownership, pending/ack,
failure-result projection, historical storage, tenant model, and quota remain
out of scope.
