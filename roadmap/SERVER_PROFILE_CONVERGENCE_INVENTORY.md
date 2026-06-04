# Server Profile Convergence Inventory

Status: current code inventory for `SERVER_PROFILE_CONVERGENCE_ROADMAP.md`.

## Profile Sites

| Site | Current Use | Classification | Target |
| --- | --- | --- | --- |
| `xa-mass-server/src/main/resources/application.yml` | declares `spring.profiles.default=dev`; base defaults are memory storage/runtime/transport | server bootstrap | current default owner |
| `xa-mass-server/src/main/resources/application-local.yml` | deleted during implementation | profile residue | removed |
| `xa-mass-server/src/main/resources/application-dev.yml` | runnable server shape, but depends on optional backend overlay profiles for H2/Redis/Postgres | server bootstrap | own complete developer shape: H2 control-plane storage plus memory runtime/transport |
| `xa-mass-server/src/main/resources/application-prod.yml` | production-like resource sizing only | server bootstrap | own complete prod shape: SQLite control-plane storage plus Redis runtime/transport |
| `xa-mass-server/src/main/resources/application-h2.yml` | legacy compose overlay for file H2 storage | compose residue | keep until compose startup is converged or explicitly broken |
| `xa-mass-server/src/main/resources/application-postgres.yml` | deleted during implementation | backend override residue | removed; PostgreSQL remains a manual property path |
| `xa-mass-server/src/main/resources/application-redis-runtime.yml` | legacy compose overlay for Redis runtime, transport delivery, and presence | compose residue | keep until compose startup is converged or explicitly broken |
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
| `README.md` | says compose runs `dev,redis-runtime,h2` | compose/runbook | leave compose cleanup out of current implementation scope |
| `xa-mass-testing/VERIFIED_RUNBOOK.md` | says compose runs `dev,redis-runtime,h2` | compose/runbook | leave compose cleanup out of current implementation scope |
| `xa-mass-server/README.md` | documents `dev` and `prod` normal profile meanings plus compose legacy notes | server docs | current server profile owner doc |
| `platform_infra/mass-runtime-redis/README.md` | mentions the server `redis-runtime` profile | runtime docs | update after overlay removal |

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
- H2 and Redis overlay files are compose legacy only. Removing them would change compose behavior while compose is out of scope.
