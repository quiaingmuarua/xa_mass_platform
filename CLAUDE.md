# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent Contract

[AGENTS.md](AGENTS.md) is the primary agent contract. Read it before changing any behavior.

Trust order: code > verified runtime behavior > AGENTS.md > active `doc/` baselines > module READMEs > roadmaps (after re-verification).

Archived documents are changelog-only. They do not prove current behavior.

## Build

Java 21 / Spring Boot 3.3.0 / Maven multi-module reactor.

```bash
# Compile all modules (skip tests)
./mvnw -DskipTests compile

# Package server jar (includes all upstream modules)
./mvnw -pl xa-mass-server -am -DskipTests package

# Full reactor build with tests
./mvnw package
```

Frontend (Vue 3 + Vite, managed by corepack/pnpm — run from `frontend/`):

```bash
corepack pnpm install
corepack pnpm build      # production build served by the backend at :8088
corepack pnpm dev        # mock-API dev server at :5174
corepack pnpm lint
corepack pnpm typecheck
corepack pnpm test:run
```

## Run

```bash
# Local distributed verification (requires pre-built jar)
./mvnw -pl xa-mass-server -am -DskipTests package
docker compose up redis server
```

Default ports: HTTP `8088`, WebSocket `18088`, Socket `18089`.

```bash
# Smoke check
curl -i http://127.0.0.1:8088/actuator/health
```

Optional dev scenario (against a running server):

```bash
node integrations/samples/dev/scenario/launch-workers.mjs
node integrations/samples/dev/scenario/launch-workers.mjs --register-only
```

See [xa-mass-testing/VERIFIED_RUNBOOK.md](xa-mass-testing/VERIFIED_RUNBOOK.md) for full startup, smoke, and reset commands.

## Tests

Run a focused test class:

```bash
./mvnw -pl <module> -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<TestClass> test
```

Core acceptance gates:

```bash
# Scheduling correctness (engine-level, PR gate)
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EngineSchedulingCoreSuite test

# Representative server E2E (PR gate)
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerSchedulingE2eSuite test

# Lifecycle and result convergence (PR gate)
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ServerLifecycleResultConvergenceSuite test
```

CI gates live in `.github/workflows/maven.yml`. Perf smokes are scheduled/manual only.

When adding tests: read [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md) Section 0 first. Prefer engine acceptance for lifecycle/kernel invariants; Boot-shell E2E for real wiring; chaos runners for distributed edges.

## Architecture

**Core primitives:** `Task + Worker + Scheduling Plane`

**Mainline execution path:**
```
Task shell -> item append -> runtime enqueue -> scheduling eligibility
  -> worker selection (WorkerGroup + matching) -> transport dispatch
  -> result convergence -> task state
```

**Kernel truth is split across:**
- `Task.contract` — public/runtime preset: `SESSION | BATCH`
- `Task.intakeStatus` — append window: `OPEN | SEALED`
- `xa-mass-task-runtime` — accepted backlog, scheduler discovery,
  claim/lease/retry/finality, retained final rows, and progress snapshots

**Infra truth layers** (from [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md)):
- Control-plane: SQLite (task/worker/rule shell storage) via `platform_infra/mass-storage-jdbc`
- Runtime truth: Redis (`platform_infra/mass-runtime-redis`) — queue, lease, counters, transport delivery, presence
- Trace/audit: async JSONL sink (`platform_infra/mass-trace-sink`) + DuckDB CLI (`xa-mass-trace`)

**Transport** is three explicit channels — task dispatch, result ingest, system events — with three current adapter types: polling, websocket, socket.

**Module ownership quick map:**
- `xa-mass-engine` — kernel orchestration: lifecycle, matching, assignment, result, terminal convergence
- `xa-mass-worker-runtime` — worker-plane lifecycle: WorkerGroup, AdapterNode, scheduling evidence, admission
- `transport/transport_runtime` — adapter routing, delivery/result-ingest glue
- `xa-mass-server` — Spring Boot host, HTTP controllers, auth/IAM, control console shell
- `sdk/xa-mass-java-sdk` — external Java SDK for task producers and external workers
- `sdk/xa-mass-embedded-sdk` — JVM embedding entry (`MassSdk`, `MassApplication`)
- `platform_infra/*` — runtime-api/memory/redis, storage-api/memory/jdbc, queue-primitives, trace-sink
- `xa-mass-testing` — perf smokes, SDK transport harnesses, chaos runners
- `integrations/xa-mass-worker-pack` — official worker capability pack and dev/E2E harness

Module source of truth: root `pom.xml`.

## Key Doc Pointers

| Topic | Where |
|---|---|
| Reading order for new sessions | [AGENTS.md](AGENTS.md) Section 1 |
| Task lifecycle rules | [doc/TASK_LIFECYCLE_BASELINE.md](doc/TASK_LIFECYCLE_BASELINE.md) |
| Infra placement (storage/runtime/trace) | [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md) |
| Testing layer truth and minimum verification | [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md) |
| Proof registry (authoritative invariant owners) | [doc/PROOF_REGISTRY.md](doc/PROOF_REGISTRY.md) |
| HTTP API contracts | [xa-mass-server/doc/INTERNAL_API_REFERENCE.md](xa-mass-server/doc/INTERNAL_API_REFERENCE.md) |
| Trace operator CLI | [xa-mass-trace/README.md](xa-mass-trace/README.md) |
| SDK/integrations boundary guard | [doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md](doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md) |
| Transport ownership | [transport/AGENTS.md](transport/AGENTS.md) |
| Active cross-module roadmaps | [roadmap/README.md](roadmap/README.md) |
