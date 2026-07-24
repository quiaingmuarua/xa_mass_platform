# XA Mass Kernel

Status: clean-kernel mechanism workspace, JVM rewrite scaffold, and Runtime API.

The repository contains three active areas:

- [`kernel_design/`](kernel_design/): Python executable specification,
  mechanism documentation, Redis proofs, and protocol examples. It is the
  current semantic oracle.
- [`kernel_jvm/`](kernel_jvm/): empty Kotlin/JVM production implementation
  scaffold. It does not yet implement kernel behavior.
- [`server_jvm/`](server_jvm/): Java/Spring Boot Runtime API Server. It exposes
  the stable `/api/v1` command surface and currently calls the Python Kernel
  Command Server over HTTP.

Gradle is the JVM build boundary so a future Worker SDK can be added as an
Android-compatible library module without making the kernel runtime an Android
module. The Worker SDK does not exist in this scaffold yet.

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
