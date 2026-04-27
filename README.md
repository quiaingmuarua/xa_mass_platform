# XA Mass Platform

Trust code and verified runtime behavior over historical documentation.

## Read This First

Current high-trust entry points:

- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/TESTING_BASELINE.md](./doc/TESTING_BASELINE.md)
- [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- [doc/engine/TASK_EXECUTION_FLOW.md](./doc/engine/TASK_EXECUTION_FLOW.md)

Design/reference docs:

- [doc/HIGH_VOLUME_MODEL_BASELINE.md](./doc/HIGH_VOLUME_MODEL_BASELINE.md)

Role split:

- `AGENTS.md`: fastest handoff for coding agents and maintainers
- `doc/AGENT_BASELINE.md`: code reality, module truth, and architectural guardrails
- `doc/TESTING_BASELINE.md`: test-system taxonomy, CI placement strategy, and agent-first acceptance map
- `doc/VERIFIED_RUNBOOK.md`: startup, verification, runtime path, and regression commands
- `doc/INTERNAL_API_REFERENCE.md`: endpoint inventory, current contracts, and implementation status
- `doc/engine/TASK_EXECUTION_FLOW.md`: task execution flow notes aligned to the current mainline
- `transport/AGENTS.md`: transport-local handoff and boundary map
- `doc/HIGH_VOLUME_MODEL_BASELINE.md`: design reference for queue-first high-volume compression work

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
- The runtime entry is library/SDK-first. HTTP pages, demo APIs, and mock runtime surfaces are validation shells.
- Hot-path observability is logs, traces, and bounded diagnostics counters rather than scan-heavy model projections or full-table reconciliation.
- One verified realtime adapter path is still `Worker + WorkerContext + WebSocket adapter + mock clients`, but that is now treated as a reference transport adapter rather than the product boundary.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.

The transport-neutral runtime model is now framed around three channels:

- task dispatch channel
- result ingest channel
- system-event channel for online/offline/heartbeat and related control-plane signals

## Current Facts

- Boot entry: `xa-mass-dev-app`
- SDK entry: `xa-mass-sdk` / `MassSdk`
- Java baseline: JDK 21 with virtual threads used through explicit runtime abstractions
- Runtime model: task dispatch, result ingest, and system events are explicit transport seams
- Active transport adapters: polling, websocket, and socket
- Current task-create HTTP route: `POST /status/api/tasks`
- Current verified ports: `server.port=8088`, `mass.websocket.port=18088`
- Pull-style workers are mainline through `MassSdkApplication.pullWorker(...)` and `/worker-api/*`
- Worker capability truth is `supportedEventCodes`; `supportedProjects` is only a coarse filter hint
- `Task.project`, `Task.user`, and `Task.sharedConfig` are task-level truth; `TaskMsg.input/output` are per-item payload boundaries
- Verified lifecycle coverage includes `NEW -> READY -> RUNNING -> TERMINAL`, `NEW -> READY -> PAUSED -> READY`, and `NEW -> BLOCKED -> READY`

See [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md) for startup and smoke commands, [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md) for active HTTP contracts, and [transport/AGENTS.md](./transport/AGENTS.md) for transport-local ownership.

## Quick Start

- startup, smoke flow, and regression commands: [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- active HTTP contract and task routes: [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- transport module map and verification commands: [transport/AGENTS.md](./transport/AGENTS.md)
- external worker samples and quickstart: [samples/](./samples/) and [doc/EXTERNAL_WORKER_QUICKSTART.md](./doc/EXTERNAL_WORKER_QUICKSTART.md)

## Module Map

- `xa-mass-dev-app`: Boot validation shell
- `xa-mass-sdk` + `xa-mass-sdk-api`: embedding entry, runtime composition, and public SDK types
- `transport/transport_api` + `transport/transport_runtime`: transport-neutral SPI and shared transport runtime assembly
- `transport/polling-adapter` + `transport/websocket-adapter` + `transport/socket-adapter`: concrete transport adapters
- `xa-mass-engine`: lifecycle, assignment, result handling, and policy seams
- `xa-mass-web`: HTTP controllers and backend-hosted console shell
- `xa-mass-testing`: acceptance tooling, load harnesses, and chaos probes
- `xa-mass-core`: shared models and infrastructure

Module truth comes from the root `pom.xml`. Do not treat removed historical modules or top-level directories outside the reactor as current mainline.

## SDK Entry

For third-party embedding, depend on `xa-mass-sdk`.

```xml
<dependency>
  <groupId>com.xa.mass</groupId>
  <artifactId>xa-mass-sdk</artifactId>
  <version>${xa.mass.version}</version>
</dependency>
```

- SDK-facing runtime examples, external worker samples, and transport-specific bootstraps live under [samples/](./samples/).
- External worker onboarding lives in [doc/EXTERNAL_WORKER_QUICKSTART.md](./doc/EXTERNAL_WORKER_QUICKSTART.md).

## Documentation Layout

- Keep active operational docs under `doc/`
- Keep transport owner docs under `transport/`
- Historical archive docs have been removed from the current repository snapshot
- If a document disagrees with code or runtime, prefer code and verified runtime
