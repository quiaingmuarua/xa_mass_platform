# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## First Read

Before changing behavior, read these documents in order:

1. `AGENTS.md` — repo-level agent contract and guardrails
2. `doc/AGENT_BASELINE.md` — platform definition and model boundaries
3. `doc/STATE_MACHINE_BASELINE.md` — lifecycle rules for Task, TaskMsg, TaskMsgAttempt, WorkerContext
4. `doc/INFRA_TRUTH_LAYERS.md` — placement rules for control-plane storage vs. runtime state vs. trace/audit

Then jump to the owning module README for the specific area being changed.

**Trust order:** code > verified runtime behavior > `AGENTS.md` > active owner docs > module READMEs.

## Build Commands

```bash
# Compile entire reactor (skip tests)
./mvnw -DskipTests compile

# Build frontend
cd frontend && corepack pnpm build && cd ..

# Run all tests for a specific module (example: xa-mass-server)
./mvnw -pl xa-mass-server -am test

# Run a specific test class
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MyTestClass test

# Run the focused regression gate (high-signal coverage)
mvn -pl xa-mass-server -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerContextAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,TaskApiTargetedWorkerDebugIntegrationTest,ControlConsoleRoutingIntegrationTest,MockRuntimeDataLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test

# Boot-shell E2E core acceptance
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskApiCallbackReplayIntegrationTest,TaskApiMixedResultsIntegrationTest,TaskApiMultiRoundDispatchIntegrationTest,TaskApiSingleWorkerReuseIntegrationTest test

# Engine concurrency acceptance
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TaskConcurrencyAcceptanceTest test

# Perf load model
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner

# Perf smoke bundle (validates current workspace after engine changes)
xa-mass-testing/scripts/run-perf-smokes.sh

# Cross-language external worker samples
./scripts/run-external-worker-samples.sh
```

**Windows note:** on mixed-drive workspaces, surefire is configured with `useManifestOnlyJar=false` and `-Djdk.net.URLClassPath.disableClassPathURLCheck=true` to avoid false missing-class errors.

**Java baseline:** JDK 21 with virtual threads routed through explicit runtime abstractions (`VirtualThreadRuntimeTaskExecutor`).

## Verified Runtime Ports

- `server.port=8088` — backend-hosted control console and JSON APIs
- `mass.websocket.port=18088` — WebSocket transport adapter
- `mass.socket.port=18089` — socket transport adapter (when `mass.socket.enabled=true`)

Boot entry: `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java`

## Fast Code Verification Path

Before inferring architecture from doc vocabulary, read these five files:

1. `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java` — orchestration facade and composition root
2. `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java` — lifecycle transitions
3. `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java` — result convergence
4. `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskWorkRuntime.java` — runtime queue/lease contract
5. `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskDetailStore.java` — storage-edge compatibility residue shapes

Verify three things quickly: (1) runtime admission goes through `TaskWorkRuntime`, not a message-CRUD mainline; (2) result convergence is runtime-first with compatibility projection as best-effort residue; (3) bounded message/attempt reads live behind explicit compatibility surfaces, not as the default query model.

## Architecture Overview

XA Mass Platform solves one kernel problem: match structured work items (`TaskMsg`) to heterogeneous, stateful workers, track per-item result, and converge task-level completion state.

**Stable kernel types:** `Task`, `TaskMsg`, `TaskMsgAttempt`

**Infra truth has three layers** (see `doc/INFRA_TRUTH_LAYERS.md`):
- **Control-plane storage** — task/worker/rule truth that survives restart (`platform_infra/mass-storage-*`)
- **Runtime state** — hot-path queue/lease/counter/lock state (`platform_infra/mass-runtime-*`)
- **Trace/audit stream** — high-volume history and analysis (`platform_infra/mass-trace-sink`, `doc/TRACE_CONTRACT.md`)

Do not collapse layers. Missing trace implementation does not make trace-shaped data become DB truth.

### Module Map

| Module path | Artifact | Owns |
|---|---|---|
| `xa-mass-base` | `xa-mass-base` | Shared base models, enums, channel primitives |
| `platform_infra/mass-queue-primitives` | — | Keyed queue/blocking-poll/backpressure primitive |
| `platform_infra/mass-runtime-api` | — | Runtime queue/lease/counter contracts |
| `platform_infra/mass-runtime-memory` | — | In-memory `TaskWorkRuntime` (current default) |
| `platform_infra/mass-runtime-redis` | — | Redis runtime baseline (not in verified path) |
| `platform_infra/mass-storage-api` | — | Task/worker/rule storage contracts |
| `platform_infra/mass-storage-memory` | — | In-memory control-plane storage (default/test) |
| `platform_infra/mass-storage-jdbc` | — | JDBC storage for H2/PostgreSQL |
| `platform_infra/mass-trace-sink` | — | `ExecutionEvent` / `ExecutionEventType` canonical trace model |
| `transport/transport_api` | `xa-mass-transport-api` | Transport-neutral dispatch/result/system-event contracts |
| `transport/transport_runtime` | `xa-mass-transport-runtime` | Shared runtime assembly, adapter routing, delivery glue |
| `transport/polling-adapter` | `xa-mass-transport-polling` | Pull/polling worker transport |
| `transport/websocket-adapter` | `xa-mass-transport-websocket` | WebSocket transport |
| `transport/socket-adapter` | `xa-mass-transport-socket` | Socket transport |
| `xa-mass-engine` | `xa-mass-engine` | Lifecycle, matching, assignment, result, terminal convergence |
| `xa-mass-sdk-api` | `xa-mass-sdk-api` | Stable SDK-facing contracts |
| `xa-mass-sdk` | `xa-mass-sdk` | Embedding entry and runtime composition for JVM callers |
| `xa-mass-testing` | `xa-mass-testing` | Acceptance tooling, load harnesses, chaos probes |
| `xa-mass-worker-pack` | `xa-mass-worker-pack` | Builtin workers, sample clients, launchers |
| `xa-mass-server` | `xa-mass-server` | Boot entry, HTTP controllers, control console, frontend shell |

### Engine Internals

Engine entry: `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`

Key classes:
- `TaskManager` — engine-internal orchestration facade and composition root (not the default cross-module API)
- `TaskConcurrencyCoordinator` — task/message locking and coalesced progress reconciliation
- `TaskCommandService` / `TaskQueryService` — shell/admin mutation and inspection flows
- `TaskResultIngestFacade` — transport/runtime result ingress entry point
- `TaskCompatibilityProjectionStore` — engine-internal owner for bounded `TaskDetailStore` projection residue; keeps storage-edge construction out of runtime orchestrators
- `TaskProjectionStateAuditor` — explicit full-scan compatibility projection diagnostics; only for audit/diagnostic work, not hot paths
- `TaskMessageCompatibilityState` — engine-owned enums for message/attempt residue state; converts to storage projection types only at persistence boundaries
- `WorkerManager` — worker management; cross-module callers that only need lookup should depend on `WorkerStorage` directly
- `RuleManager` — rule evaluation; cross-module callers that only need rule definitions should depend on `RuleStorage` directly

**Cross-module callers must not default to `TaskManager`.** Prefer `TaskCommandService`, `TaskQueryService`, `TaskResultIngestFacade`, `TaskEventService`, and runtime ports.

**`@CompatibilityProjectionOnly`** marks classes whose purpose is bounded projection residue. They must not be used on hot paths or as native runtime state owners.

### Transport Internals

- `adapterId + routeKey` is the delivery address in a multi-adapter runtime; `transportHint` is only a coarse family hint
- `TransportPacket` is the internal flat transport envelope
- Engine must not take a direct dependency on `transport/transport_api`

### Trace Contract

Canonical trace objects: `com.xa.mass.trace.sink.ExecutionEvent` and `ExecutionEventType`.

`ExecutionEventType` is the only stable event-name vocabulary. Do not maintain a parallel event-name registry elsewhere. Full contract: `doc/TRACE_CONTRACT.md`.

## Key Behavioral Contracts

- **Task task-ingest split:** `POST /api/v1/tasks` creates only the task shell; work items append through `POST /api/v1/tasks/{taskId}/items`
- **`eventCode`** is globally unique capability identity
- **`Task.workloadClass`** (`INTERACTIVE` or `BULK`) drives scheduling semantics; do not resolve from free-form `sharedConfig`
- **`Task.sharedConfig`** and **`TaskMsg.input/output`** are the generic payload boundaries; `target` is only a conventional key inside `TaskMsg.input`, not a model field
- **Worker matching** is task-level orchestration; do not reintroduce per-`TaskMsg` matching on the hot path
- **`TaskMsg`** and **`TaskMsgAttempt`** are bounded compatibility/audit projections; `TaskWorkRuntime` owns the hot-path ready/claim/lease/expiry truth
- **Storage mode** defaults to in-memory; JDBC path is behind `mass.storage.mode=jdbc-h2` or `mass.storage.mode=jdbc-postgres`

## Agent Contract (Hard Rules)

Use `AGENTS.md` as the single source for agent behavior, refactor discipline,
rename discipline, and compatibility/convergence rules. Do not maintain a
second summarized rule set here.

**Multi-file or core change planning must include:** scope, out-of-scope, files and symbols, alternatives considered, costs, test impact with classifications, risk, and verification steps.

## Testing Lanes

| Lane | Owner module | When required |
|---|---|---|
| `Boot-shell E2E` | `xa-mass-server` | PR required (focused subset) |
| `concurrency` | `xa-mass-engine` | Core when race-sensitive |
| `perf` | `xa-mass-testing` | Core signal; smoke optional/non-blocking |
| `cross-language black-box` | `xa-mass-server` | PR + nightly |
| `chaos` | `xa-mass-testing` | Scheduled/manual, not default PR-required |

**Lifecycle or state-transition changes** require: `doc/STATE_MACHINE_BASELINE.md`, `doc/TRACE_CONTRACT.md`, and `doc/E2E_BASELINE.md` updated together with the code change.

Test artifacts output to:
- `xa-mass-testing/target/perf-reports/`
- `xa-mass-testing/target/concurrency-reports/`
- `xa-mass-testing/target/chaos-reports/`

## Key Document Index

| Topic | Document |
|---|---|
| Startup, smoke, regression commands | `doc/VERIFIED_RUNBOOK.md` |
| Lifecycle state machines | `doc/STATE_MACHINE_BASELINE.md` |
| Infra placement rules | `doc/INFRA_TRUTH_LAYERS.md` |
| Trace contract | `doc/TRACE_CONTRACT.md` |
| E2E baseline | `doc/E2E_BASELINE.md` |
| HTTP API contracts | `doc/INTERNAL_API_REFERENCE.md` |
| Transport boundary | `transport/TRANSPORT_BOUNDARY_BASELINE.md` |
| Engine details | `xa-mass-engine/README.md` |
| Testing details | `xa-mass-testing/README.md` |
| Known gaps | `doc/CURRENT_GAPS.md` |
| Deprecation index | `DEPRECATION_LEDGER.md` |
| External worker onboarding | `doc/EXTERNAL_WORKER_QUICKSTART.md` |
