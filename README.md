# XA Mass Kernel

Status: clean-kernel mechanism workspace, JVM rewrite scaffold, and Runtime API.

The repository contains five active areas:

- [`kernel_design/`](kernel_design/): Python executable specification,
  mechanism documentation, and Redis proofs. It is the
  current semantic oracle.
- [`kernel_jvm/`](kernel_jvm/): empty Kotlin/JVM production implementation
  scaffold. It does not yet implement kernel behavior.
- [`server_jvm/`](server_jvm/): Java/Spring Boot Runtime API Server. It exposes
  the stable `/api/v1` command surface, calls the Python Kernel Command Server,
  and owns Java Worker Delivery access.
- [`worker_delivery_contract_jvm/`](worker_delivery_contract_jvm/): shared
  Java 21 Worker Delivery DTO, validation, outcome classification, and codec.
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
