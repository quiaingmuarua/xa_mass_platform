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
  string-utility event definitions, strict JSON deployment manifest,
  public-HTTP Register/Bind control, connection Bind frame construction, and aggregate
  WebSocket Worker lifecycle
  without owning WorkerGroup catalog initialization, Server profiles, or
  Adapters.
- [`worker_delivery_contract_jvm/`](worker_delivery_contract_jvm/): shared
  Java 11 compatible Worker Delivery DTO, validation, outcome classification,
  strict codec, and JDK-value JSON facade shared with Android.
- [`transport/`](transport/): concrete Worker Delivery implementations. It
  contains the Java 11 Worker Core execution mechanism, the Netty Adapter
  runtime, concrete JVM network clients, and the Android HandlerThread
  WebSocket client. Its long-lived Worker path has three explicit owners:
  Client networking and reconnect, Runtime Bind/Command/Result protocol, and
  `WorkerRunController` `RUNNING/STOPPED` lifecycle. Each Host `start()` makes
  one synchronous Preparation attempt on the calling thread; failed
  preparation or endpoint termination remains stopped until the Host starts
  it again. JVM and Android hosts choose their own scheduling threads and
  compose those layers without exposing physical connection state.
- [`integrations/`](integrations/): externally assembled, runnable proof
  applications. The
  [`worker-capability-rpc`](integrations/worker-capability-rpc/) module owns
  Worker identity resolution, Task creation, and single-Item RPC invocation
  through the public Runtime API, with independent JSONL evidence for each
  WorkerGroup. The `scenario-workers`
  Server profile composes one real WebSocket Adapter with two reusable
  Scenario WorkerGroups and six capabilities. The installable
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

Python executable specification:

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
```

Real Redis proof:

```text
KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15 \
  python -m unittest discover -s kernel_design/executable_spec/tests
```

JVM modules:

```text
./gradlew build
```

Cross-process proofs use a real Redis service and Python Kernel process. The
Server integration suite proves the Java/Python owner boundary; the external
Scenario proof additionally starts the `scenario-workers` profile and executes
60 targeted single-Item RPC calls through 20 real WebSocket Workers:

```text
./gradlew :server_jvm:integrationTest
./gradlew :integrations:worker-capability-rpc:runRpcScenario
```

See
[`integrations/worker-capability-rpc/README.md`](integrations/worker-capability-rpc/README.md)
for the required process startup sequence. CI performs both cross-process
proofs with isolated Redis services.

See
[`integrations/android-websocket-worker/README.md`](integrations/android-websocket-worker/README.md)
for the Android 13 device, `adb reverse`, dedicated Server profile, and
Gradle-launched RPC proof.

See [`AGENTS.md`](AGENTS.md) before changing mechanism behavior and
[`doc/README.md`](doc/README.md) for the retained historical method assets.
