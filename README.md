# XA Mass Platform

This repository is in a convergence phase. Trust code and verified runtime behavior over historical documentation.

## Read This First

Current high-trust entry points:

- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- [doc/内部管理接口文档.md](./doc/内部管理接口文档.md)
- [doc/engine/任务执行流.md](./doc/engine/任务执行流.md)

Role split:

- `AGENTS.md`: fastest handoff for coding agents and maintainers
- `doc/AGENT_BASELINE.md`: code reality, module truth, known gaps
- `doc/VERIFIED_RUNBOOK.md`: startup, verification, runtime path, regression commands
- `doc/内部管理接口文档.md`: endpoint inventory, implementation status, response/transition rules
- `doc/engine/任务执行流.md`: task execution flow notes aligned to the current mainline

## Current Reality

- Real Spring Boot entrypoint: `xa-mass-mock`
- Do not start from `xa-mass-starter`
- Verified HTTP port: `server.port=8088`
- Verified WebSocket gateway port: `mass.websocket.port=18088`
- Verified task lifecycle coverage includes:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `NEW -> READY -> PAUSED -> READY`
  - `NEW -> BLOCKED -> READY`

## Quick Start

Run from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
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
- `xa-mass-starter`: lifecycle/composition layer, not the Boot entry
- `xa-mass-api`: REST controllers and status pages
- `xa-mass-engine`: task lifecycle, assignment, rules
- `xa-mass-gateway`: WebSocket server and dispatch
- `xa-mass-base`: shared models and infrastructure

## Documentation Layout

- Keep active operational docs under `doc/`
- Historical, duplicated, or low-trust docs have been moved to [doc/archive/README.md](./doc/archive/README.md)
- If a document disagrees with code or runtime, prefer code and verified runtime
