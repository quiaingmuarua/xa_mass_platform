# XA Mass Kernel

Status: clean-kernel mechanism workspace, incremental JVM owner parity, and
Runtime API.

The repository contains seven active areas:

- [`kernel_design/`](kernel_design/): Python executable specification,
  mechanism documentation, and Redis proofs. It is the
  current semantic oracle.
- [`kernel_jvm/`](kernel_jvm/): Java 21 mirror of the Kernel owner contracts
  plus selected owner-specific Redis providers. It intentionally has no
  scheduling or application lifecycle implementation.
- [`server_jvm/`](server_jvm/): Java/Spring Boot Runtime API Server. It exposes
  the stable `/api/v1` surface. Task control operations use Python HTTP;
  Worker resource/Properties/index operations, Task data, and Worker Delivery use Java Redis
  providers without redefining Kernel runtime contracts. It separately owns
  long-lived Worker identity registration, persistent Endpoint Binding, and
  Adapter route verification. Its
  optional Worker Assembly initializes advisory WorkerGroup catalog metadata,
  starts configured Adapters, and then composes Scenario Workers through the
  public Identity and Worker transport paths.
- [`scenario_workers_jvm/`](scenario_workers_jvm/): Java 21 finite Scenario
  Worker capability assembly. It owns the checked-in phone-number and
  string-utility event definitions, configured-Group Lab directory discovery,
  one persistent JSON per Worker, public-HTTP Register/Bind control, Adapter
  identity Report construction, and aggregate WebSocket Worker lifecycle
  without owning WorkerGroup catalog initialization, Server profiles, or
  Adapters.
- [`worker_delivery_contract_jvm/`](worker_delivery_contract_jvm/): shared
  Java 11 compatible Worker Delivery DTO, validation, outcome classification,
  strict codec, and JDK-value JSON facade shared with Android.
- [`transport/`](transport/): concrete Worker Delivery implementations. It
  contains the Java 11 Worker Core execution mechanism, the Netty Adapter
  runtime, concrete JVM network clients, and the Android HandlerThread
  WebSocket client. The Netty Adapter has three frozen layers: the Adapter
  aggregate plus typed Command/Report Process owners for lifecycle and local
  scheduling, one shared connection mechanism for identity/routes/results,
  and one complete protocol-specific physical Server. Each Process owns one
  private finite queue; queues never cross owner boundaries.
  That production cut is frozen; WebSocket and Socket share a test behavior
  contract while retaining independent physical ownership and owner-local
  bounded shutdown.
  Its long-lived Worker path likewise has three explicit owners:
  Client networking and reconnect, Transport identity/Command/Result protocol, and
  `WorkerRunController` `RUNNING/STOPPED` lifecycle. Each Host `start()` makes
  one non-blocking request for exactly one Preparation attempt; failed
  preparation or endpoint termination remains stopped until the Host starts
  it again. Java and Android Worker assemblies own their platform execution
  resources without exposing physical connection state.
- [`integrations/`](integrations/): externally assembled, runnable proof
  applications. The
  [`worker-capability-rpc`](integrations/worker-capability-rpc/) module owns
  the external client proof for Server's finite Scenario RPC Lab: it uploads
  text fixtures, creates and runs six Server-owned Scenarios, and downloads JSONL
  evidence. The Java-only [`scenario_rpc_jvm`](scenario_rpc_jvm/) module owns
  line parsing, batch append/result-load orchestration, incremental sinks,
  ordering, and result validation and is consumed only by Server. The `scenario-workers`
  Server profile composes one real WebSocket Adapter, two reusable JVM
  Scenario WorkerGroups with six capabilities, and the advisory Android demo
  WorkerGroup. The installable
  [`android-websocket-worker`](integrations/android-websocket-worker/) App
  separately proves public Register/Bind, the Android network Client, shared
  Worker Core, and a real device WebSocket Task result without embedding Task
  control in the App.

The shared contract and Transport modules are repository-local artifacts;
they are not published SDKs.

The superseded Java platform, frontend, SDK, server, transport, infrastructure,
and integration code are preserved by the annotated Git tag
`legacy-java-platform-final-2026-07-24`. They are historical evidence, not
compatibility targets.

## Verification

The current verification contract is organized by proof boundary rather than
coverage percentage. See [`TESTING.md`](TESTING.md) for lane ownership,
path selection, exact local commands, and the stable CI `Proof Gate`.

Python executable specification and real Redis oracle (set
`KERNEL_DESIGN_REDIS_URL` to enable the Redis-backed proofs):

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
```

Deterministic JVM contracts:

```text
./gradlew :server_jvm:test
./gradlew :scenario_rpc_jvm:test
./gradlew :integrations:worker-capability-rpc:test
```

Real Redis and cross-process proofs use separate entrypoints:

```text
./gradlew :server_jvm:redisOwnerIntegrationTest
./gradlew :server_jvm:runtimeBoundaryIntegrationTest
./gradlew :integrations:worker-capability-rpc:runRpcScenario
```

The Scenario command separately self-verifies 20 persistent, globally unique
Worker IDs and 60 successful WorkerGroup/event calls. RPC results are not
attributed to a particular Worker. See its owning README for process startup.

See
[`integrations/android-websocket-worker/README.md`](integrations/android-websocket-worker/README.md)
for the Android 13 device, `adb reverse`, shared `scenario-workers` profile,
and Gradle-launched WorkerGroup RPC proof.

See [`AGENTS.md`](AGENTS.md) before changing mechanism behavior and
[`doc/README.md`](doc/README.md) for the retained historical method assets.
