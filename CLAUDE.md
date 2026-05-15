# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## First Read

Before changing behavior, read these documents in order:

1. `AGENTS.md` — repo-level agent contract and guardrails
2. `doc/AGENT_BASELINE.md` — platform definition and model boundaries
3. `doc/STATE_MACHINE_BASELINE.md` — lifecycle rules for Task, TaskMessageProjection, TaskMessageAttemptProjection, WorkerContext
4. `doc/INFRA_TRUTH_LAYERS.md` — placement rules for control-plane storage vs. runtime state vs. trace/audit

Then jump to the owning module README for the specific area being changed. Use `doc/TESTING_INDEX.md` as the default entry for test-layer decisions, minimum verification, and CI gate truth.

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

# Focused regression gate (high-signal coverage)
mvn -pl xa-mass-server -am -Dtest=WorkerAttributesTest,WorkerContextAttributesTest,WorkerMatchContextTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskApiDelayedWorkerAvailabilityIntegrationTest,TaskApiWorkerAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,TaskApiTargetedWorkerDebugIntegrationTest,ControlConsoleRoutingIntegrationTest,MockRuntimeDataLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test

# Representative server scheduling E2E gate
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerSchedulingE2eSuite test

# Engine concurrency acceptance
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EngineSchedulingCoreSuite test

# Perf load model
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner

# Perf smoke bundle (validates current workspace after engine changes)
xa-mass-testing/scripts/run-perf-smokes.sh

# Chaos smoke bundle (CI-gated; runs three probes)
xa-mass-testing/scripts/run-chaos-smokes.sh

# Cross-language external worker samples
./scripts/run-external-worker-samples.sh
```

**Windows note:** surefire is configured with `useManifestOnlyJar=false` and `-Djdk.net.URLClassPath.disableClassPathURLCheck=true` to avoid false missing-class errors on mixed-drive workspaces.

**Java baseline:** JDK 21 with virtual threads routed through explicit runtime abstractions.

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

XA Mass Platform solves one kernel problem: match structured work items to heterogeneous, stateful workers, track per-item result, and converge task-level completion state.

**Stable kernel:** `Task / assignment / result / audit / terminal policy`

**Kernel truth is explicitly split across three owners:**
- `Task.contract` — runtime contract: `SESSION` (open-ended, streaming) or `BATCH` (sealed, auto-terminal)
- `Task.intakeStatus` — append-window truth: `OPEN` or `SEALED`
- `TaskWorkRuntime` — hot-path owner for ready work, active leases, retry scheduling, expiry, and result application

`TaskMessageProjection` and `TaskMessageAttemptProjection` are **bounded compatibility residue** (audit/UI helpers), not the runtime hot-path owners.

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
| `platform_infra/mass-runtime-memory` | — | In-memory `TaskWorkRuntime` (current verified default) |
| `platform_infra/mass-runtime-redis` | — | Redis runtime baseline (not in verified path) |
| `platform_infra/mass-storage-api` | — | Task/worker/rule storage contracts + `TaskDetailStore` compatibility seam |
| `platform_infra/mass-storage-memory` | — | In-memory control-plane storage (default/test); also owns `QLExpressRuleEvaluator` |
| `platform_infra/mass-storage-jdbc` | — | JDBC control-plane storage for H2/PostgreSQL |
| `platform_infra/mass-trace-sink` | — | `ExecutionEvent` / `ExecutionEventType` canonical trace model |
| `transport/transport_api` | `xa-mass-transport-api` | Transport-neutral dispatch/result/system-event contracts |
| `transport/transport_runtime` | `xa-mass-transport-runtime` | Shared runtime assembly, delivery glue, `WorkerPresenceStore` |
| `transport/polling-adapter` | `xa-mass-transport-polling` | Pull/polling worker transport |
| `transport/websocket-adapter` | `xa-mass-transport-websocket` | WebSocket transport |
| `transport/socket-adapter` | `xa-mass-transport-socket` | Socket transport |
| `xa-mass-engine` | `xa-mass-engine` | Lifecycle, matching, assignment, result, terminal convergence |
| `xa-mass-sdk-api` | `xa-mass-sdk-api` | Stable SDK-facing contracts |
| `xa-mass-sdk` | `xa-mass-sdk` | Embedding entry and runtime composition for JVM callers |
| `xa-mass-testing` | `xa-mass-testing` | Perf load harnesses, SDK transport harness, chaos probes |
| `xa-mass-worker-pack` | `xa-mass-worker-pack` | Builtin workers, sample clients, launchers |
| `xa-mass-server` | `xa-mass-server` | Boot entry, HTTP controllers, control console, frontend shell |

### Engine Internals

Engine entry: `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`

Key classes:
- `TaskManager` — engine-internal orchestration facade and composition root (not the default cross-module API)
- `TaskConcurrencyStrategy` (interface) / `LocalTaskConcurrencyCoordinator` (default impl) — task/message locking and coalesced progress reconciliation; `TaskManager` holds this as `TaskConcurrencyStrategy concurrencyCoordinator`
- `TaskCommandService` / `TaskQueryService` — shell/admin mutation and inspection flows; preferred cross-module entry
- `TaskResultIngestFacade` — transport/runtime result ingress entry point
- `TaskCompatibilityProjectionStore` — engine-internal owner for bounded `TaskDetailStore` projection residue
- `TaskWorkProjectionState` — engine-owned enums for work/attempt residue state; converts to storage projection types only at persistence boundaries
- `TaskProjectionStateAuditor` — explicit full-scan compatibility projection diagnostics; audit/diagnostic work only, not hot paths
- `WorkerManager` — worker management; cross-module callers that only need lookup should depend on `WorkerStorage` directly
- `RuleManager` — rule evaluation; cross-module callers that only need rule definitions should depend on `RuleStorage` directly

**Cross-module callers must not default to `TaskManager`.** Prefer `TaskCommandService`, `TaskQueryService`, `TaskResultIngestFacade`, `TaskEventService`, and runtime ports.

**`@CompatibilityProjectionOnly`** marks classes whose purpose is bounded projection residue. They must not be used on hot paths or as native runtime state owners.

### Transport Internals

- `adapterId + routeKey` is the delivery address in a multi-adapter runtime; `transportHint` is only a coarse family hint
- `TransportPacket` is the internal flat transport envelope
- Worker reachability truth lives in `WorkerPresenceStore` (transport-owned); engine consumes a reachability view and must not re-own transport online truth through worker heartbeat folding
- Engine must not take a direct dependency on `transport/transport_api`

### Security Wiring

Authorization is centralized in `xa-mass-server`:
- `ApiAuthInterceptor` resolves operator principals and calls `ApiAuthorizationService.requireOperatorRoutePermission(...)` (not `AuthorizationPolicy` directly — that interface is used internally by `ApiAuthorizationService`)
- `ApiAuthorizationService` centralizes deny-message mapping and structured deny logging
- `ApiRouteAuthorizationCatalog` centralizes operator route-to-permission declarations
- Task create paths stamp ownership metadata into `Task.sharedConfig._massSecurity`; read APIs strip this and expose it as `data.security`
- External worker HTTP routes check through the same policy via `ExternalWorkerApiController`

### Trace Contract

Canonical trace objects: `com.xa.mass.trace.sink.ExecutionEvent` and `ExecutionEventType`.

`ExecutionEventType` is the only stable event-name vocabulary. Do not maintain a parallel event-name registry elsewhere. Full contract: `doc/TRACE_CONTRACT.md`.

`TraceEventLogger` is the engine's canonical emitter. Key storage locations: `terminalReason` is stored in `attrs["terminalReason"]` (not `transition.reason`); message status dst is stored in `transition.dst`.

## Key Behavioral Contracts

- **Task/ingest split:** `POST /api/v1/tasks` creates the task shell; `contract` (`SESSION`/`BATCH`) is a **top-level** field on the request, NOT inside `executionSpec` — putting it inside `executionSpec` throws a rejection; `executionSpec` holds `workloadClass`, `batchSize`, `maxRuntimeSeconds`; work items append through `POST /api/v1/tasks/{taskId}/items`
- **`Task.contract`** (`SESSION` or `BATCH`) is the runtime contract truth; ingress form does not redefine lifecycle, terminal, or retry semantics
- **`Task.intakeStatus`** (`OPEN` or `SEALED`) is the append-window truth; `sealTask` is the contract-neutral close action for both `SESSION` and `BATCH`
- **Automatic terminal closure** only fires for `BATCH` tasks after intake is sealed and all work items are final; `SESSION` tasks do not auto-close
- **`eventCode`** is globally unique capability identity; declared on the batch append request, not on the task shell
- **`taskName`** is server-derived and persisted; callers do not provide it
- **`Task.workloadClass`** (`INTERACTIVE` or `BULK`) drives scheduling semantics; do not resolve from free-form `sharedConfig`
- **`Task.sharedConfig`** plus runtime ingress item payload / `payloadRef` are the generic payload boundaries; `target` is only a conventional key inside the item payload, not a model field
- **Worker matching** is task-level orchestration; do not reintroduce per-item matching on the hot path
- **Storage mode** defaults to in-memory; JDBC path is behind `mass.storage.mode=jdbc-h2` or `mass.storage.mode=jdbc-postgres`
- **`RETRY_BUDGET_EXHAUSTED` task terminal reason has no triggering policy** — `AllWorkFinalTaskTerminalPolicy` only emits `ALL_MESSAGES_SUCCEEDED`, `ALL_MESSAGES_FAILED`, and `MIXED_MESSAGE_RESULTS`; per-message retry exhaustion (`maxRetryCount`) ends with `ALL_MESSAGES_FAILED` + message `finalReason=RETRY_EXHAUSTED`

## Agent Contract (Hard Rules)

Use `AGENTS.md` as the single source for agent behavior, refactor discipline, rename discipline, and compatibility/convergence rules. Do not maintain a second summarized rule set here.

**Multi-file or core change planning must include:** scope, out-of-scope, files and symbols, alternatives considered, costs, test impact with classifications, risk, and verification steps.

## Testing Lanes

| Lane | Owner module | CI gate |
|---|---|---|
| `Scheduling Correctness` | `xa-mass-engine`, `xa-mass-server` | PR-required (`scheduling-core`, `server-scheduling-e2e`) |
| `Kernel Convergence` | `xa-mass-engine` | PR-required (`reactor-core`, `scheduling-core`) |
| `chaos-smokes` | `xa-mass-testing` | PR-required (`chaos-smokes`) |
| `cross-language black-box` | `xa-mass-server` | PR-required (`cross-language-blackbox`) |
| `perf` | `xa-mass-testing` | Scheduled/manual only (`perf-smokes.yml`) |

**Test retirement principle:** before adding a new test file, check if the scenario can be folded into an existing test with one extra assertion. New files are justified only when the Spring context setup is materially different.

**Lifecycle or state-transition changes** require: `doc/STATE_MACHINE_BASELINE.md`, `doc/TRACE_CONTRACT.md`, and `doc/E2E_BASELINE.md` updated together with the code change.

**Change → minimum verification mapping** lives in `doc/TESTING_INDEX.md` Section 6.

Test artifacts output to:
- `xa-mass-testing/target/perf-reports/`
- `xa-mass-testing/target/concurrency-reports/`
- `xa-mass-testing/target/chaos-reports/`

## Key Document Index

| Topic | Document |
|---|---|
| Default testing entry (layers, CI truth, minimum verification) | `doc/TESTING_INDEX.md` |
| Startup, smoke, regression commands | `doc/VERIFIED_RUNBOOK.md` |
| Lifecycle state machines | `doc/STATE_MACHINE_BASELINE.md` |
| Infra placement rules | `doc/INFRA_TRUTH_LAYERS.md` |
| Trace contract | `doc/TRACE_CONTRACT.md` |
| E2E baseline | `doc/E2E_BASELINE.md` |
| HTTP API contracts | `doc/INTERNAL_API_REFERENCE.md` |
| Transport boundary | `transport/TRANSPORT_BOUNDARY_BASELINE.md` |
| Engine details | `xa-mass-engine/README.md` |
| Testing module details | `xa-mass-testing/README.md` |
| Known gaps | `doc/CURRENT_GAPS.md` |
| Deprecation index | `DEPRECATION_LEDGER.md` |
| External worker onboarding | `doc/EXTERNAL_WORKER_QUICKSTART.md` |
| DB storage principles | `doc/DB_STORAGE_PRINCIPLES.md` |
