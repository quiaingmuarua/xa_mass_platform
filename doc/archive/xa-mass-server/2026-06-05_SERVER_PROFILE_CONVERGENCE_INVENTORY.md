# Server Profile Convergence Inventory

Archived on 2026-06-05 with the server profile convergence roadmap.

Current truth owners:

- `xa-mass-server/README.md` for server profile meanings and run commands.
- `xa-mass-server/src/main/resources/application-dev.yml` for the normal
  developer profile.
- `xa-mass-server/src/main/resources/application-prod.yml` for the
  production-like SQLite plus Redis profile.
- `compose.yaml` for local distributed verification wiring.

This document is historical context only. Do not use it as proof of current
implementation behavior; verify against current code, tests, owner READMEs,
and active profile config.

Status: archived code inventory for server profile convergence.

## Profile Sites

| Site | Current Use | Classification | Target |
| --- | --- | --- | --- |
| `xa-mass-server/src/main/resources/application.yml` | declares `spring.profiles.default=dev`; base defaults are memory storage/runtime/transport | server bootstrap | current default owner |
| `xa-mass-server/src/main/resources/application-local.yml` | deleted during implementation | profile residue | removed |
| `xa-mass-server/src/main/resources/application-dev.yml` | runnable server shape, but depends on optional backend overlay profiles for H2/Redis/Postgres | server bootstrap | own complete developer shape: H2 control-plane storage plus memory runtime/transport |
| `xa-mass-server/src/main/resources/application-prod.yml` | production-like resource sizing only | server bootstrap | own complete prod shape: SQLite control-plane storage plus Redis runtime/transport |
| `xa-mass-server/src/main/resources/application-h2.yml` | deleted during implementation | compose residue | removed; compose now uses `prod` plus SQLite |
| `xa-mass-server/src/main/resources/application-postgres.yml` | deleted during implementation | backend override residue | removed; PostgreSQL remains a manual property path |
| `xa-mass-server/src/main/resources/application-redis-runtime.yml` | deleted during implementation | compose residue | removed; compose now uses `prod` plus Redis |
| `XaMassServerApplication.main(...)` | starts Spring without setting `spring.profiles.active`; logs effective active/default profiles | server bootstrap | current default owner |
| `XaMassServerApplication` `@Profile({"dev", "prod"})` beans | gates JDBC storage, review materialization, runtime, embedded SDK app, starters, catalog, principal directory, diagnostics | server bootstrap | current runnable server profile guard |
| `ControlConsoleScenarioBootstrapConfiguration` `@Profile("dev")` | enables dev control-console sample bootstrap | sample/dev bootstrap | stay dev-only unless a property explicitly enables it |
| `TestDevBootstrapConfiguration` `@Profile("dev")` | test bootstrap support | test harness | keep test-scoped |
| Worker-pack sample starter/controller `@Profile("dev")` | sample worker/demo surfaces | sample/dev bootstrap | keep dev-only unless explicitly enabled |

## Test Harness

| Site | Current Use | Classification | Target |
| --- | --- | --- | --- |
| Server E2E `@ActiveProfiles("dev")` | starts JUnit-managed Spring Boot contexts | test harness | remain test-owned shutdown; use isolated dynamic properties where storage is file-backed |
| H2/Postgres server tests | override `mass.storage.mode` and JDBC URL through dynamic properties | test fixture | keep property override path, not overlay profile dependency |
| Redis runtime server tests | override `mass.runtime.mode`, transport delivery, and presence properties directly | test fixture | keep runtime property override path |
| `xa-mass-server/src/test/resources/application-dev.yml` | test profile overrides | test fixture | keep isolated test defaults; do not write shared developer H2 files accidentally |

## Documentation And Runbooks

| Site | Current Use | Classification | Target |
| --- | --- | --- | --- |
| `README.md` | documents compose running `prod` | compose/runbook | current compose entry |
| `xa-mass-testing/VERIFIED_RUNBOOK.md` | documents compose running `prod` | compose/runbook | current compose entry |
| `xa-mass-server/README.md` | documents `dev` and `prod` normal profile meanings plus compose legacy notes | server docs | current server profile owner doc |
| `platform_infra/mass-runtime-redis/README.md` | documents `prod` and property override Redis selection | runtime docs | current runtime docs |

## Storage Runtime Sites

| Site | Current Use | Classification | Target |
| --- | --- | --- | --- |
| `JdbcStorageMode` | supports `memory`, `jdbc-h2`, `jdbc-postgres`, `jdbc-sqlite` | storage adapter | current storage selector |
| `JdbcTaskShellStore` | stores task shell JSON plus indexed task columns including `create_time` | control-plane storage | current SQLite/H2/PostgreSQL task shell adapter |
| `JdbcRuleStorage` | stores rule definitions | control-plane storage | current SQLite/H2/PostgreSQL rule adapter |
| `JdbcSubmitterRegistry` | server-side principal persistence | control-plane storage | current SQLite/H2/PostgreSQL principal adapter |
| `JdbcTaskReviewStore` | server-local review/export materialization | storage/read-model | current SQLite-compatible review materialization store |
| Redis runtime stores | work/result runtime, worker registry, delivery, presence | runtime truth | remain Redis under `prod`; do not copy runtime truth into SQLite |
| Trace sinks/contracts | lifecycle/audit observation | trace/audit | no trace DB ingestion in this roadmap |

## Decisions

- Public server profiles converge to `dev` and `prod`; `local` is residue, not a runnable server shape.
- `dev` owns file H2 control-plane storage and memory runtime/transport.
- `prod` owns SQLite control-plane storage and Redis runtime/transport.
- PostgreSQL remains manually selectable through `mass.storage.mode=jdbc-postgres`; it no longer has a normal server profile overlay.
- Redis runtime is an inspectable offline-analysis surface; profile convergence does not require runtime-to-SQLite duplication.
- Trace queue-to-DB ingestion is a future trace-owned roadmap, not part of this implementation.
- H2 and Redis overlay files were removed. Compose now runs the normal `prod`
  profile with SQLite control-plane storage and Redis runtime.
