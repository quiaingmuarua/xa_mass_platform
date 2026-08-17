# XA Mass JVM Runtime API Server

Status: current external Runtime API, incremental Kernel provider assembly and
opt-in Scenario host.

`server_jvm` owns:

- the versioned `/api/v1` HTTP boundary, validation and error mapping;
- provider assembly over `kernel_jvm` owner contracts;
- process health and public OpenAPI/Scalar surfaces;
- Worker Identity and persistent Endpoint Binding;
- bounded application use cases such as Task Batch, WorkerGroup RPC and
  CONTROL_ONLY correlation;
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
| Worker Identity and Endpoint Binding | Server-owned Redis boundaries |
| WorkerGroup RPC and Task Batch | Server-bounded use cases over existing owners |
| CONTROL_ONLY mailbox, waiter and correlation | Server instance memory |
| Other scheduling internals | Explicit JVM gaps |

The bounded use cases do not create new truth: WorkerGroup RPC appends one Item
and observes last success through one shared probe; Task Batch appends a finite
input once and publishes complete or partial JSONL; pause/resume calls the
Worker score owner; CONTROL_ONLY correlates caller-selected targets without
persisting Commands or Results. Its single public call is scoped to one
configured Adapter. A top-level `opaquePayload` targets that Adapter; supplying
one WorkerGroup plus a `1..100` entry `workerId -> opaquePayload` map targets
only Workers currently bound to that Adapter. The two request modes are
exclusive, and Server never partitions one Control Call across Adapters.
`messageType` and each opaque payload pass through unchanged. Server does not
enumerate event support or convert an unknown event into an HTTP admission
error; the Adapter (`23005`) or Worker (`3302`) returns an observed execution
result. Future API Session authorization may restrict caller/target/event
access before this use case, but it is not a CONTROL_ONLY event whitelist.
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

### Worker Delivery

Server owns the Worker Delivery HTTP and owner-provider composition. It
constructs active Adapters only through the finite public Netty factory.

Each Adapter reaches Server through the same HTTP boundary used by external
Workers. There is no in-process or Redis shortcut. Adapter lifecycle,
scheduler, queues, current route registry and physical Channels remain owned by
`transport/netty-adapter`.

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
CONTROL_ONLY wait              3s default / 10s maximum
```

The default Adapter section defines only remote API connection defaults. An
Adapter instance is an explicit deployment declaration and must also have a
matching Endpoint Binding entry.

Property-index providers are opt-in through
`XA_MASS_WORKER_PROPERTY_INDEX_REGISTRY_JSON`. The default registry is empty;
unknown implementations or malformed declarations fail startup.

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

KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:redisOwnerIntegrationTest

KERNEL_COMMAND_INTEGRATION_URL=http://127.0.0.1:18080 \
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  ./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```

The Runtime Boundary proof closes real polling, WebSocket and Socket Task
paths. It also pauses a real WebSocket Worker, executes a custom
`extension.worker.*` event through a SYSTEM Command and the default
probe/properties/events handlers, observes Adapter connection state,
closes the current Channel, proves transparent reconnect, then resumes
scheduling.

The canonical proof ownership, prerequisites and CI lane selection are in
[`TESTING.md`](../TESTING.md).
