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
- `doc/AGENT_BASELINE.md`: code reality, module truth, and architectural guardrails
- `doc/VERIFIED_RUNBOOK.md`: startup, verification, runtime path, and regression commands
- `doc/INTERNAL_API_REFERENCE.md`: endpoint inventory, current contracts, and implementation status
- `doc/engine/TASK_EXECUTION_FLOW.md`: task execution flow notes aligned to the current mainline

## Platform Positioning

XA Mass Platform is a general distributed task scheduling platform.

- Its core abstraction is simple: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The platform is scenario-agnostic. It does not define the business itself; it defines who is online, who can accept work, how work is dispatched, how results are collected, and how task state converges.
- The stable kernel is `Task`, `TaskMsg`, assignment, result write-back, audit, and terminal policy.
- The project is library/SDK-first. HTTP pages, demo APIs, and mock runtime surfaces exist to validate the kernel.
- The current reference scenario is a long-connection worker scheduling path built from `Worker + WorkerContext + WebSocket gateway + mock clients`.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- `Worker` and `WorkerContext` are the current reference adapters, not the permanent product boundary.

## Current Reality

- Real Spring Boot entrypoint: `xa-mass-mock`
- Do not start from `xa-mass-runtime`
- Current root reactor modules are `xa-mass-api`, `xa-mass-core`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-runtime`, and `xa-mass-mock`
- historical reactor/module experiments such as `xa-mass-base`, `xa-mass-starter`, and engine archive generations are no longer present in the current repository snapshot
- Verified HTTP port: `server.port=8088`
- Verified WebSocket gateway port: `mass.websocket.port=18088`
- Verified task lifecycle coverage includes:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `NEW -> READY -> PAUSED -> READY`
  - `NEW -> BLOCKED -> READY`
- `TERMINAL` is not self-describing anymore; inspect `task.terminalReason`
- Active task-create contract now uses `sharedConfig`, `targetList`, `routingCode`, `batchSize`, `defaultMsgMaxRetryCount`, and `openEnded`
- `PUT /status/api/tasks/{taskId}` is metadata-only and no longer accepts `targetList`
- `Task.sharedConfig` is the task-level shared payload; `TaskMsg.input` and `TaskMsg.output` are the per-item payload boundary

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

- `xa-mass-mock`: verified runnable entry and full-stack validation shell
- `xa-mass-runtime`: lifecycle/composition layer, not the Boot entry
- `xa-mass-api`: REST controllers and status pages for validation/demo
- `xa-mass-engine`: task state machine, assignment, result handling, and strategy extension points
- `xa-mass-gateway`: WebSocket server and dispatch
- `xa-mass-core`: shared models and infrastructure

Module boundary note:

- top-level directories are not automatically active modules
- check the root `pom.xml` before treating a directory as current mainline
- if an older doc references removed modules or archive code, treat that as historical drift rather than something missing from the current repo

## Documentation Layout

- Keep active operational docs under `doc/`
- Historical archive docs have been removed from the current repository snapshot during convergence
- If a document disagrees with code or runtime, prefer code and verified runtime
