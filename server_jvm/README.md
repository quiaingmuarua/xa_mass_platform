# XA Mass JVM Runtime API Server

Status: current external Runtime API, incremental Kernel provider assembly and
opt-in Scenario host.

`server_jvm` owns:

- the versioned `/api/v1` HTTP boundary, validation and error mapping;
- provider assembly over `kernel_jvm` owner contracts;
- process health, the fixed Python Pacer CLI child lifecycle, and public
  OpenAPI/Scalar surfaces;
- Worker Identity and persistent Endpoint Binding;
- bounded application use cases such as finite Task Result export, managed
  Task Call and DIRECT_CALL correlation;
- configured Adapter and Scenario startup order.

It does not own Kernel candidate selection, Worker lease, TaskItem claim,
retry, recovery, Task finality, Adapter connection routing or Worker event
execution. See the root [architecture entrypoint](../README.md).

## Runtime Shape

```text
Public API
  -> Controller and Server use-case service
  -> kernel_jvm owner contract
  -> owner-local Java Redis provider

KernelPacerAssembly
  -> fixed Python assembly CLI child
  -> exact ready token and bounded stdin-driven shutdown
  -> assignment, result-routing and serviceability Pacers remain Python policy

Worker Identity / Binding
  -> Server-owned Redis boundary

Worker Delivery
  -> point or Adapter batch API
  -> DeliveryCommand / DeliveryReport owner runtime

Configured deployment
  -> register advisory WorkerGroups with their Task Calls
  -> start Adapter Manager
  -> start ScenarioWorkers aggregate
```

Task control, Task data, Worker resources, selected Worker scheduling and
delivery operations use Java Redis providers. Java owns the temporary Python
child's process lifecycle and reports its health; Python still implements the
Pacer policy and exposes no HTTP surface. Missing JVM operations fail
explicitly; there is no HTTP fallback or Server scheduler.

Provider ownership is deliberately mixed but explicit:

| Boundary | Current provider/owner |
| --- | --- |
| Task create, approve, close and Task Call Item submission | JVM owner contracts with Java Redis Task providers |
| Worker resources, selected Task data and Worker scheduling operations | JVM owner contracts with Java Redis providers |
| DeliveryCommand consume and DeliveryReport append | Java Redis delivery providers |
| Worker Serviceability bridge | Lowest-priority Adapter snapshot construction plus transparent Java Redis Adapter-evidence append |
| Worker Identity and Endpoint Binding | Server-owned Redis boundaries |
| Managed Task Call and finite Result export | Server-bounded use cases over Kernel Task Call submission, Task score observation and Result owner reads |
| Worker Direct Command slot | `WorkerCommandRuntime` shared Redis Hash |
| Adapter Direct FIFO, waiter and correlation | Server instance memory |
| Other scheduling internals | Explicit JVM gaps |

WorkerGroup registration creates no Server mapping or second Task catalog. In
addition to the create-only Group declaration, it derives one internal Task
coordinate, creates the fixed `DIRECT_ITEM_RULE + PARK_WHEN_IDLE` descriptor
through Kernel owners, and approves it. Calls submit one Item through the
Kernel Task Call command and observe last success through one shared probe;
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
`DIRECT_ITEM_RULE + PARK_WHEN_IDLE` Task. Registration returns its Task ID:

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
same Task ID. Callers use that response value, or the value projected by
`GET /api/v1/runtime-view/configured-resources`; they must not derive it from
the current deterministic naming formula.
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

Finite Tasks support explicit approval, close, ordinary Item append and result
load. Managed Tasks support synchronous Item Call and result load; their
lifecycle and ordinary append remain non-public. Calling an operation with the
wrong public Task type returns `422`; a missing Task returns `404`.

`results:export` supports only finite Tasks. It waits up to the caller's
`1..300000` millisecond budget (30 seconds by default) for the Task score to
become `TERMINAL`, returning `202 {"status":"not_ready"}` when that is not
observed. Once terminal, Server iterates the Task-scoped success Result Hash
through bounded owner `HSCAN` pages, deduplicates message IDs, and streams
`application/x-ndjson`. Each line contains only `messageId` and the unchanged
`opaqueResultPayload`; ordering is not a contract. The temporary file is
deleted after the response stream closes, including failure paths.

Public Item requests contain caller-owned `messageId`, Event Name, Payload,
optional priority and optional `ttlMillis`. Server stamps creation time and
derives the absolute expiry. Finite Task append forbids an Item allocation
rule; managed Task Call requires an allocation-rule object, where `{}` means no
Worker restriction inside the Group.

`items:call` accepts `1..100` Items, submits the bounded batch once and
synchronously waits within the caller's `waitTimeoutMillis`. The response is a
Message-ID-keyed result map. HTTP `200` means every entry is `succeeded`; HTTP
`202` marks only unobserved entries as `not_observed`, without inferring their
runtime state. Duplicate Message IDs in one request use the latest Item and
produce one response entry. The caller can later read the same Message IDs
through the same Task-ID-scoped result route. Neither route selects a Worker;
the Item allocation rule remains Kernel scheduling input.

```json
{
  "items": [
    {
      "messageId": "caller-message-001",
      "eventCode": "extension.worker.string.md5",
      "payload": {"value": "hello"},
      "ttlMillis": 30000,
      "allocationRule": {}
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
conflict and never updates the stored Group. Attributes and Event Names are
directory metadata, not Matcher, Dispatch, or per-Worker capability truth.

Runtime View offers two intentionally different reads:

```text
POST /api/v1/runtime-view/worker-groups:batch-get
POST /api/v1/runtime-view/worker-groups:preview
```

Batch-get reads at most 20 explicit IDs in request order. Preview performs one
positive `HRANDFIELD ... WITHVALUES` for `1..100` random Groups. Preview has no
cursor, total, stable order, or completeness meaning; unreadable sampled rows
are counted and omitted from the returned views.

One Worker start uses one public control call:

```text
POST /api/v1/worker-groups/{workerGroupId}/workers:prepare
```

Prepare requires an existing Group and a non-blank
`workerProperties.clientWorkerKey`. It resolves or creates the Server-owned
Worker identity, selects or reuses the persistent Endpoint Binding, upserts the
complete Worker Properties snapshot and initializes missing scheduling truth.
These are separate owners and Redis keys, not one transaction; a repeated
Prepare converges interrupted stages. Workers persist only their Group/client
key coordinate and never send a Worker ID hint. Transparent reconnect reuses
the current in-memory identity and Endpoint without preparing again.

Runtime View may request one bounded `1..100` Worker scheduling observation.
The existing Java `WorkerScoreCore.getScoreStates` owner operation performs one
batch read; `WorkerSchedulingService` projects only facts derivable from that
Score snapshot. No new Python Kernel HTTP route is involved. This projection is
independent of Adapter connection, Binding and Task execution evidence.

```text
POST /api/v1/runtime-view/worker-groups/{workerGroupId}/
     workers:scheduling-observe
body: {"workerIds":["worker-1","worker-2"]}
```

The response contains one shared `readAt` plus a complete
`statesByWorkerId` map in request order. States are `hot-score-overdue`, `held-hot`,
`paused`, `recovery`, `cold`, or `missing`. They do not expose raw Score and do
not claim to know the active Python Kernel process's HOT eligibility epoch.
`hot-score-overdue` means only that a positive HOT Score precedes the current
100ms slot; it is weaker than the Kernel's floor-aware candidate range.
Provider failure returns the existing Runtime View unavailable error rather
than inventing a Worker state.

Runtime View also exposes one Adapter-scoped, bounded Network observation:

```text
POST /api/v1/runtime-view/endpoint-managers/{endpointManagerId}/
     workers:network-observe
body: {"workerIds":["worker-1","worker-2"]}
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
Exact route schemas are available from the running Server:

```text
Scalar API Reference  http://127.0.0.1:18082/scalar
OpenAPI JSON          http://127.0.0.1:18082/v3/api-docs
Architecture Overview http://127.0.0.1:18082/overview.htm
```

Only `/api/v1/**` enters OpenAPI. Scalar telemetry, Agent Scalar and external
fonts are disabled.

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

## Assembly Boundaries

### Kernel Providers

Controllers and use-case services depend only on `kernel_jvm` owner contracts.
Provider selection stays in Server assembly. The shared `kernelredis` package
owns connection and health only; Redis key operations live in owner-local
provider packages.

Worker Prepare composes Server-owned identity resolution and Endpoint Binding
with the Kernel Worker upsert. The owners and registries remain separate.
Prepare is the sole canonical Properties refresh; a transparent Client
reconnect performs no control operation.

### Worker Delivery

Server owns the Worker Delivery HTTP and owner-provider composition. It
constructs active Adapters only through the finite public Netty factory.

Each Adapter reaches Server through the same HTTP boundary used by external
Workers. There is no in-process or Redis shortcut. Adapter lifecycle,
scheduler, queues, current route registry and physical Channels remain owned by
`transport/netty-adapter`.

Long-lived Worker identity carries `workerId` in the Report source and exact
`null` payload. Adapter routing and retained verification use only workerId;
WorkerGroup remains outside the Transport route. The optional Kernel
Serviceability Dispatch Pacer writes Adapter-partitioned probe requests. Server
destructively consumes a bounded request set only at the lowest Command-response
priority and constructs one `KERNEL -> ADAPTER`
`platform.adapter.worker-connections.snapshot` Command. The ordinary Adapter
Result path routes all `ADAPTER -> KERNEL` Reports into the bounded Kernel
Serviceability evidence handoff. This includes periodic snapshots and
Adapter-produced single-Worker Route changes or TASK delivery-expiry evidence.
Server parses neither event nor payload semantics, does not resolve
WorkerGroup, and never invokes the Worker score owner.

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

### Worker And Scenario Assembly

The default profile starts no Adapter and no Scenario Worker. An explicit
profile or external configuration may:

1. register create-only advisory WorkerGroup declarations and their
   deterministic Task Calls;
2. construct and start configured Adapters;
3. pass opaque capability assembly JSON, the Lab root and Runtime API base URL
   to `ScenarioWorkers`.

Server does not parse Worker files, construct business Definitions or own
individual Worker lifecycle. Those responsibilities belong to
[`scenario_workers_jvm`](../scenario_workers_jvm/README.md).

The checked `scenario-workers` profile provides one WebSocket Adapter, two JVM
Scenario WorkerGroups and the advisory external Android demo group. Registering
those three declarations automatically provisions all three Task Calls. Its
finite Capability Task proof is owned by
[`integrations/worker-capability-task`](../integrations/worker-capability-task/README.md).

## Configuration

Default coordinates:

```text
Java Runtime API Server        http://127.0.0.1:18082
Kernel Redis                   redis://localhost:6379/15
Redis scope                    profile_default
Pacer CLI config               kernel_design/config/pacer-default.json
Pacer lifecycle state          data/kernel-pacer
Adapter instances              none
Managed Task Call wait         30s default / 60s maximum
DIRECT_CALL wait               3s default / 10s maximum
Adapter Direct FIFO capacity   1000 per Adapter
Pending Direct targets         10000 per Server
Serviceability probe requests  10000 per Adapter HASH
Serviceability evidence       10000 per Redis scope
```

Spring Profile controls assembly while `xa.mass.redis.scope` controls the data
boundary; the Redis DB number is not a profile or test discriminator. Scope
syntax and the complete physical ABI are owned by the Kernel
[Redis Keyspace contract](../kernel_design/doc/runtime-redis/redis-keyspace.md).

The optional Serviceability handoff uses
`xa_mass:<scope>:worker:serviceability:adapter:<adapterId>:probe_requests` and
`xa_mass:<scope>:worker:serviceability:evidence_results`. These are
Kernel-owned best-effort handoffs, not current connectivity truth. Server
implements only the bounded Adapter request consume and Adapter-evidence append
needed by its HTTP bridge.

The default Adapter section defines only remote API connection defaults. An
Adapter instance is an explicit deployment declaration and must also have a
matching Endpoint Binding entry.

## Run

Start the Java Runtime API from the repository root. It validates and starts
the configured Python Pacer CLI child before later lifecycle components:

```text
python -m pip install -r kernel_design/requirements.txt
./gradlew :server_jvm:bootRun
```

Start the checked local Scenario profile from the repository root:

```text
./gradlew :server_jvm:bootRun \
  --args="--spring.profiles.active=scenario-workers"
```

The profile uses repository-relative Lab directories. Existing Worker files
are persistent local state; Scenario shutdown closes network resources but does
not delete Workers, WorkerGroups or registered Task Call Tasks.

For a repository-independent Server deployment, build or download the
[`distribution/server`](../distribution/server/) Runtime ZIP. After extraction,
Java 21, Python 3.11.3 through 3.13 and external Redis are the only machine
prerequisites:

```text
python bin/run-server.py -- --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

The launcher owns `scenario-workers`, its private offline venv, the absolute
Pacer policy path and compiled frontend path. It does not start Redis or own
the Pacer directly; the packaged Java Server still supervises that child.
Source `bootRun` remains unchanged for repository development.

Health endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness covers the JVM process. Readiness requires both the configured Python
Pacer child to be alive and Kernel Redis to be reachable. The `kernel` health
contributor exposes only mode, lifecycle state and safe PID. Task business APIs
do not call the Python process.

## Verification

```text
./gradlew :server_jvm:test

./gradlew :server_jvm:redisOwnerIntegrationTest

./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```

The two integration tasks use the checked `integration-test` profile with
Redis at `redis://127.0.0.1:6379/15`. Runtime Boundary starts one Java Spring
context, which supervises the Python CLI using the checked Pacer config. Redis,
Python and the `redis` package must be available; no second host is started by
the test operator.

The finite lifecycle configuration is `xa.mass.kernel-pacer`: `enabled`,
`python-executable`, `working-directory`, `config-path`, `state-directory`,
`startup-timeout`, and `shutdown-timeout`. The Python module and arguments are
fixed by the assembly and cannot be configured as a shell command. Normal JVM
tests use the `test` profile with this lifecycle disabled.

`xa.mass.redis` is the single production source for the Redis URL and scope.
Java installs those exact values into the fixed child environment; managed
Pacer policy JSON must not contain a `redis` object. This keeps Java commands
and Python Pacers in one Redis universe without making Server parse Pacer
policy. The Runtime Boundary proof generates a unique `test_*` scope and
injects it into both sides so the handoff is exercised rather than hidden by
matching defaults. The default profile uses `profile_default`; the checked
Scenario profile defaults to `profile_scenario_workers`.

Exactly one Server instance per Redis scope may have the Pacer lifecycle
enabled. Other API replicas must set `xa.mass.kernel-pacer.enabled=false`.
There is no distributed leader election in this temporary host. Sharing a
state directory is not a substitute: when its owner record still identifies a
live process with the same start instant, the second Server fails startup and
leaves that process untouched.

The Pacer child reaches `RUNNING` only after its exact instance token appears
in the ready file. Worker/Adapter assembly starts after that transition and is
closed before the Pacer child. Closing Java first closes child stdin; a child
that does not stop within the bounded timeout is forcibly terminated. Child
stdout and stderr inherit the Java process streams.

Historical-state handling is deliberately non-destructive. A missing/dead PID
or PID-reuse mismatch removes stale owner and ready files. A matching live PID
causes startup to fail; Java never kills a process recovered only from disk
state. If the operating system cannot expose its start identity, startup also
fails for explicit operator recovery. Forced termination is reserved for the
exact child `Process` started and retained by the current Java lifecycle.

The Runtime Boundary proof closes real polling, WebSocket and Socket Task
paths. It also calls an unpaused real WebSocket Worker directly, executes a
custom `extension.worker.*` event through a SYSTEM Command and the default
probe/properties/events handlers, observes Adapter connection state, closes
the current Channel, and proves transparent reconnect.

The canonical proof ownership, prerequisites and CI lane selection are in
[`TESTING.md`](../TESTING.md).
