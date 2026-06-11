# XA Mass Platform

Status: current project summary.

Trust code and verified runtime behavior over historical documentation.

Documentation is agent-first. Human-facing architecture docs exist for
explanation and onboarding, but current implementation truth is maintained
through `AGENTS.md`, owner baselines, module READMEs, proof registries, verified
runbooks, and current code. Do not reorder the root reading path to optimize
human-first browsing at the expense of agent execution accuracy.

## Start Here

For agents and contributors:

- [AGENTS.md](./AGENTS.md)
- [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- [doc/TASK_LIFECYCLE_BASELINE.md](./doc/TASK_LIFECYCLE_BASELINE.md)
- [doc/README.md](./doc/README.md) - expanded global doc map when needed
- [transport/AGENTS.md](./transport/AGENTS.md)

For humans:

- [architecture/README.md](./architecture/README.md) - human-facing architecture and onboarding guide
- [architecture/quick-start.md](./architecture/quick-start.md) - embedded mental-model path
- [README.zh-CN.md](./README.zh-CN.md) - Chinese project introduction for people

For SDK and integration users:

- [sdk/README.md](./sdk/README.md)
- [sdk/xa-mass-java-sdk/README.md](./sdk/xa-mass-java-sdk/README.md)
- [sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md](./sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md) - shortest task producer plus worker session path
- [integrations/README.md](./integrations/README.md)

Chinese architecture entry:

- [architecture/README.zh-CN.md](./architecture/README.zh-CN.md)

Additional current baseline:

- [xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md](./xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md)
- [xa-mass-trace/README.md](./xa-mass-trace/README.md)

Local distributed verification:

```bash
./mvnw -pl xa-mass-server -am -DskipTests package
docker compose up redis server
```

Compose runs the already-built server jar with `durable-local`: SQLite file
storage for control-plane truth and Redis for engine/transport runtime truth.
See [xa-mass-testing/VERIFIED_RUNBOOK.md](./xa-mass-testing/VERIFIED_RUNBOOK.md) for reset and smoke
commands.

## What It Is

XA Mass Platform is reusable distributed scheduling infrastructure.

It solves one recurring kernel problem: safely and repeatedly assign structured
work to heterogeneous, stateful workers, track each result, and converge
task-level completion state under retries, duplicate delivery, stale replay,
and worker churn.

The platform primitives are intentionally small:

```text
Task + Worker + Scheduling Plane
+ lease-based dispatch
+ idempotent result convergence
+ multi-transport delivery
+ retry/repair/backpressure
= reusable distributed scheduling infrastructure
```

Current kernel truth is intentionally narrow:

- `Task.contract` is the current public/runtime preset input
  (`SESSION | BATCH`); engine behavior consumes resolved task scheduling policy
  values derived from it
- `Task.intakeStatus` answers whether ingress remains `OPEN` or is already `SEALED`
- `TaskWorkRuntime` answers ready/delayed/lease/counter truth for execution
- result convergence is runtime-first, but the lifecycle owner split is documented
  in [`doc/TASK_LIFECYCLE_BASELINE.md`](./doc/TASK_LIFECYCLE_BASELINE.md)
  and current engine/runtime code rather than frozen in this summary

Current mainline execution path:

- `Task shell -> item append -> runtime enqueue -> scheduling eligibility -> worker selection and assignment -> transport dispatch -> result convergence -> task state`
- By the time a work item enters transport, Scheduling Plane has already
  selected a concrete worker. Transport preserves that `selectedWorkerId` as a
  delivery constraint; `routeKey` is opaque connection/domain metadata and
  `deliveryQueueKey` is a queue partition, not worker-selection truth.

Scheduling Plane Boundary:

- Scheduling Plane ownership is split into three first-class categories:
  `TaskSchedulingPolicy` for competition admission/cadence/priority/fairness/
  budget, `WorkerSchedulingPolicy` for worker resource-universe selection and
  pool constraints, and `RuntimeWorkerSelection` for concrete worker choice
  from live evidence and admission state
- current implementation: engine-facing value contracts exist for
  `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
  `ResolvedWorkerSchedulingPolicy`; persisted catalog/binding and configurable
  policy modules are not implemented yet
- current policy remains distributed across resolved task policy, explicit
  worker group selectors, matching rule sets, assignment policy, and runtime
  backpressure/admission behavior
- `TaskDispatchIntent` is the task-level dispatch intent: project,
  `workerGroupId(s)` or selector, `routingCode`, route attributes,
  optional `targetWorkerId`, and optional constrained target worker attributes
- `WorkerGroup` owns capability boundary: project bindings, event bindings,
  default group attributes, and capacity/concurrency defaults. It constrains
  worker scheduling resolution, but it is not an internal Scheduling Plane
  strategy owner
- runtime worker selection happens inside an already selected WorkerGroup using
  online/presence/load/admission/draining/lease evidence
- work items do not own worker-selection policy. An item carries `eventCode`
  plus input or `payloadRef`; `eventCode` is handler/capability identity, not a
  worker selector
- correct path: `item.eventCode -> validate selected WorkerGroup supports the
  event -> worker executes local handler by eventCode`; do not scan all workers
  from item payload or eventCode

Common scenarios include IM bots, crawlers, LLM agents, data pipelines,
CI/CD runners, device/RPA workers, human-review queues, and GPU/inference
workers.
The shared kernel is:

- `stateful worker + scheduling evidence + worker-selection mechanism + per-item result tracking + task-level convergence`
- stable kernel truth starts from `Task + Worker + Scheduling Plane`; result, audit, and terminal policy are lifecycle consequences of that mainline
- matching as current worker-selection mechanism: the current default uses
  group-first candidate acquisition, worker scheduling evidence,
  QLExpress-backed eligibility rules, ranking, and admission without changing
  worker-runtime ownership. It is not a top-level policy owner.
- transport-neutral runtime seams: task dispatch, result ingest, and system events
- SDK-first runtime entry; server provides the backend product/API host,
  control-console support, auth/IAM, and API-key operations without redefining
  kernel ownership
- hot-path observability through logs, traces, and bounded diagnostics rather than scan-heavy projections

Current integration boundary rule:

- detailed guardrails for this boundary live in
  [doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md](./doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md)
- `sdk/xa-mass-java-sdk` is the external Java SDK boundary for task producers,
  external workers, and automation that talks to a running server over public
  HTTP routes
- `sdk/xa-mass-public-contract` owns the narrow Controller-exposed wire
  DTO/constants shared by the server and external SDKs
- `xa-mass-embedded-sdk` is the stable JVM embedding boundary for in-process
  runtime composition
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
- External Java SDK entry: `sdk/xa-mass-java-sdk` / `MassPlatform`
- Embedded SDK entry: `sdk/xa-mass-embedded-sdk` / `MassSdk`
- Java baseline: JDK 21 with virtual threads used through explicit runtime abstractions
- Runtime model: task dispatch, result ingest, and system events are explicit transport seams
- Active transport adapters: polling, websocket, and socket
- Current task shell create HTTP route: `POST /api/v1/tasks`
- Current verified ports: `server.port=8088`, `mass.websocket.port=18088`
- Pull-style workers are mainline through `MassSdkApplication.pullWorker(...)` and `/worker-api/v1/**`
- `Task.project`, `Task.user`, and `Task.sharedConfig` are task-level truth; runtime ingress payload or `payloadRef` is the per-item payload boundary
- `TaskWorkRuntime` is the current hot-path owner for ready work, active lease, retry scheduling, expiry, and backpressure truth
- result convergence remains runtime-first; verify the lifecycle split between
  runtime apply truth, stable-final result rows, and server-local review
  materialization from
  [`doc/TASK_LIFECYCLE_BASELINE.md`](./doc/TASK_LIFECYCLE_BASELINE.md)
- Verified lifecycle coverage includes `NEW -> READY -> RUNNING -> TERMINAL`, `NEW -> READY -> PAUSED -> READY`, and `NEW -> BLOCKED -> READY`

## Module Map

- `xa-mass-base`: shared base models, enums, utility infrastructure, and low-level channel primitives used across the reactor
- `platform_infra/mass-queue-primitives`: narrow keyed queue/blocking-poll/backpressure primitive used by runtime modules that should not own queue bookkeeping directly
- `platform_infra/mass-runtime-api`: shared runtime queue/lease/counter contracts plus the active result-runtime boundary used by engine, transport, server, and test shells
- `platform_infra/mass-runtime-memory`: in-memory runtime implementations for the embedded default path and focused runtime tests
- `platform_infra/mass-runtime-redis`: Redis-backed runtime implementations plus their keyspace/index baseline; used by the compose/local distributed verification path and explicit Redis runtime profiles
- `xa-mass-kernel-spi`: kernel-facing task shell runtime ports and matching rule value contracts used to keep engine production detached from storage modules
- `platform_infra/mass-storage-api`: persistence/control-plane task shell and rule storage contracts; not a kernel-facing engine dependency and not a worker declaration owner
- `platform_infra/mass-storage-memory`: in-memory control-plane task shell storage, worker declaration adapter, and rule-definition storage used by SDK/server defaults and focused tests
- `platform_infra/mass-storage-jdbc`: JDBC control-plane storage implementation for H2/PostgreSQL task shell, rule, and submitter truth; task review/export materialization is server-local
- `platform_infra/mass-trace-sink`: canonical execution-event model plus the default asynchronous JSONL trace sink
- `xa-mass-trace`: DuckDB-backed trace operator CLI for local timeline/stats/validation over canonical JSONL trace output; default human/agent read path for lifecycle trace diagnosis and trace-observed integration verification
- `transport/transport_api`: transport-neutral dispatch/result/system-event contracts and transport model surface
- `transport/transport_runtime`: shared transport runtime assembly, adapter routing, and delivery/result-ingest runtime glue
- `transport/polling-adapter`: polling/pull worker transport adapter
- `transport/socket-adapter`: socket worker transport adapter
- `transport/websocket-adapter`: WebSocket worker transport adapter
- `xa-mass-worker-runtime`: worker-plane lifecycle/resource owner for WorkerGroup, AdapterNode, NodeGroupBinding, worker declaration ports, candidate source, scheduling evidence, admission, dispatch gates, and warm hints
- `xa-mass-engine`: lifecycle, assignment, result handling, and policy seams
- `sdk/xa-mass-public-contract`: narrow public HTTP wire DTOs/constants shared by server and external SDKs
- `sdk/xa-mass-java-sdk`: external Java client/session/handler SDK for task producers and external workers
- `sdk/xa-mass-embedded-sdk-api`: embedded SDK-facing auth, catalog, event, and model contracts
- `sdk/xa-mass-embedded-sdk`: embedding entry and runtime composition for JVM callers
- `tools/xa-mass-admin-cli`: server-owned operator/admin HTTP CLI for health,
  auth, API-key inspection, and typed env init against a running server; not a
  public SDK and not a task/worker actor client
- `xa-mass-testing`: acceptance tooling, load harnesses, and chaos probes
- `integrations/xa-mass-worker-pack`: official worker capability pack, dev/E2E
  harness support, and worker-side command runtime
- `xa-mass-server`: Boot reference host, HTTP controllers, lightweight backend
  product skeleton, backend-hosted control console, and frontend shell

Module truth comes from the root `pom.xml`. Do not treat removed historical modules or top-level directories outside the reactor as current mainline.

Top-level non-reactor directories are intentionally narrow:

- `architecture/`: human-facing onboarding and mental model, linked from this
  README because it is a cross-module entry point
- `roadmap/`: active cross-module planning, inventories, and decision records;
  use only when the task touches planned convergence or future direction, and
  verify against code before treating it as current truth
- `tools/`: executable local/admin tools that are cross-module entry points but
  not SDK product surfaces; current owner is `tools/xa-mass-admin-cli`

## Pointers

- runtime infra ownership: [platform_infra/README.md](./platform_infra/README.md)
- trace operator CLI: [xa-mass-trace/README.md](./xa-mass-trace/README.md)
- task lifecycle baseline: [doc/TASK_LIFECYCLE_BASELINE.md](./doc/TASK_LIFECYCLE_BASELINE.md)
- storage-jdbc ownership and current drift notes: [platform_infra/mass-storage-jdbc/README.md](./platform_infra/mass-storage-jdbc/README.md)
- Redis runtime keyspace baseline: [platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md](./platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md)
- startup, smoke, and regression commands: [xa-mass-testing/VERIFIED_RUNBOOK.md](./xa-mass-testing/VERIFIED_RUNBOOK.md)
- active HTTP contracts: [xa-mass-server/doc/INTERNAL_API_REFERENCE.md](./xa-mass-server/doc/INTERNAL_API_REFERENCE.md)
- transport ownership and verification: [transport/AGENTS.md](./transport/AGENTS.md)
- SDK module map: [sdk/README.md](./sdk/README.md)
- integrations module map: [integrations/README.md](./integrations/README.md)
- SDK/integrations boundary guard: [doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md](./doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md)
- public HTTP contract ownership: [sdk/xa-mass-public-contract/README.md](./sdk/xa-mass-public-contract/README.md)
- external Java SDK: [sdk/xa-mass-java-sdk/README.md](./sdk/xa-mass-java-sdk/README.md)
- embedded SDK contract ownership: [sdk/xa-mass-embedded-sdk-api/README.md](./sdk/xa-mass-embedded-sdk-api/README.md)
- embedded SDK runtime composition: [sdk/xa-mass-embedded-sdk/README.md](./sdk/xa-mass-embedded-sdk/README.md)
- human architecture guide: [architecture/README.md](./architecture/README.md)
- external Java SDK onboarding: [sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md](./sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md)
- fixture samples: [integrations/samples/](./integrations/samples/) (protocol/dev
  fixtures only, not the public SDK product surface)
