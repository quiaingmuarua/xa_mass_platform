# XA Mass Platform

This repository is in a convergence phase. Trust code and verified runtime behavior over historical documentation.

## Read This First

Current high-trust entry points:

- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- [doc/engine/TASK_EXECUTION_FLOW.md](./doc/engine/TASK_EXECUTION_FLOW.md)

Role split:

- `AGENTS.md`: fastest handoff for coding agents and maintainers
- `doc/AGENT_BASELINE.md`: code reality, module truth, known gaps
- `doc/VERIFIED_RUNBOOK.md`: startup, verification, runtime path, regression commands
- `doc/INTERNAL_API_REFERENCE.md`: endpoint inventory, implementation status, response/transition rules
- `doc/engine/TASK_EXECUTION_FLOW.md`: task execution flow notes aligned to the current mainline

## Current Reality

- Primary product direction is library/SDK-first. The HTTP backend and status pages are validation/demo surfaces, not the architectural center.
- Real Spring Boot entrypoint: `xa-mass-mock`
- Do not start from `xa-mass-runtime`
- Current root reactor modules are `xa-mass-api`, `xa-mass-core`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-runtime`, and `xa-mass-mock`
- `xa-mass-base` and `xa-mass-starter` directories remain in the repository but are not part of the current root reactor
- Verified HTTP port: `server.port=8088`
- Verified WebSocket gateway port: `mass.websocket.port=18088`
- Verified task lifecycle coverage includes:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `NEW -> READY -> PAUSED -> READY`
  - `NEW -> BLOCKED -> READY`
- `TERMINAL` is not self-describing anymore; inspect `task.terminalReason` to distinguish manual cancel from message-driven completion
- Unsupported task `project` codes now fail fast instead of silently falling back to `demoApp`

## Quick Start

Run from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-mock/target/classes:xa-mass-runtime/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Primary endpoints:

- `http://localhost:8088/status`
- `http://localhost:8088/status/tasks`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088/ws`

## Module Map

- `xa-mass-mock`: verified runnable entry, full-stack validation
- `xa-mass-runtime`: lifecycle/composition layer, not the Boot entry
- `xa-mass-api`: REST controllers and status pages for demo/validation
- `xa-mass-engine`: core lifecycle, assignment, rules, and strategy extension points
- `xa-mass-gateway`: WebSocket server and dispatch
- `xa-mass-core`: shared models and infrastructure

Module boundary note:

- top-level directories are not automatically active modules
- check the root `pom.xml` before treating a directory as current mainline
- `xa-mass-base` and `xa-mass-starter` are currently reference/legacy directories, not root-reactor modules

## Documentation Layout

- Keep active operational docs under `doc/`
- Historical, duplicated, or low-trust docs have been moved to [doc/archive/README.md](./doc/archive/README.md)
- If a document disagrees with code or runtime, prefer code and verified runtime
