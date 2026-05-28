# XA Mass Platform

Status: current project summary.

Trust code and verified runtime behavior over historical documentation.

## Start Here

- [architecture/README.md](./architecture/README.md) - human-facing architecture and onboarding guide
- [README.zh-CN.md](./README.zh-CN.md) - Chinese project introduction for people
- [architecture/README.zh-CN.md](./architecture/README.zh-CN.md) - Chinese architecture guide
- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/README.md](./doc/README.md)
- [transport/AGENTS.md](./transport/AGENTS.md)

Additional current baseline:

- [doc/HIGH_VOLUME_MODEL_BASELINE.md](./doc/HIGH_VOLUME_MODEL_BASELINE.md)
- [xa-mass-trace/README.md](./xa-mass-trace/README.md)

Local distributed verification:

```bash
./mvnw -pl xa-mass-server -am -DskipTests package
docker compose up redis server
```

Compose runs the already-built server jar with `dev,redis-runtime,h2`: H2 file
storage for control-plane truth and Redis for engine/transport runtime truth.
See [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md) for reset and smoke
commands.

## What It Is

XA Mass Platform is a general distributed task scheduling platform.

It solves one recurring kernel problem: match a batch of structured work items
to a batch of heterogeneous, stateful workers, track each result, and
converge task-level completion state.

Current kernel truth is intentionally narrow:

- `Task.contract` answers whether the task is `SESSION` or `BATCH`
- `Task.intakeStatus` answers whether ingress remains `OPEN` or is already `SEALED`
- `TaskWorkRuntime` answers ready/delayed/lease/counter truth for execution
- result convergence is runtime-first, but the active owner split is documented
  in [`doc/RESULT_BOUNDARY_BASELINE.md`](./doc/RESULT_BOUNDARY_BASELINE.md)
  and current engine/runtime code rather than frozen in this summary

Current mainline execution path:

- `Task shell -> item append -> runtime enqueue -> dispatch binder -> transport delivery view -> result convergence -> task state`

Common scenarios include IM bots, crawlers, LLM agents, and device/RPA workers.
The shared kernel is:

- `stateful worker + capability/routing match + per-item result tracking + task-level convergence`
- stable kernel truth: `Task`, assignment, result, audit, and terminal policy
- transport-neutral runtime seams: task dispatch, result ingest, and system events
- SDK-first runtime entry; server provides a lightweight backend product shell
  for HTTP APIs, auth/IAM, API-key operations, and the control console without
  redefining kernel ownership
- hot-path observability through logs, traces, and bounded diagnostics rather than scan-heavy projections

Current integration boundary rule:

- `xa-mass-sdk` is the stable integration boundary for workers, embedding
  clients, and external automation
- `xa-mass-server` is the reference host and lightweight backend product
  skeleton for HTTP/auth/project/tenant/user/API-key/console surfaces; it may
  evolve those host surfaces without redefining engine-kernel semantics
- `xa-mass-base` and `xa-mass-engine` models are allowed to evolve quickly;
  public compatibility is preserved through SDK request models and SDK snapshot
  read models rather than by freezing internal `Task` / `Worker` / runtime
  structures
- SDK snapshots are read-model contracts, not runtime truth and not engine
  decision input

## Current Facts

- Boot entry: `xa-mass-server`
- SDK entry: `xa-mass-sdk` / `MassSdk`
- Java baseline: JDK 21 with virtual threads used through explicit runtime abstractions
- Runtime model: task dispatch, result ingest, and system events are explicit transport seams
- Active transport adapters: polling, websocket, and socket
- Current task shell create HTTP route: `POST /api/v1/tasks`
- Current verified ports: `server.port=8088`, `mass.websocket.port=18088`
- Pull-style workers are mainline through `MassSdkApplication.pullWorker(...)` and `/worker-api/v1/**`
- `Task.project`, `Task.user`, and `Task.sharedConfig` are task-level truth; runtime ingress payload or `payloadRef` is the per-item payload boundary
- `TaskWorkRuntime` is the current hot-path owner for ready work, active lease, retry scheduling, expiry, and backpressure truth
- result convergence remains runtime-first; verify the active split between
  runtime apply truth, stable-final result rows, and compatibility residue from
  [`doc/RESULT_BOUNDARY_BASELINE.md`](./doc/RESULT_BOUNDARY_BASELINE.md)
- Verified lifecycle coverage includes `NEW -> READY -> RUNNING -> TERMINAL`, `NEW -> READY -> PAUSED -> READY`, and `NEW -> BLOCKED -> READY`

## Module Map

- `xa-mass-base`: shared base models, enums, utility infrastructure, and low-level channel primitives used across the reactor
- `platform_infra/mass-queue-primitives`: narrow keyed queue/blocking-poll/backpressure primitive used by runtime modules that should not own queue bookkeeping directly
- `platform_infra/mass-runtime-api`: shared runtime queue/lease/counter contracts plus the active result-runtime boundary used by engine, transport, server, and test shells
- `platform_infra/mass-runtime-memory`: in-memory runtime implementations for the current default embedded path and focused runtime tests
- `platform_infra/mass-runtime-redis`: Redis-backed runtime implementations plus their keyspace/index baseline; explicit opt-in, not the current default verified runtime path
- `platform_infra/mass-storage-api`: shared task/worker/rule storage contracts and storage-adjacent rule types used across engine, JDBC adapters, server, SDK, and tests
- `platform_infra/mass-storage-memory`: in-memory control-plane task/worker storage plus the current in-memory rule helpers used by SDK/server defaults and focused tests
- `platform_infra/mass-storage-jdbc`: JDBC control-plane storage implementation for H2/PostgreSQL task, worker, rule, and submitter truth; current implementation also keeps compatibility projections in-process
- `platform_infra/mass-trace-sink`: canonical execution-event model plus the default asynchronous JSONL trace sink
- `xa-mass-trace`: DuckDB-backed trace operator CLI for local timeline/stats/validation over canonical JSONL trace output; default human/agent read path for lifecycle trace diagnosis and trace-observed integration verification
- `transport/transport_api`: transport-neutral dispatch/result/system-event contracts and transport model surface
- `transport/transport_runtime`: shared transport runtime assembly, adapter routing, and delivery/result-ingest runtime glue
- `transport/polling-adapter`: polling/pull worker transport adapter
- `transport/socket-adapter`: socket worker transport adapter
- `transport/websocket-adapter`: WebSocket worker transport adapter
- `xa-mass-engine`: lifecycle, assignment, result handling, and policy seams
- `xa-mass-sdk-api`: stable SDK-facing auth, catalog, event, and model contracts
- `xa-mass-sdk`: embedding entry and runtime composition for JVM callers
- `xa-mass-testing`: acceptance tooling, load harnesses, and chaos probes
- `integrations/xa-mass-worker-pack`: official builtin/sample/dev worker
  capabilities, sample clients, launchers, and worker-side command runtime
- `xa-mass-server`: Boot reference host, HTTP controllers, lightweight backend
  product skeleton, backend-hosted control console, and frontend shell

Module truth comes from the root `pom.xml`. Do not treat removed historical modules or top-level directories outside the reactor as current mainline.

## Pointers

- runtime infra ownership: [platform_infra/README.md](./platform_infra/README.md)
- trace operator CLI: [xa-mass-trace/README.md](./xa-mass-trace/README.md)
- result owner baseline: [doc/RESULT_BOUNDARY_BASELINE.md](./doc/RESULT_BOUNDARY_BASELINE.md)
- storage-jdbc ownership and current drift notes: [platform_infra/mass-storage-jdbc/README.md](./platform_infra/mass-storage-jdbc/README.md)
- Redis runtime keyspace baseline: [platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md](./platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md)
- startup, smoke, and regression commands: [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
- active HTTP contracts: [doc/INTERNAL_API_REFERENCE.md](./doc/INTERNAL_API_REFERENCE.md)
- transport ownership and verification: [transport/AGENTS.md](./transport/AGENTS.md)
- SDK contract ownership: [xa-mass-sdk-api/README.md](./xa-mass-sdk-api/README.md)
- SDK embedding/runtime composition: [xa-mass-sdk/README.md](./xa-mass-sdk/README.md)
- human architecture guide: [architecture/README.md](./architecture/README.md)
- external worker onboarding: [doc/EXTERNAL_WORKER_QUICKSTART.md](./doc/EXTERNAL_WORKER_QUICKSTART.md)
- samples: [integrations/samples/](./integrations/samples/)
