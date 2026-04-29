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

- `xa-mass-base`: shared base models, enums, utility infrastructure, and low-level channel primitives used across the reactor
- `platform_infra/mass-queue-primitives`: narrow keyed queue/blocking-poll/backpressure primitive used by runtime modules that should not own queue bookkeeping directly
- `platform_infra/mass-runtime-api`: shared runtime queue/lease/counter contract used by engine, transport, server, and test shells
- `platform_infra/mass-runtime-memory`: in-memory `TaskWorkRuntime` implementation for the current default embedded path and focused runtime tests
- `platform_infra/mass-runtime-redis`: Redis runtime keyspace/index baseline plus future `TaskWorkRuntime` module; not part of the current verified runtime path
- `platform_infra/mass-storage-api`: shared task/worker/rule storage contracts and storage-adjacent rule types used across engine, JDBC adapters, server, SDK, and tests
- `platform_infra/mass-storage-memory`: in-memory control-plane task/worker storage plus the current in-memory rule helpers used by SDK/server defaults and focused tests
- `platform_infra/mass-storage-jdbc`: JDBC control-plane storage implementation for H2/PostgreSQL task, worker, rule, and submitter truth; current implementation also keeps compatibility projections in-process
- `transport/transport_api`: transport-neutral dispatch/result/system-event contracts and transport model surface
- `transport/transport_runtime`: shared transport runtime assembly, adapter routing, and delivery/result-ingest runtime glue
- `transport/polling-adapter`: polling/pull worker transport adapter
- `transport/socket-adapter`: socket worker transport adapter
- `transport/websocket-adapter`: WebSocket worker transport adapter
- `xa-mass-engine`: lifecycle, assignment, result handling, and policy seams
- `xa-mass-sdk-api`: stable SDK-facing auth, catalog, event, and model contracts
- `xa-mass-sdk`: embedding entry and runtime composition for JVM callers
- `xa-mass-testing`: acceptance tooling, load harnesses, and chaos probes
- `xa-mass-worker-pack`: official builtin/sample/dev worker capabilities, sample clients, launchers, and worker-side command runtime
- `xa-mass-server`: Boot validation shell, HTTP controllers, backend-hosted control console, and frontend shell

Module truth comes from the root `pom.xml`. Do not treat removed historical modules or top-level directories outside the reactor as current mainline.

## Pointers

- runtime infra ownership: [platform_infra/README.md](./platform_infra/README.md)
- storage-jdbc ownership and current drift notes: [platform_infra/mass-storage-jdbc/README.md](./platform_infra/mass-storage-jdbc/README.md)
- Redis runtime keyspace baseline: [platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md](./platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md)
- startup, smoke, and regression commands: [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- active HTTP contracts: [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- transport ownership and verification: [transport/AGENTS.md](./transport/AGENTS.md)
- SDK contract ownership: [xa-mass-sdk-api/README.md](./xa-mass-sdk-api/README.md)
- SDK embedding/runtime composition: [xa-mass-sdk/README.md](./xa-mass-sdk/README.md)
- external worker onboarding: [doc/EXTERNAL_WORKER_QUICKSTART.md](./doc/EXTERNAL_WORKER_QUICKSTART.md)
- samples: [samples/](./samples/)
