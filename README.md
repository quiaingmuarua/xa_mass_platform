# XA Mass Kernel

Status: clean-kernel mechanism workspace, incremental JVM owner parity, and
Runtime API.

The repository contains six active areas:

- [`kernel_design/`](kernel_design/): Python executable specification,
  mechanism documentation, and Redis proofs. It is the
  current semantic oracle.
- [`kernel_jvm/`](kernel_jvm/): Java 21 mirror of the Kernel owner contracts
  plus selected owner-specific Redis providers. It intentionally has no
  scheduling or application lifecycle implementation.
- [`server_jvm/`](server_jvm/): Java/Spring Boot Runtime API Server. It exposes
  the stable `/api/v1` surface and assembles owner operations from Python HTTP
  and Java Redis providers without redefining Kernel runtime contracts. Its
  optional, configuration-driven Worker Assembly can host explicitly known
  built-in Worker bundles through the same public Worker transport path.
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
  [`phone-number-rpc`](integrations/phone-number-rpc/) module owns only Task
  creation and single-Item RPC invocation; the matching phone-number Worker
  bundle is explicitly enabled in Server configuration.

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
