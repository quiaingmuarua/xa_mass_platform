# XA Mass JVM Runtime API Server

Status: current external Runtime API, incremental Kernel provider assembly and
configured Server runtime host.

`server_jvm` owns:

- the versioned `/api/v1` HTTP boundary, validation and error mapping;
- provider assembly over `kernel_jvm` owner contracts;
- ordered writes and Runtime View composition over the independent
  `worker_matching_jvm` facts/rule owner;
- fixed Pacer preset selection, Spring lifecycle delegation and Health
  projection for the
  single `kernel_pacer_jvm` Runtime, plus public OpenAPI/Scalar surfaces;
- Worker Identity and persistent Endpoint Binding;
- bounded application use cases such as finite Task Result export, managed
  Task Call and DIRECT_CALL correlation;
- configured WorkerGroup seed and Adapter startup order.

It does not own Kernel candidate selection, Worker lease, TaskItem claim,
retry, recovery, Task finality, allocation-rule interpretation, Adapter
connection routing or Worker event execution. See the root
[architecture entrypoint](../README.md).

## Runtime Shape

```text
Public API
  -> Controller and Server use-case service
  -> kernel_jvm or worker_matching_jvm owner contract
  -> owner-local Java Redis provider

WorkerMatchingAssembly
  -> persistent Worker/Platform facts and PRECOMPUTED Candidate rules
  -> one bounded PRECOMPUTED Demand consumer writing Candidate Cache

KernelPacerAssembly
  -> kernel_pacer_jvm KernelPacerRuntime
     -> Java ResultConvergenceApplication
        -> TASK_SUCCESS / TASK_FAILURE / optional ADAPTER_EVIDENCE lanes
     -> Java DispatchConvergenceApplication
        -> RUNNING INITIAL initialization lane
        -> RUNNING NORMAL allocation / dispatch / optional serviceability lanes
     -> one bounded reverse shutdown

Worker Identity / Binding
  -> Server-owned Redis boundary

Worker Delivery
  -> point or Adapter batch API
  -> DeliveryCommand / DeliveryReport owner runtime

Configured deployment
  -> register advisory WorkerGroups with their Task Calls
  -> start Adapter Manager
  -> become ready without starting any Worker process
```

Task control, Task data, Worker resources, scheduling and delivery operations
use Java Redis providers. Java owns every production Pacer. Missing JVM
operations outside the production caller closure fail explicitly; there is no
HTTP fallback or Server scheduler.

Provider ownership is deliberately mixed but explicit:

| Boundary | Current provider/owner |
| --- | --- |
| Task create, approve, close and Task Call Item submission | Server writes PRECOMPUTED Candidate rules before Kernel Task records; ON_DEMAND Item selectors are normalized by Kernel before Item persistence; lifecycle remains Kernel-owned |
| Worker resources and scheduling operations | Matching owns Properties; Kernel owns identity/Group/Endpoint metadata and Score |
| DeliveryCommand consume and DeliveryReport append | Java Redis delivery providers |
| Result Convergence | `kernel_pacer_jvm` fixed Task success/failure and optional Adapter Evidence lanes over Java owners |
| Worker Serviceability Dispatch bridge | shared Task-source Kernel lane plus lowest-priority Server Adapter snapshot construction |
| Worker Identity and Endpoint Binding | Server-owned Redis boundaries |
| Managed Task Call and finite Result export | Server-bounded use cases over Kernel Task Call submission, Task score observation and Result owner reads |
| Worker Direct Command slot | `WorkerCommandRuntime` shared Redis Hash |
| Adapter Direct FIFO, waiter and correlation | Server instance memory |
| Assignment Dispatch | `kernel_pacer_jvm` orders and holds PRECOMPUTED demand or directly acquires normalized ON_DEMAND targets, then owns Score renewal, uniqueness, lease and claim |
| Operations outside current production callers | Explicit JVM gaps |

WorkerGroup registration creates no Server mapping or second Task catalog. In
addition to the create-only Group declaration, it derives one internal Task
coordinate, creates the fixed `ON_DEMAND_ITEM_RULE + PARK_WHEN_IDLE` descriptor
through Kernel owners, and approves it. Calls submit one Item through the
Kernel Task Call command and observe its Result projection through one shared
probe; both `succeeded` and `failed` complete the bounded wait, while only an
absent Result remains `not_observed`. This projection does not establish the
TaskItem Score finality observed by Kernel lifecycle paths.
Finite Task input remains caller-owned and is appended through the ordinary
Task data API in chunks of at most 100. Result export waits only for a finite
Task's `TERMINAL` score, scans the existing Result owner Hash, and streams a
request-local JSONL file; Server owns no Lab input/output directory.
Ordinary Task data append remains a pure data write and does not release the
Kernel-private idle park; pause/resume calls the Worker score owner;
DIRECT_CALL correlates caller-selected targets without
creating Task or Result Routing truth. Its single public call is scoped to one
configured Adapter. A top-level `opaquePayload` targets that Adapter; supplying
one WorkerGroup plus a `1..100` entry `workerId -> opaquePayload` map targets
only Workers currently bound to that Adapter. The two request modes are
exclusive, and Server never partitions one Direct Call across Adapters.
`messageType` and each opaque payload pass through unchanged. Server does not
enumerate event support or convert an unknown event into an HTTP admission
error; the Adapter (`23005`) or Worker (`3302`) returns an observed execution
result. Future API Session authorization may restrict caller/target/event
access before this use case, but it is not a DIRECT_CALL event whitelist.

Generic public Task creation is scoped by an existing WorkerGroup and has no
profile selector:

```text
POST /api/v1/tasks
```

The request identifies one registered WorkerGroup and contains allocation plus
numeric Task configuration. Server generates the `task-{UUID}` coordinate and
creates only `PRECOMPUTED_TASK_RULE + CLOSE_WHEN_IDLE`; callers do not supply a
Task ID or Task type. Multiple finite Tasks may belong to the same Group.

```json
{
  "workerGroupId": "scenario-string-utils-workers",
  "allocationRule": {},
  "priority": 50,
  "maximumCandidateWorkers": 10,
  "maxRetryTimes": 3
}
```

Every registered WorkerGroup also owns exactly one managed, approved
`ON_DEMAND_ITEM_RULE + PARK_WHEN_IDLE` Task. Registration returns its Task ID:

```text
POST /api/v1/worker-groups/{workerGroupId}:register
```

```json
{
  "workerGroupId": "scenario-string-utils-workers",
  "taskId": "scenario-rpc-scenario-string-utils-workers",
  "status": "registered"
}
```

The registration request contains only the Group declaration and no public
Task configuration. Success guarantees both the exact Group descriptor and the
exact derived Task plus approval. It returns `already_registered` only when
both already exist; repeating an exact legacy Group declaration backfills a
missing Task Call and returns `registered`. Group descriptor drift or derived
Task descriptor drift returns conflict. Re-registration always returns the
same Task ID. Callers use that response value and must not derive the naming
formula. A diagnostic caller that no longer has the registration response may
inspect the bounded Task Runtime window:

```text
POST /api/v1/runtime-view/tasks:preview
```

The request selects the highest `1..100` Task Score coordinates. Runtime View
then performs one bounded Task descriptor read and one bounded WorkerGroup
descriptor read, preserving Task Score Owner order. A caller may select an
expected Managed Task only by exact Group, allocation mechanism and idle
disposition. The window is unstable and incomplete, so it is an observation
surface rather than a guaranteed point lookup or registration repair path.
Group create, Task create and approval remain separate owner operations, not
one transaction. If Task provisioning fails after Group creation, the Group
remains and the request fails; retrying the exact Group declaration re-reads
owner truth and converges the interrupted registration.

All public Task data operations are Task-ID-addressed:

```text
POST /api/v1/tasks/{taskId}/approve
POST /api/v1/tasks/{taskId}/close
POST /api/v1/tasks/{taskId}/items
POST /api/v1/tasks/{taskId}/items:call
POST /api/v1/tasks/{taskId}/results:load
POST /api/v1/tasks/{taskId}/results:export
```

Finite Tasks support explicit approval, close, ordinary Item append and Result
load. Managed Tasks support synchronous Item Call and Result load; their
lifecycle and ordinary append remain non-public. Calling an operation with the
wrong public Task type returns `400/12008`; a missing Task returns
`400/12002`.

Approve and close return the shared action effect
`{"status":"applied"}` when owner state changes and
`{"status":"unchanged"}` when the Task is already in the requested state.
Ordinary Item append keeps its direct Message-ID-keyed Map: each accepted Item
has `{"status":"applied"}`, while an independently rejected Item has
`{"status":"rejected","code":...,"message":"..."}`. A whole-request
failure still uses the non-2xx `ApiErrorResponse`; `rejected` is not a
single-resource success response.

The Tasks API follows the repository-wide HTTP response contract documented
below: HTTP is the coarse processing class, while the numeric business code is
the detailed rejection reason. Successful DTOs remain use-case-specific and
are not wrapped in a common envelope.

Within `/api/v1`, a body containing only one scalar, one collection or one Map
uses that JSON value directly. A dedicated Contract type is retained only when
the body combines fields, represents an independently meaningful structured
resource or enforces a cross-field invariant. Consequently finite Item append
accepts a direct `TaskItemRequest[]`, Result load accepts a direct
`messageId[]`, and both return their Message-ID-keyed outcome Map directly.
The removed object envelopes are not compatibility formats.

`results:export` supports only finite Tasks. It observes the Task score once
and returns `400/12010` immediately unless the Task is already `TERMINAL`.
Once terminal, Server iterates the Task-scoped unified Result HASH through
bounded owner `HSCAN COUNT 1000` pages, ignores entries
classified as failed, deduplicates Redis cursor observations by caller-owned
`messageId`, and streams `application/x-ndjson`. Only one
Result scan and temporary-file generation may run per Task in one Server
process; an overlapping request returns `400/12009`. The guard is released
after file generation, so independent response transfers may overlap. Each
line contains only `messageId` and the unchanged `opaqueResultPayload`;
ordering is not a contract. The temporary file is deleted after the response
stream closes, including failure paths.

Public Item requests contain caller-owned `messageId`, Event Name, Payload,
optional priority and optional `ttlMillis`. Server stamps creation time and
derives the absolute expiry. Finite Task append omits `workerSelector`;
managed Task Call requires a finite Selector array, where `[]` means no Worker
restriction inside the Group and `$eq`/`$in` can name explicit Worker IDs.

`items:call` accepts `1..100` Items, submits the bounded batch once and
synchronously waits within the caller's `waitTimeoutMillis`. The response is a
Message-ID-keyed result map. Once submission is accepted it returns HTTP `200`;
each observed entry is `succeeded` or `failed`, while timeout, saturated
observation capacity, or Registry shutdown marks only the remainder
`not_observed` without inferring their runtime state. A Dispatch-terminal
failed Result completes that Item's waiter without a payload. It preserves all
immediately observed succeeded or failed entries.
Observation saturation does not return `429`. Duplicate Message IDs in one
request use the latest Item and produce one response entry. The caller can
later read the same Message IDs through the same Task-ID-scoped result route.
Neither route selects a Worker. Server passes the finite Item
`workerSelector` to the Kernel parser, then appends a TaskItem containing only
explicit target Worker IDs or an empty ANY target. Worker Matching is not
called by this ON_DEMAND path.

`results:load` accepts a direct JSON array and returns one state object for
every deduplicated requested Message ID in a direct Map: `succeeded`, `failed`,
or `not_observed`. Only `succeeded` includes
`opaqueResultPayload`; failed carries no Worker payload or reason. A late
success may replace an earlier failed snapshot, so each response is a read-time
view rather than an immutable historical event. Server does not read TaskItem
score or Task score to derive these states. Consequently `items:call` or
`results:load` may report `succeeded` while the independent Item Score remains
`ACTIVE` or `FINAL_FAILED`. Score-based lifecycle, statistics and Runtime
projections continue to follow Kernel Score truth; Server neither coordinates
nor repairs the two resources.

Task Call remains at-least-once. Submission spans existing owner operations,
so an Item write followed by an unconfirmed idle-park release repair can still return `503`.
Server does not retry or roll back that submission; callers should retain the
original Message IDs and reconcile them through `results:load` rather than
assuming every non-2xx response means no execution occurred.

```json
{
  "items": [
    {
      "messageId": "caller-message-001",
      "eventCode": "extension.worker.string.md5",
      "payload": {"value": "hello"},
      "ttlMillis": 30000,
      "workerSelector": []
    }
  ],
  "waitTimeoutMillis": 30000
}
```

## WorkerGroup And Worker Preparation

WorkerGroup is a predeclared control-plane resource. The public registration
route is create-only and also provisions its Task Call:

```text
POST /api/v1/worker-groups/{workerGroupId}:register
```

An equivalent `attributes + eventCodes` declaration with an exact approved
Task Call returns `already_registered`; a different Group declaration returns
`400/15006` and never updates the stored Group. Attributes and Event Names are
directory metadata, not Worker Matching facts, Dispatch evidence, or
per-Worker capability truth.

Runtime View offers bounded explicit-coordinate and preview reads:

```text
POST /api/v1/runtime-view/worker-groups:batch-get
POST /api/v1/runtime-view/worker-groups:preview
POST /api/v1/runtime-view/tasks:preview
```

Batch-get reads at most 20 explicit IDs in request order. Preview performs one
positive `HRANDFIELD ... WITHVALUES` for `1..100` random Groups. Preview has no
cursor, total, stable order, or completeness meaning; unreadable sampled rows
are counted and omitted from the returned views. Task Preview performs one
descending `ZREVRANGE ... WITHSCORES` for the highest `1..100` Task Score
coordinates, then projects Task and WorkerGroup descriptors with two bounded
batch reads. It exposes only the Owner-defined Score Band, never the raw Score.
A missing descriptor remains a `null` projection; the read does not create,
approve, close or repair a Task. It has no total, cursor, paging or completeness
meaning, and its order is not business priority or execution evidence.

An ordinary Worker start uses one public control call:

```text
POST /api/v1/worker-groups/{workerGroupId}/workers:prepare
```

A Java Manager may optionally prepare `1..100` Workers with one bounded call:

```text
POST /api/v1/worker-groups/{workerGroupId}/workers:prepare-batch
```

Single and batch calls use the same Prepare item DTO:

```json
{"workerKind":"SCENARIO_LAB","transportType":"WEBSOCKET","workerProperties":{}}
```

`workerKind` is optional and defaults to `CLIENT_KEY`, preserving existing
ordinary callers. Android supplies `CLIENT_KEY` explicitly; ordinary Java may
omit it. `SCENARIO_LAB` derives its
Server-owned registration coordinate from immutable
`labInventoryKey + labInventoryLine` string properties (line parses as decimal
`1..100`, preserving the same registration key) and strictly rejects numeric
line values and `clientWorkerKey`; mutable fields such as `labSlot` do not participate in
identity. A batch body is the direct array of `1..100` ordered Prepare items,
all of which must share one kind and transport type. Both HTTP routes enter the
same Server `prepareAll` path;
the single route supplies a one-item list. Server validates the batch shape and
every registration coordinate before side effects, then invokes Identity,
Binding, Matching Facts and minimal Kernel Worker owners sequentially.
The response is an ordered list of the ordinary Prepare response DTO. Only a
complete response returns `200`; completed side effects are not rolled back,
so callers may retry through the same derived coordinates.

`workerKind` only selects the Server-owned registration-key algorithm. It is
not part of the Redis key address: all identities for one WorkerGroup are
fields in the same Group identity Hash. Each algorithm emits a typed,
unambiguous field value, so an arbitrary `CLIENT_KEY` input cannot alias a
`SCENARIO_LAB` coordinate. This cutover intentionally provides no legacy field
fallback or workerId migration; test and deployment scopes may clear old
identity data before using the new contract.

`CLIENT_KEY` Prepare requires an existing Group and a non-blank
`workerProperties.clientWorkerKey`. It resolves or creates the Server-owned
Worker identity, selects or reuses the persistent Endpoint Binding, replaces
the complete Matching-owner Worker Properties snapshot, and initializes
missing Kernel scheduling metadata and Score. These are separate owners and
Redis keys, not one transaction; a repeated Prepare converges interrupted
stages. Ordinary Workers retain only their
Group/client key coordinate and never send a Worker ID hint. Transparent
reconnect reuses the current in-memory identity and Endpoint without preparing
again.

Worker scheduling control and platform Properties changes use the same action
effect contract:

```text
POST  /api/v1/worker-groups/{workerGroupId}/workers/{workerId}:pause-scheduling
POST  /api/v1/worker-groups/{workerGroupId}/workers/{workerId}:resume-scheduling
PATCH /api/v1/worker-groups/{workerGroupId}/workers/{workerId}/platform-properties
```

Pause, resume and Properties patch return `{"status":"applied"}` when the
requested mutation changes owner state and `{"status":"unchanged"}` when the
resource is already at the requested value. A Properties patch still accepts
the direct JSON Properties object; it mutates Matching facts and never writes
Worker Score.
Missing resources, invalid changes and state conflicts use the public
`15008..15010` business codes and never expose the Kernel Owner reason.
Properties Owner failure uses `503/15011`; scheduling Owner failure keeps
`503/15004`.

Runtime View may request one bounded `1..100` Worker scheduling observation.
The existing Java `WorkerScoreCore.getScoreStates` owner operation performs one
batch read; `WorkerSchedulingService` projects only facts derivable from that
Score snapshot. This projection is independent of Adapter connection, Binding
and Task execution evidence.

```text
POST /api/v1/runtime-view/worker-groups/{workerGroupId}/
     workers:scheduling-observe
body: ["worker-1","worker-2"]
```

The response contains one shared `readAt` plus a complete
`statesByWorkerId` map in request order. States are `hot-score-overdue`, `held-hot`,
`paused`, `recovery`, `cold`, or `missing`. They do not expose raw Score and do
not claim to know the active Java Kernel process's HOT eligibility epoch.
`hot-score-overdue` means only that a positive HOT Score precedes the current
100ms slot; it is weaker than the Kernel's floor-aware candidate range.
Provider failure returns the existing Runtime View unavailable error rather
than inventing a Worker state.

Runtime View also exposes one Adapter-scoped, bounded Network observation:

```text
POST /api/v1/runtime-view/endpoint-managers/{endpointManagerId}/
     workers:network-observe
body: ["worker-1","worker-2"]
```

This use case sends the existing
`platform.adapter.worker-connections.snapshot` event through the same
Adapter Direct FIFO, waiter and Result correlation as DIRECT_CALL. It projects
the Adapter response to `connected`, `disconnected`, or `unknown` and never
creates a Server copy of Route truth. `readAt` is the Server observation time.
An Adapter timeout, rejection or malformed payload returns Runtime View
unavailable; it is not converted into the legitimate Adapter state `unknown`.
The caller groups Workers by `endpointManagerId`; Server does not join Adapter
Network with Binding, WorkerGroup, scheduling or execution state.

Worker calls do not require pause or read score. They use
`WorkerCommandRuntime.offerWorkerCommands`: an empty field in the existing
Adapter-partitioned Worker Command Hash is filled, while an occupied field is
rejected as `command-slot-occupied`. The authoritative TASK append path may
replace an offered Direct Command until it is destructively consumed. Timeout,
HTTP cancellation and shutdown finish only the waiter; they do not retract a
Worker Command already offered to Redis.

Adapter-targeted calls enter a bounded Server-memory FIFO. Adapter Commands are
consumed first; any remaining response capacity is filled by exactly one
bounded consume from the shared Worker Command Hash. If capacity still remains,
Server may consume up to 100 coalesced Kernel Serviceability requests and add
one Adapter snapshot Command. Only a Worker Command map key is its workerId;
Adapter and Kernel Command keys are response-local and opaque.

Adapter `results:append` accepts `1..100` strict `DeliveryReport` JSON objects.
The complete batch must have one supported `dst`; Server rejects a mixed or
unsupported batch before calling any semantic Owner, then routes the whole
batch to TASK Result, SYSTEM Direct Call, or KERNEL Serviceability handling.
Owner-local source, correlation, outcome and forward failures remain per-item
rejections. Queue capacity remains an Adapter-local memory bound and is not an
HTTP batch-size declaration. If Kernel Serviceability cannot admit the complete
valid evidence subset, Server returns `503`.
`commands:consume` accepts the JSON integer limit and returns the entry-keyed
Command Map directly. `results:append` accepts the Report object array directly;
only its accepted/rejected count response remains a named structure. All Report
destinations share this path; adding a destination does not add an endpoint.
Exact route schemas are available from the running Server:

```text
Live Scalar Reference   http://127.0.0.1:18082/scalar
Live OpenAPI JSON       http://127.0.0.1:18082/v3/api-docs
Static API Snapshot UI  http://127.0.0.1:18082/api-reference
Static OpenAPI Snapshot http://127.0.0.1:18082/reference/openapi.json
Architecture Overview  http://127.0.0.1:18082/overview.htm
Diagnostic Code UI      http://127.0.0.1:18082/reference/error-codes
Diagnostic Code JSON    http://127.0.0.1:18082/reference/platform-diagnostic-codes.json
```

Only `/api/v1/**` enters OpenAPI. Scalar telemetry, Agent Scalar and external
fonts are disabled. `/scalar` and `/v3/api-docs` are generated from the current
running Server. `/api-reference` reads the committed deterministic snapshot
used by the frontend and Vercel, so it is intentionally read only and can lag
until the snapshot is regenerated.

Regenerate the snapshot after changing a public Controller, DTO, Tag or
OpenAPI description:

```powershell
.\gradlew.bat :server_jvm:exportOpenApiSnapshot
```

The exporter starts an isolated `test` Profile context on a random loopback
port, reads the real `/v3/api-docs`, removes the request-derived `servers`
field, canonicalizes JSON object order, and writes
`frontend/public/reference/openapi.json`. Server tests compare the generated
contract with that committed file and report drift without rewriting it.

OpenAPI Introduction links to the two diagnostic reference paths without
embedding the dictionary or binding codes to operations. Public
`ApiErrorResponse.code` values belong to `server_jvm`; Netty Adapter and Worker
Core codes remain in producer-local namespaces. The packaged JSON is generated
by `distribution/server` from the current compiled enums, excludes Scenario
and downstream capability errors, and makes no cross-version stability claim.

Scalar navigation uses four caller-facing API groups:

| Tag | Surface |
| --- | --- |
| `Worker Resources` | WorkerGroup declaration and Worker preparation or control |
| `Tasks` | Task creation, lifecycle, Item Call and Result access by Task ID |
| `Runtime View` | Read-only bounded runtime projections |
| `Worker Delivery` | Worker/Adapter delivery and best-effort Direct Call |

These tags are documentation navigation, not Redis storage domains or runtime
owners. Redis `scope`, `result` and `dispatch` boundaries do not become public
API categories merely because they own physical keys.

After routing has matched a public Runtime operation, application and use-case
outcomes share one response convention:

| HTTP status | Meaning |
| --- | --- |
| `200` | The use case completed, including idempotent no-op and bounded partial observation results |
| `400 + ApiErrorResponse` | Input, business resource, precondition or current state rejected the request |
| `429 + ApiErrorResponse` | Generic admission capacity was exhausted; currently only Direct Call uses it |
| `503 + ApiErrorResponse` | An Owner or required dependency is temporarily unavailable |

Business resource absence is `400` with its detailed numeric code. Successful
responses keep their natural JSON value or resource/use-case DTO; a scalar,
single collection or single Map is not wrapped merely to name that value.
Application rejection and
unavailability errors use `code`, the stable public default `message`, and
`requestId`. Owner reasons, Redis data and internal exception messages are not
returned.

Framework routing and protocol failures remain coarse HTTP concerns rather
than XA business outcomes: an unknown URL may return `404`, an unsupported
method `405`, an unsupported media type `415`, and an unexpected framework
failure `500`. They are not assigned XA business codes by the application
error mapping.

Worker Delivery is the machine-protocol exception to the single `200` success
rule. Command Poll returns `200` with a Command or `204` when empty; Worker and
Adapter Report append returns `202`; Binding verification returns `204`; and
Adapter Command consume returns `200`. Delivery rejection still uses the same
`400/503 + ApiErrorResponse` contract. No public operation declares a business
`404`, `409` or `422` response.

## Assembly Boundaries

Production packages use stable functional roots. The versioned HTTP surface
centralizes route adapters under `api.v1.controller` and groups wire types under
`api.v1.contract` by Task, Worker, Runtime View and Delivery vocabulary. Task
use cases remain under `task` (`call` and `result`); Worker responsibilities
under `worker` (`group`, `preparation`, `identity`, `binding`, `resource` and
`scheduling`); delivery services under `delivery`; and process-wide provider or
lifecycle wiring under `assembly` (`redis`, `kernel`, `pacer` and `runtime`).
`runtimeview`, `operation`, `error` and `frontend` remain separate Server
surfaces. These packages express ownership and composition boundaries; they do
not add alternate Runtime owners.

### Kernel Providers

Controllers and use-case services depend on `kernel_jvm` and
`worker_matching_jvm` owner contracts.
Provider selection stays in Server assembly. The shared `assembly.redis`
package owns connection and health only; Redis key operations live in
owner-local provider packages.

Worker Prepare composes Server-owned identity resolution and Endpoint Binding,
complete Matching-owner Worker Facts replacement, and minimal Kernel Worker
metadata/Score initialization in that order. The owners and registries remain
separate. Prepare is the sole canonical Worker Properties refresh; a
transparent Client reconnect performs no control operation.

### Worker Delivery

Server owns the Worker Delivery HTTP and owner-provider composition. It
constructs active Adapters only through the finite public Netty factory.

Adapter Command consume and Report append use the loopback Worker Delivery HTTP
boundary. First route verification uses a Server-injected asynchronous port:
one bounded Server queue is drained by one resident virtual thread, and current
Endpoint Bindings are read in batches of at most 100 before the individual
Adapter requests are completed. The queue is transient coordination, not Route
or Binding truth. Adapter lifecycle, schedulers, queues, current route registry
and physical Channels remain owned by `transport/netty-adapter`.

Long-lived Worker identity carries `workerId` in the Report source and exact
`null` payload. Adapter routing and retained verification use only workerId;
WorkerGroup remains outside the Transport route. The optional Kernel
Serviceability Dispatch lane writes Adapter-partitioned probe requests. Server
destructively consumes a bounded request set only at the lowest Command-response
priority and constructs one `KERNEL -> ADAPTER`
`platform.adapter.worker-connections.snapshot` Command. The ordinary Adapter
Result path routes all `ADAPTER -> KERNEL` Reports into the bounded Kernel
Serviceability evidence handoff. This includes periodic snapshots and
Adapter-produced single-Worker Route changes or TASK delivery-expiry evidence.
Server parses neither event nor payload semantics, does not resolve
WorkerGroup, and never invokes the Worker score owner.

For `dst=TASK`, Worker Delivery validates producer identity and the endpoint
code namespace before mapping accepted reports to the Kernel-owned
`TaskResultClass.SUCCESS` or `TaskResultClass.FAILURE` lane. A Worker `200` is
SUCCESS; Worker-owned `3...` and valid Adapter Task rejection are FAILURE.
Kernel Result Routing receives that type and does not reinterpret the raw
error code. Adapter delivery-expiry still emits a separate `dst=KERNEL`
Serviceability report in the same HTTP batch.

Adapter instances may configure `route-cache` and `properties-cache`. The
defaults retain disconnected verification evidence for `10m` with at most
`100000` disconnected Workers, and bound properties by a `64 MiB` encoded-data
budget. Properties have no Server-defined freshness window; their visibility
follows retained Adapter route identity and may also be lost under properties
capacity pressure. These are Adapter-owned process-local policies; Server only
validates configuration and passes the two finite config records into the
Adapter factory. Callers read the properties projection by
DIRECT_CALL to `platform.adapter.worker-properties.snapshot`. Server treats the
event name, opaque input and result payload transparently: it owns neither cache
contents nor update time, route gate, TTL or eviction interpretation. Live
connection state remains a separate
`platform.adapter.worker-connections.snapshot` call; Server does not join the
two projections. The Adapter does not automatically request Worker properties
or turn its cache into a KERNEL Report.

Server-level route verification defaults to a `100000` request queue and a
`5s` Binding-read timeout. Queue rejection, timeout, shutdown, or Binding-owner
failure completes affected verification requests exceptionally; Server does
not retry them. Worker connection retry remains the recovery owner.

### Worker And Scenario Assembly

The default profile starts no Adapter and declares no Scenario WorkerGroup. An
explicit profile or external configuration may:

1. register create-only advisory WorkerGroup declarations and their
   deterministic Task Calls;
2. construct and start configured Adapters.

Server does not parse Worker files, construct business Definitions or own
individual Worker lifecycle, and it never starts a Scenario Worker process.
Those responsibilities belong to the independently launched
[`scenario_workers_jvm`](../scenario_workers_jvm/README.md) Host.

The checked `scenario-workers` profile provides one WebSocket Adapter, two JVM
Scenario WorkerGroup declarations and the advisory external Android demo
Group. Registering those three declarations automatically provisions all three
Task Calls. Server readiness does not depend on a Worker Host. The root
`run_local_runtime.py` defaults to this Profile, starts Server first and starts
the standalone JVM Host only after readiness. Its finite vertical Worker proof
is owned by
[`integrations/worker-correctness`](../integrations/worker-correctness/README.md).

The checked `agentforge` profile is a separate downstream deployment preset.
It starts exactly one `agentforge-websocket` Adapter at 18183, exposes Server
at 18182, uses `profile_agentforge`, and has an empty configured Group manifest.
AgentForge registers its own Groups through the public API; this Profile does
not start or embed AgentForge or Scenario capability code.

## Configuration

Default coordinates:

```text
Java Runtime API Server        http://127.0.0.1:18082
Kernel Redis                   redis://localhost:6379/15
Redis scope                    profile_default
Kernel Pacer preset            DEFAULT
Adapter instances              none
Managed Task Call wait         30s default / 60s maximum
Task Call waiters              10000 maximum
Task Call observations         100000 pending waiter-message associations
Task Call Probe batch          256 due message IDs per round
DIRECT_CALL wait               3s default / 10s maximum
Adapter Direct FIFO capacity   1000 per Adapter
Pending Direct targets         10000 per Server
Serviceability probe requests  10000 per Adapter HASH
Serviceability evidence       10000 per Redis scope
```

Spring Profile controls assembly while `xa.mass.redis.scope` controls the data
boundary; the Redis DB number is not a profile or test discriminator. Scope
syntax and the complete physical ABI are owned by the Kernel
[Redis Keyspace contract](../kernel_jvm/doc/runtime-redis/redis-keyspace.md).

The optional Serviceability handoff uses
`xa_mass:<scope>:worker:serviceability:adapter:<adapterId>:probe_requests` and
`xa_mass:<scope>:worker:serviceability:evidence_results`. These are
Kernel-owned best-effort handoffs, not current connectivity truth. Server
implements only the bounded Adapter request consume and Adapter-evidence append
needed by its HTTP bridge. The fixed Java Kernel Pacer destructively consumes
that evidence LIST and owns its score policy.

The default Adapter section defines only remote API connection defaults. An
Adapter instance is an explicit deployment declaration and must also have a
matching Endpoint Binding entry.

## Run

Start the Java Runtime API from the repository root. Server selects the
checked `DEFAULT` policy preset, constructs the one `KernelPacerRuntime`, and
its Spring adapter starts that Runtime before later lifecycle components:

```text
./gradlew :server_jvm:bootRun
```

Start the checked local Scenario profile from the repository root:

```text
./gradlew :server_jvm:bootRun \
  --args="--spring.profiles.active=scenario-workers"
```

This selects `SCENARIO_LAB` and starts Group/Task seeds, Pacer and Adapter, but
no JVM Worker. For the complete local Lab use the one-command process launcher.
Omitting `--profile` defaults to `scenario-workers`:

```text
python run_local_runtime.py
```

It builds and starts Server first, waits for readiness, then starts the
standalone Scenario Worker Host against `data/scenario-workers`. Existing
Worker files remain persistent local state. Stopping Host closes its network
resources without stopping Server or deleting Workers, WorkerGroups or managed
Task Calls.

The same source launcher can start the checked clean downstream Profile:

```text
python run_local_runtime.py --profile agentforge
```

That path builds and serves the same frontend, then starts Server, Pacer and the
single AgentForge WebSocket Adapter. It does not build or start the Scenario
Worker Host. Unknown Profiles are rejected.

For a repository-independent deployment, extract the
[`distribution/server`](../distribution/server/) Runtime ZIP and start its Boot
JAR directly from the Runtime root with Java 21, external Redis and explicit
Profile and frontend arguments. The schema-v5 manifest lists the supported
`scenario-workers` and `agentforge` Profiles. The Runtime ZIP does not contain
the repository-local Scenario Worker Host; use `run_local_runtime.py` or the
module's Gradle task when that Lab is required. Source `bootRun` remains
available for repository development.

Health endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness covers the JVM process. Readiness requires Worker Matching, Result
Convergence, Dispatch Convergence and Kernel Redis to remain available. The
optional Serviceability lane is part of Dispatch Convergence rather than a
separate lifecycle. The `kernel` health contributor exposes only the aggregate
lifecycle and the two Java convergence-application states; `workerMatching`
reports its bounded consumer state separately.

## Verification

```text
./gradlew :server_jvm:test

./gradlew :server_jvm:redisOwnerIntegrationTest

./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```

The two integration tasks use the checked `integration-test` profile with
Redis at `redis://127.0.0.1:6379/15`. Runtime Boundary selects
`RUNTIME_BOUNDARY_PROOF` and starts one Java Spring context and both Java
convergence applications. No auxiliary Kernel process or second host is
started by the test operator.

The finite lifecycle configuration is `xa.mass.kernel-pacer`: `enabled`,
`preset`, and `shutdown-timeout`. Spring accepts the normal
`XA_MASS_KERNEL_PACER_PRESET` environment override or
`--xa.mass.kernel-pacer.preset=...`; an unknown preset fails configuration
binding before Runtime construction. Normal JVM tests use the `test` profile
with this lifecycle disabled.

`xa.mass.redis` is the single production source for the Redis URL and scope.
`kernel_pacer_jvm` owns the four fixed policy presets and mints one shared HOT
eligibility floor for each Serviceability-enabled Runtime assembly. Server
passes only the selected preset, shutdown timeout and owner dependencies; it
does not interpret scheduling policy. `SERVICEABILITY_DEFAULT` provides normal
production cadence with Serviceability enabled. Runtime Boundary uses a unique
`test_*` scope; Server rejects its proof-only preset for every other scope. The
default and AgentForge profiles use `DEFAULT`; the checked Scenario profile
uses `SCENARIO_LAB` and defaults to `profile_scenario_workers`.

Exactly one Server instance per Redis scope may have the Pacer lifecycle
enabled. Other API replicas must set `xa.mass.kernel-pacer.enabled=false`;
there is no distributed leader election.

Known readiness mismatch: `KernelPacerHealthIndicator` currently reports DOWN
when that lifecycle is disabled, including the API-only replica described
above. The readiness/deployment contract remains unresolved; disabling Pacer
must not be documented as making such a replica readiness-UP. This documentation
correction does not change the indicator or its tests.

Result Convergence starts first and Dispatch Convergence starts second.
Dispatch Convergence owns the fixed Task Initialization, Allocation, Task Dispatch and
optional Serviceability lanes. Worker/Adapter assembly starts after the
aggregate reaches `RUNNING`. Shutdown uses one shared deadline in the exact
reverse order. A failed start rolls back every already-started Java
application.

The Runtime Boundary proof closes real polling, WebSocket and Socket Task
paths. It also calls an unpaused real WebSocket Worker directly, executes a
custom `extension.worker.*` event through a SYSTEM Command and the default
probe/properties/events handlers, observes Adapter connection state, closes
the current Channel, and proves transparent reconnect.

The canonical proof ownership, prerequisites and CI lane selection are in
[`TESTING.md`](../TESTING.md).
