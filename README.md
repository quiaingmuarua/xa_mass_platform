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
  Worker resource upsert, Task data, and Worker Delivery use Java Redis
  providers without redefining Kernel runtime contracts. Its optional Worker
  Assembly composes explicitly configured Scenario Workers through the same
  public Worker transport path.
- [`scenario_workers_jvm/`](scenario_workers_jvm/): Java 21 finite Scenario
  Worker capability assembly. It owns the checked-in phone-number and
  string-utility event definitions, WorkerGroup/Worker declarations, and real
  WebSocket Worker lifecycle without owning Server profiles or Adapters.
- [`worker_delivery_contract_jvm/`](worker_delivery_contract_jvm/): shared
  Java 11 compatible Worker Delivery DTO, validation, outcome classification,
  strict codec, and JDK-value JSON facade shared with Android.
- [`transport/`](transport/): concrete Worker Delivery implementations. It
  contains the Java 11 Worker Core execution/state-machine mechanism, the
  Netty Adapter runtime, concrete JVM network clients, and the Android
  HandlerThread WebSocket client. JVM and Android hosts explicitly compose
  Worker Core with a concrete client without changing the distinct delivery,
  execution, and network boundaries.
- [`integrations/`](integrations/): externally assembled, runnable proof
  applications. The
  [`worker-capability-rpc`](integrations/worker-capability-rpc/) module owns
  only Task creation and single-Item RPC invocation. The `scenario-workers`
  Server profile composes one real WebSocket Adapter with two reusable
  Scenario WorkerGroups and six capabilities.

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

See [`AGENTS.md`](AGENTS.md) before changing mechanism behavior and
[`doc/README.md`](doc/README.md) for the retained historical method assets.
