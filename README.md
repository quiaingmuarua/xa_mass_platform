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

- Real Spring Boot entrypoint: `xa-mass-dev-app`
- Do not treat the embedded runtime classes as a Spring Boot app
- Current root reactor modules are `xa-mass-web`, `xa-mass-core`, `xa-mass-transport-api`, `xa-mass-engine`, `xa-mass-gateway`, `xa-mass-sdk-api`, `xa-mass-sdk`, and `xa-mass-dev-app`
- `xa-mass-sdk` is the real Java embedding module; it now carries both the SDK facade and the embedded runtime composition
- historical reactor/module experiments such as `xa-mass-base`, `xa-mass-starter`, and engine archive generations are no longer present in the current repository snapshot
- Verified HTTP port: `server.port=8088`
- Verified WebSocket gateway port: `mass.websocket.port=18088`
- Verified task lifecycle coverage includes:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `NEW -> READY -> PAUSED -> READY`
  - `NEW -> BLOCKED -> READY`
- `TERMINAL` is not self-describing anymore; inspect `task.terminalReason`
- Active task-create contract now uses `userId`, `project`, `sharedConfig`, `inputs`, `routingCode`, `batchSize`, `defaultMsgMaxRetryCount`, and `openEnded`
- `PUT /status/api/tasks/{taskId}` is metadata-only and does not accept `inputs`
- `Task.project` and `Task.user` are first-class business bindings on the task aggregate; do not hide them inside `sharedConfig` or attribute maps
- `Task.sharedConfig` is the task-level shared payload; `TaskMsg.input` and `TaskMsg.output` are the per-item payload boundary

## Quick Start

Run from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-web/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-transport-api/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Primary endpoints:

- `http://localhost:8088/`
- `http://localhost:8088/tasks`
- `http://localhost:8088/resources/workers`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088/ws`

## Module Map

- `xa-mass-dev-app`: verified runnable entry and full-stack validation shell; starts runtime through `xa-mass-sdk` and exposes the current HTTP control console and JSON APIs through `xa-mass-web`
- `xa-mass-sdk`: consumer-facing dependency entry and embedded runtime composition for the platform
- `xa-mass-sdk-api`: stable SDK-facing catalog/auth/model contract shared by `xa-mass-sdk` and `xa-mass-web`
- `xa-mass-transport-api`: transport-neutral runtime SPI for transport servers and worker endpoint registries
- `xa-mass-web`: REST controllers and the backend-hosted control console shell
- `xa-mass-engine`: task state machine, assignment, result handling, and strategy extension points
- `xa-mass-gateway`: current WebSocket transport adapter plus dispatch runtime
- `xa-mass-core`: shared models and infrastructure

Build boundary note:

- the root `pom.xml` is now parent/reactor and dependency-management only
- do not rely on parent-level implicit compile dependencies; each module should declare its own runtime and test dependencies explicitly

## SDK Entry

For third-party embedding, depend on `xa-mass-sdk`.

```xml
<dependency>
  <groupId>com.xa.mass</groupId>
  <artifactId>xa-mass-sdk</artifactId>
  <version>${xa.mass.version}</version>
</dependency>
```

Minimal Java usage:

```java
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskCreateRequest;

MassSdkApplication app = MassSdk.builder()
        .server(19090, "/ws")
        .gateway(gateway -> gateway.enabled(false))
        .engine(engine -> engine.enabled(true))
        .build();

app.start();
app.createTask(MassTaskCreateRequest.builder()
        .userId("agent")
        .project("demoApp")
        .taskName("demo-task")
        .sharedConfig(java.util.Map.of("textContent", "hello"))
        .inputs(java.util.List.of(java.util.Map.of("target", "target-a")))
        .routingCode("us")
        .batchSize(1)
        .build());
```

Module boundary note:

- top-level directories are not automatically active modules
- check the root `pom.xml` before treating a directory as current mainline
- `xa-mass-dev-app` uses `xa-mass-sdk` as its runtime entry and keeps `xa-mass-web` explicit because the current mock app also serves REST APIs and the backend-hosted control console shell
- `xa-mass-web` depends on `xa-mass-sdk-api` for SDK-facing metadata and request shapes, not on the embedded runtime module
- avoid making `xa-mass-sdk` depend on API/UI modules; that would make third-party SDK consumers pull demo web surfaces unnecessarily
- embedded runtime composition now lives inside `xa-mass-sdk` under `com.xa.mass.starter.*`
- if an older doc references removed modules or archive code, treat that as historical drift rather than something missing from the current repo

## Documentation Layout

- Keep active operational docs under `doc/`
- Historical archive docs have been removed from the current repository snapshot during convergence
- If a document disagrees with code or runtime, prefer code and verified runtime
