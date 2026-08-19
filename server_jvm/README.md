# XA Mass JVM Runtime API Server

Status: current external Runtime API, incremental Kernel provider assembly and
opt-in Scenario host.

`server_jvm` owns:

- the versioned `/api/v1` HTTP boundary, validation and error mapping;
- provider assembly over `kernel_jvm` owner contracts;
- process health and public OpenAPI/Scalar surfaces;
- Worker Identity and persistent Endpoint Binding;
- bounded application use cases such as Task Batch, WorkerGroup RPC and
  DIRECT_CALL correlation;
- configured Adapter and Scenario startup order.

It does not own Kernel candidate selection, Worker lease, TaskItem claim,
retry, recovery, Task finality, Adapter connection routing or Worker event
execution. See the root [architecture entrypoint](../README.md).

## Runtime Shape

```text
Public API
  -> Controller and Server use-case service
  -> kernel_jvm owner contract
  -> selected Python HTTP or Java Redis provider

Worker Identity / Binding
  -> Server-owned Redis boundary

Worker Delivery
  -> point or Adapter batch API
  -> DeliveryCommand / DeliveryReport owner runtime

Configured deployment
  -> initialize advisory WorkerGroups and Profile Tasks
  -> start Adapter Manager
  -> start ScenarioWorkers aggregate
```

Task control and Kernel application lifecycle still use the Python Runtime
Server. Selected resource, Task data, Worker scheduling and delivery owners use
Java Redis providers. Missing JVM operations fail explicitly; there is no
Server fallback scheduler.

Provider ownership is deliberately mixed but explicit:

| Boundary | Current provider/owner |
| --- | --- |
| Task create, approve, close and dispatch wake | Python Kernel HTTP |
| Worker resources, selected Task data and Worker scheduling operations | JVM owner contracts with Java Redis providers |
| DeliveryCommand consume and DeliveryReport append | Java Redis delivery providers |
| Worker Serviceability bridge | Lowest-priority Adapter snapshot construction plus transparent Java Redis Adapter-evidence append |
| Worker Identity and Endpoint Binding | Server-owned Redis boundaries |
| WorkerGroup RPC and Task Batch | Server-bounded use cases over existing owners |
| Worker Direct Command slot | `WorkerCommandRuntime` shared Redis Hash |
| Adapter Direct FIFO, waiter and correlation | Server instance memory |
| Other scheduling internals | Explicit JVM gaps |

The bounded use cases do not create new truth: WorkerGroup RPC appends one Item
and observes last success through one shared probe; Task Batch appends a finite
input once and publishes complete or partial JSONL; pause/resume calls the
Worker score owner; DIRECT_CALL correlates caller-selected targets without
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

## Assembly Boundaries

### Kernel Providers

Controllers and use-case services depend only on `kernel_jvm` owner contracts.
Provider selection stays in Server assembly. The shared `kernelredis` package
owns connection and health only; Redis key operations live in owner-local
provider packages.

Worker Register establishes Server-owned identity. Every explicit Worker Bind
validates that identity, persists its Endpoint route, and upserts the complete
Worker Properties Map through the Kernel owner. Bind is the sole canonical
Properties refresh; a transparent Client reconnect performs neither operation.

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

1. declare advisory WorkerGroup catalogs;
2. create or reuse the deterministic Profile Task for each configured Group;
3. construct and start configured Adapters;
4. pass opaque capability assembly JSON, the Lab root and Runtime API base URL
   to `ScenarioWorkers`.

Server does not parse Worker files, construct business Definitions or own
individual Worker lifecycle. Those responsibilities belong to
[`scenario_workers_jvm`](../scenario_workers_jvm/README.md).

The checked `scenario-workers` profile provides one WebSocket Adapter, two JVM
Scenario WorkerGroups and the advisory external Android demo group. Its local
Task Batch proof is owned by
[`integrations/worker-capability-rpc`](../integrations/worker-capability-rpc/README.md).

## Configuration

Default coordinates:

```text
Java Runtime API Server        http://127.0.0.1:18082
Python Kernel Runtime Server   http://127.0.0.1:18080
Kernel Redis                   redis://localhost:6379/15
Redis prefix                   default
Adapter instances              none
WorkerGroup RPC wait           30s default / 60s maximum
DIRECT_CALL wait               3s default / 10s maximum
Adapter Direct FIFO capacity   1000 per Adapter
Pending Direct targets         10000 per Server
Serviceability probe requests  10000 per Adapter HASH
Serviceability evidence       10000 per Redis prefix
```

The optional Serviceability handoff uses
`ws:{redisPrefix}:adapter:{adapterId}:probe-requests` and
`ws:{redisPrefix}:adapter-evidence-results`. These are Kernel-owned best-effort
handoffs, not current connectivity truth. Server implements only the bounded
Adapter request consume and Adapter-evidence append needed by its HTTP bridge.

The default Adapter section defines only remote API connection defaults. An
Adapter instance is an explicit deployment declaration and must also have a
matching Endpoint Binding entry.

## Run

Start the Python Kernel Runtime Server:

```text
python -m kernel_design.runtime_server
```

Then start the Java Runtime API:

```text
./gradlew :server_jvm:bootRun
```

Start the checked local Scenario profile from the repository root:

```text
./gradlew :server_jvm:bootRun \
  --args="--spring.profiles.active=scenario-workers"
```

The profile uses repository-relative Lab directories. Existing Worker files
are persistent local state; Scenario shutdown closes network resources but does
not delete Workers, WorkerGroups or Profile Tasks.

Health endpoints:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Liveness covers the JVM process. Readiness requires both the configured Python
Kernel control API and Kernel Redis connection.

## Verification

```text
./gradlew :server_jvm:test

./gradlew :server_jvm:redisOwnerIntegrationTest

./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```

The two integration tasks use the checked `integration-test` profile with
Redis at `redis://127.0.0.1:6379/15` and the Python Kernel at
`http://127.0.0.1:18080`. The profile supplies addresses only; the external
services must already be running and connection failures fail the proof.

The Runtime Boundary proof closes real polling, WebSocket and Socket Task
paths. It also calls an unpaused real WebSocket Worker directly, executes a
custom `extension.worker.*` event through a SYSTEM Command and the default
probe/properties/events handlers, observes Adapter connection state, closes
the current Channel, and proves transparent reconnect.

The canonical proof ownership, prerequisites and CI lane selection are in
[`TESTING.md`](../TESTING.md).
