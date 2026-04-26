# XA Mass Platform

This repository is in a convergence phase. Trust code and verified runtime behavior over historical documentation.

## Read This First

Current high-trust entry points:

- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/HIGH_VOLUME_MODEL_BASELINE.md](./doc/HIGH_VOLUME_MODEL_BASELINE.md)
- [doc/TESTING_BASELINE.md](./doc/TESTING_BASELINE.md)
- [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- [doc/engine/TASK_EXECUTION_FLOW.md](./doc/engine/TASK_EXECUTION_FLOW.md)

Role split:

- `AGENTS.md`: fastest handoff for coding agents and maintainers
- `doc/AGENT_BASELINE.md`: code reality, module truth, and architectural guardrails
- `doc/HIGH_VOLUME_MODEL_BASELINE.md`: target production model for compressing the object-heavy validation path into a queue-driven high-volume mainline
- `doc/TESTING_BASELINE.md`: test-system taxonomy, CI placement strategy, and agent-first acceptance map
- `doc/VERIFIED_RUNBOOK.md`: startup, verification, runtime path, and regression commands
- `doc/INTERNAL_API_REFERENCE.md`: endpoint inventory, current contracts, and implementation status
- `doc/engine/TASK_EXECUTION_FLOW.md`: task execution flow notes aligned to the current mainline

## Platform Positioning

XA Mass Platform is a general distributed task scheduling platform.

It exists to solve one recurring problem cleanly: dynamically match a batch of structured work items (`TaskMsg`) to a batch of heterogeneous, stateful workers, track each execution result, and converge task-level completion state.

This pattern shows up repeatedly, but existing systems usually optimize only one side of it:

- IM bot platforms need tasks routed to the right bot instance or group-facing executor.
- crawler platforms need work routed by region, account, or other routing constraints.
- LLM agent runtimes need event/capability-based dispatch to different agents.
- phone/RPA systems need online-device matching plus per-item result tracking.

XA Mass is aimed at the shared kernel behind those cases: `stateful worker + capability/routing match + per-item result tracking + task-level convergence`.

- Its core abstraction is simple: assign a batch of work items to a batch of online workers, track each execution result, and converge task-level completion state.
- The platform is scenario-agnostic. It does not define the business itself; it defines who is online, who can accept work, how work is dispatched, how results are collected, and how task state converges.
- The stable kernel is `Task`, `TaskMsg`, `TaskMsgAttempt`, assignment, result write-back, audit, and terminal policy.
- The project is library/SDK-first. HTTP pages, demo APIs, and mock runtime surfaces exist to validate the kernel.
- One verified realtime adapter path is still `Worker + WorkerContext + WebSocket adapter + mock clients`, but that is now treated as a reference transport adapter rather than the product boundary.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.

## Current Mainline Goal

The repository is no longer converging toward a "WebSocket worker system". The current mainline goal is:

- keep the platform core focused on scheduling, state-machine transitions, matching, retry, release, audit, and terminal policy
- keep transport concerns behind explicit seams so worker delivery is not defined by WebSocket
- support both push-style and pull-style worker runtimes
- let future adapters such as WebSocket, gRPC, custom socket, and queue/polling workers coexist without redefining kernel semantics
- keep `xa-mass-sdk` positioned like a library/runtime entry, with richer client implementations allowed to grow around it

The transport-neutral runtime model is now framed around three channels:

- task dispatch channel
- result ingest channel
- system-event channel for online/offline/heartbeat and related control-plane signals

## Current Reality

- Real Spring Boot entrypoint: `xa-mass-dev-app`
- Java baseline: JDK 21. The root Maven reactor and Java worker samples compile with `maven.compiler.release=21`, and CI uses Temurin 21.
- Java 21 is the supported runtime floor. Use virtual threads for runtime/transport/event execution boundaries only behind explicit runtime abstractions such as `RuntimeTaskExecutor`; do not make engine lifecycle correctness depend on asynchronous execution.
- Embedded transport adapter bootstraps receive the shared runtime executor; blocking transport work should use that executor instead of adapter-local thread pools.
- The dev-app enables Spring virtual threads so bounded external worker long-polling can use simple blocking code without consuming platform request threads.
- Do not treat the embedded runtime classes as a Spring Boot app
- Current root reactor modules are `xa-mass-web`, `xa-mass-core`, `xa-mass-transport-api`, `xa-mass-transport-polling`, `xa-mass-transport-runtime`, `xa-mass-engine`, `xa-mass-transport-websocket`, `xa-mass-sdk-api`, `xa-mass-sdk`, `xa-mass-testing`, and `xa-mass-dev-app`
- `xa-mass-sdk` is the real Java embedding module; it now carries both the SDK facade and the embedded runtime composition
- `xa-mass-transport-api` is the transport-neutral seam for task dispatch, result ingest, system events, transport servers, and worker endpoint registries; module sources live under `transport/transport_api`
- `xa-mass-sdk` now assembles concrete worker transports through a transport runtime registry/factory seam instead of treating WebSocket as the runtime definition
- `xa-mass-sdk` is also the SDK-first resource entry for project/event metadata, task creation, and worker registration; runtime project validation now flows through a core project registry seeded by defaults and extended by SDK registration
- global SDK event codes are the mainline capability identity for dispatch, permissions, and worker capability declarations; project membership remains scope metadata rather than part of the identity key
- historical reactor/module experiments such as `xa-mass-base`, `xa-mass-starter`, and engine archive generations are no longer present in the current repository snapshot
- Verified HTTP port: `server.port=8088`
- Verified current WebSocket adapter port: `mass.websocket.port=18088`
- Pull-style workers are also part of the runtime surface through `MassSdkApplication.pullWorker(...)` and the external polling worker HTTP API under `/worker-api/*`
- Worker transport selection should prefer neutral hints such as `realtime` and `polling`; concrete adapter names remain compatibility aliases.
- New worker resources should enter through SDK registration (`registerWorker(...)` and `registerWorkerContext(...)`) or the external polling worker API that maps onto the same SDK/runtime path; dev-app mock JSON remains a local/E2E fixture path, not the product resource entry.
- Verified task lifecycle coverage includes:
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `NEW -> READY -> PAUSED -> READY`
  - `NEW -> BLOCKED -> READY`
- `TERMINAL` is not self-describing anymore; inspect `task.terminalReason`
- Active HTTP task creation has one route: `POST /status/api/tasks`. Console/operator callers use the normal control-plane auth boundary; SDK credential callers use the same route with `X-Mass-Api-Key` or `Authorization: Bearer ...`, where submitter `task:create`, project scope, event scope, and user scope are enforced before task creation. SDK credential introspection is read-only through `GET /sdk/submitters/me`. One user may own multiple API-key style credentials with different permissions/scopes. There is still no dedicated `routingCode` field in the public contract.
- `PUT /status/api/tasks/{taskId}` is metadata-only and does not accept `inputs`
- `Task.project` and `Task.user` are first-class business bindings on the task aggregate; do not hide them inside `sharedConfig` or attribute maps
- `Task.sharedConfig` is the task-level shared payload; `TaskMsg.input` and `TaskMsg.output` are the per-item payload boundary

## Quick Start

Run from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-web/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Primary endpoints:

- `http://localhost:8088/`
- `http://localhost:8088/tasks`
- `http://localhost:8088/resources/workers`
- `http://localhost:8088/doc.html`
- `http://localhost:8088/actuator/health`
- `ws://localhost:18088/ws` for the current WebSocket adapter path

## Module Map

- `xa-mass-dev-app`: verified runnable entry and full-stack validation shell; starts runtime through `xa-mass-sdk` and exposes the current HTTP control console and JSON APIs through `xa-mass-web`
- `xa-mass-sdk`: consumer-facing dependency entry and embedded runtime composition for the platform
- `xa-mass-sdk-api`: stable SDK-facing catalog/auth/model contract shared by `xa-mass-sdk` and `xa-mass-web`
- `xa-mass-transport-api`: transport-neutral runtime SPI for task dispatch, result ingest, system events, transport servers, and worker endpoint registries; sources live under `transport/transport_api`
- `xa-mass-transport-polling`: pull/polling worker adapter module used by the default SDK composition
- `xa-mass-transport-runtime`: shared transport runtime assembly used by the SDK composition; sources live under `transport/transport_runtime`, and its main Java package is `com.xa.mass.transport.runtime.*`
- `xa-mass-web`: REST controllers and the backend-hosted control console shell
- `xa-mass-engine`: task state machine, assignment, result handling, and strategy extension points
- `xa-mass-transport-websocket`: current WebSocket task transport adapter plus dispatch runtime; module sources live under `transport/websocket-adapter`, and its Java package identity is `com.xa.mass.transport.websocket.*`
- `xa-mass-testing`: cross-cutting acceptance tooling and load/concurrency/chaos harness home
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
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;

MassSdkApplication app = MassSdk.builder()
        .transport(transport -> transport
                .webSocketAdapter(webSocket -> webSocket
                        .server(19090, "/ws")
                        .enabled(false)
                        .serverEnabled(false)))
        .engine(engine -> engine.enabled(true))
        .build();

app.start();
app.registerWorker(WorkerRegistration.builder()
        .workerId("crawler-worker-1")
        .workerGroupId("crawler")
        .eventBindings(java.util.List.of(
                com.xa.mass.sdk.model.WorkerEventBinding.builder()
                        .eventCode("crawler.fetch-page")
                        .projectCodes(java.util.List.of("demoApp"))
                        .build()
        ))
        .transportHint("polling")
        .attributes(java.util.Map.of("type", "crawler"))
        .build());
app.registerWorkerContext(WorkerContextRegistration.builder()
        .workerContextId("ctx-crawler-worker-1")
        .workerId("crawler-worker-1")
        .routingTags(java.util.Set.of("us"))
        .attributes(java.util.Map.of("region", "us"))
        .build());
app.createTask(MassTaskCreateRequest.builder()
        .userId("agent")
        .project("demoApp")
        .taskName("demo-task")
        .sharedConfig(java.util.Map.of("textContent", "hello"))
        .inputs(java.util.List.of(java.util.Map.of("target", "target-a")))
        .batchSize(1)
        .build());
```

`supportedProjects` is only a coarse worker grouping/filter hint. Runtime event capability truth should be declared explicitly through `eventBindings` and the derived `supportedEventCodes`. SDK metadata exposes this as `GET /sdk/meta/event-capabilities`: task-backed events report live worker coverage, while direct runtime events report the SDK runtime handler path.

Third-party worker note:

- External worker validation mainline now includes polling, websocket, and socket samples under `samples/`
- polling workers use the external HTTP worker API under `/worker-api`
- `/worker-api` requires an SDK credential whose permissions include `worker:poll` and whose attributes bind `workerId`
- realtime workers still register capability through the control plane, but become online only after the concrete adapter connection is established
- external workers register capability through `eventBindings`, not adapter-internal frame types
- external workers route locally by `eventCode` and keep `adapterId + transportHint` explicit in the registration model
- polling workers receive `TaskDispatchItem` and submit `TaskResultReport`; realtime workers receive canonical task-dispatch frames and submit canonical task-result frames
- `TaskDispatchItem.input` is the logical per-item payload; transport does not expose SDK-internal wrapper shapes such as `{type,data}`
- runnable third-party worker samples and validation runbooks live in [`samples/`](./samples/) and [`doc/EXTERNAL_WORKER_QUICKSTART.md`](./doc/EXTERNAL_WORKER_QUICKSTART.md)

Module boundary note:

- top-level directories are not automatically active modules
- check the root `pom.xml` before treating a directory as current mainline
- `xa-mass-dev-app` uses `xa-mass-sdk` as its runtime entry and keeps `xa-mass-web` explicit because the current mock app also serves REST APIs and the backend-hosted control console shell
- `xa-mass-web` currently depends on both `xa-mass-sdk` and `xa-mass-sdk-api`: SDK runtime/auth/task operations come from `xa-mass-sdk`, while shared SDK-facing metadata and request shapes also live in `xa-mass-sdk-api`
- avoid making `xa-mass-sdk` depend on API/UI modules; that would make third-party SDK consumers pull demo web surfaces unnecessarily
- embedded runtime composition now lives inside `xa-mass-sdk`, with SDK-facing builder/facade types under `com.xa.mass.starter.*`, SDK-owned transport composition under `com.xa.mass.sdk.transport.*`, and shared transport runtime assembly under `com.xa.mass.transport.runtime.*`
- if an older doc references removed modules or archive code, treat that as historical drift rather than something missing from the current repo

## Documentation Layout

- Keep active operational docs under `doc/`
- Historical archive docs have been removed from the current repository snapshot during convergence
- If a document disagrees with code or runtime, prefer code and verified runtime
