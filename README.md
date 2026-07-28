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
  and Java Redis providers without redefining Kernel runtime contracts.
- [`worker_delivery_contract_jvm/`](worker_delivery_contract_jvm/): shared
  Java 21 Worker Delivery DTO, validation, outcome classification, and codec.
- [`worker_delivery_adapter_jvm/`](worker_delivery_adapter_jvm/):
  Worker Delivery Adapter Runtime. It owns complete Adapter instances,
  independent Netty WebSocket listeners, start/close lifecycle, bounded
  mailbox dispatch, active connections, result buffering, and the Server
  batch HTTP client without depending on Spring, Server, Kernel, or Redis.
- [`worker_jvm/`](worker_jvm/): runnable one-slot Java Worker with polling and
  WebSocket transports over one command execution core.

The shared contract jar is repository-local and targets Java 21. It is not a
published Worker SDK or an Android compatibility promise.

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
