# XA Mass Kernel Agent Handoff

Status: current repository handoff.

## Mainline

- `kernel_design/` is the current mechanism oracle.
- `kernel_jvm/` is a production implementation scaffold only.
- `server_jvm/` is the external Runtime Command API process. It owns HTTP
  contracts and process concerns, not scheduling or Redis truth.
- The legacy Java platform is available exclusively from
  `legacy-java-platform-final-2026-07-24`.
- There is no compatibility obligation to legacy Java APIs, modules, Redis
  shapes, SDKs, server routes, transport contracts, or frontend models.

## Trust Order

1. `kernel_design/executable_spec/` code and tests.
2. Verified Redis behavior.
3. Current `kernel_design/doc/` mechanism contracts.
4. `kernel_design/README.md` and `kernel_design/AGENTS.md`.
5. Historical tag material only as failure-mode evidence.

If executable code and a current mechanism document disagree, identify the
drift before changing either one. Do not infer new behavior from the legacy
tag.

## Working Rules

- Scope mechanism searches, diffs, and Python tests to `kernel_design/`.
- Preserve explicit owner boundaries across core contracts, scheduling,
  Redis implementations, assembly, and external protocol examples.
- Do not add bridges, compatibility aliases, mirrored DTOs, or speculative
  modules.
- Keep score values opaque outside their owner operations.
- Use real Redis proof for Redis behavior and concurrency claims.
- Update the owning mechanism document when behavior changes.
- Do not implement Kotlin behavior until a scoped parity slice names the
  Python contract and proof it replaces.
- Keep `server_jvm` on the Python `KernelCommandClient` boundary until a
  complete Kotlin owner slice is ready to replace that client. It must not
  access Redis, scores, Pacers, or `kernel_jvm` implementation packages.

## JVM Scaffold

`kernel_jvm` is intentionally one Gradle module. Packages may separate owner
responsibilities, but a new Gradle module requires a real publication,
dependency, or lifecycle boundary.

The scaffold currently contains no public API, DTO, runtime implementation,
Redis code, or Pacer. The server is a separate process/module and is not
evidence of Kotlin kernel implementation progress.

Gradle is intentional because a future Worker SDK must be consumable as an
Android library module. Keep that SDK separate from `kernel_jvm`; it may depend
only on a narrow Android-compatible Worker Delivery contract module, not on
`kernel_jvm`, Redis, scheduling, assembly, or server implementations. Do not
create that contract module before its public DTO and codec surface is defined.

## Verification

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
./gradlew build
git diff --check
```

For real Redis proof, set
`KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15` before running the Python
suite.
