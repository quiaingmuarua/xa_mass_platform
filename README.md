# XA Mass Platform

Status: current project summary.

Trust code and verified runtime behavior over historical documentation.

## Start Here

- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/TESTING_BASELINE.md](./doc/TESTING_BASELINE.md)
- [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- [transport/AGENTS.md](./transport/AGENTS.md)

Design-only reference:

- [doc/HIGH_VOLUME_MODEL_BASELINE.md](./doc/HIGH_VOLUME_MODEL_BASELINE.md)

## What It Is

XA Mass Platform is a general distributed task scheduling platform.

It solves one recurring kernel problem: match a batch of structured work items
(`TaskMsg`) to a batch of heterogeneous, stateful workers, track each result,
and converge task-level completion state.

Common scenarios include IM bots, crawlers, LLM agents, and device/RPA workers.
The shared kernel is:

- `stateful worker + capability/routing match + per-item result tracking + task-level convergence`
- stable kernel types: `Task`, `TaskMsg`, `TaskMsgAttempt`
- transport-neutral runtime seams: task dispatch, result ingest, and system events
- SDK-first runtime entry; HTTP pages and demo APIs are validation shells
- hot-path observability through logs, traces, and bounded diagnostics rather than scan-heavy projections

## Current Facts

- Boot entry: `xa-mass-server`
- SDK entry: `xa-mass-sdk` / `MassSdk`
- Java baseline: JDK 21 with virtual threads used through explicit runtime abstractions
- Runtime model: task dispatch, result ingest, and system events are explicit transport seams
- Active transport adapters: polling, websocket, and socket
- Current task-create HTTP route: `POST /status/api/tasks`
- Current verified ports: `server.port=8088`, `mass.websocket.port=18088`
- Pull-style workers are mainline through `MassSdkApplication.pullWorker(...)` and `/worker-api/*`
- `Task.project`, `Task.user`, and `Task.sharedConfig` are task-level truth; `TaskMsg.input/output` are per-item payload boundaries
- Verified lifecycle coverage includes `NEW -> READY -> RUNNING -> TERMINAL`, `NEW -> READY -> PAUSED -> READY`, and `NEW -> BLOCKED -> READY`

## Module Map

- `platform_infra/mass-queue-primitives`: narrow keyed queue/blocking-poll/backpressure primitive used by runtime modules that should not own queue bookkeeping directly
- `platform_infra/mass-runtime-api`: shared runtime queue/lease/counter contract used by engine, transport, server, and test shells
- `platform_infra/mass-runtime-memory`: in-memory `TaskWorkRuntime` implementation for the current default embedded path and focused runtime tests
- `platform_infra/mass-runtime-redis`: Redis runtime keyspace/index baseline plus future `TaskWorkRuntime` module; not part of the current verified runtime path
- `xa-mass-server`: Boot validation shell, HTTP controllers, backend-hosted control console, and frontend shell
- `xa-mass-worker-pack`: official builtin/sample/dev worker capabilities, sample clients, launchers, and worker-side command runtime
- `xa-mass-sdk` + `xa-mass-sdk-api`: embedding entry, runtime composition, and public SDK types
- `transport/transport_api` + `transport/transport_runtime`: transport-neutral SPI and shared transport runtime assembly
- `transport/polling-adapter` + `transport/websocket-adapter` + `transport/socket-adapter`: concrete transport adapters
- `xa-mass-engine`: lifecycle, assignment, result handling, and policy seams
- `xa-mass-testing`: acceptance tooling, load harnesses, and chaos probes
- `xa-mass-base`: shared base models, enums, and infrastructure

Module truth comes from the root `pom.xml`. Do not treat removed historical modules or top-level directories outside the reactor as current mainline.

## Pointers

- runtime infra ownership: [platform_infra/README.md](./platform_infra/README.md)
- Redis runtime keyspace baseline: [platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md](./platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md)
- startup, smoke, and regression commands: [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- active HTTP contracts: [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- transport ownership and verification: [transport/AGENTS.md](./transport/AGENTS.md)
- external worker onboarding: [doc/EXTERNAL_WORKER_QUICKSTART.md](./doc/EXTERNAL_WORKER_QUICKSTART.md)
- samples: [samples/](./samples/)
