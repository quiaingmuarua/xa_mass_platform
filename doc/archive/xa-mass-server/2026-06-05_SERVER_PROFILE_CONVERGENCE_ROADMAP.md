# Server Profile Convergence Roadmap

Archived on 2026-06-05 after server profile convergence landed and the old
`local`, `postgres`, `h2`, and `redis-runtime` overlay profile files were
removed.

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

Status: completed and archived server profile convergence roadmap.

## Current Code Observations

- `application.yml` now uses `spring.profiles.default=dev`; it no longer
  activates the old `local` profile.
- `XaMassServerApplication.main(...)` no longer writes
  `spring.profiles.active`. Explicit profiles from environment, JVM property,
  command-line arguments, or Spring config are not overwritten by server main.
- Server runtime beans, review materialization beans, diagnostics beans, and
  the embedded `MassSdkApplication` starter are now guarded by
  `@Profile({"dev", "prod"})`.
- Current backend selectors are property-driven:
  - `mass.storage.mode=memory | jdbc-h2 | jdbc-postgres | jdbc-sqlite`
  - `mass.runtime.mode=memory | redis`
  - `mass.transport.delivery.store=memory | redis`
  - `mass.transport.presence.store=memory | redis`
- Existing profile files now expose only the runnable shapes `dev` and `prod`.
  Removed backend/profile residue: `local`, `postgres`, `h2`, and
  `redis-runtime`.
- `compose.yaml` now runs the packaged server jar with `prod`, Redis
  namespaces, and SQLite control-plane storage.
- Spring Boot E2E tests start the server through
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `@ActiveProfiles("dev")`.
  Those servers exit because the JUnit-managed Spring context closes, not
  because the `dev` or `local` profile owns a timed shutdown contract.
- `mass-storage-jdbc` now supports H2, PostgreSQL, and SQLite dialects. SQLite
  has focused task shell, lifecycle query, rule storage, principal persistence,
  and server-local review materialization coverage.

## Owner Review

Server profile ownership belongs to `xa-mass-server` bootstrap and runbook
documentation. Profiles should describe an operator/developer runnable shape,
not redefine kernel runtime semantics.

Control-plane persistence belongs to `platform_infra/mass-storage-jdbc`.
SQLite may be added there as another JDBC control-plane adapter for the
existing storage surfaces, but it must not own runtime queues, leases, worker
presence, dispatch gates, worker locks, or trace-shaped history.

Runtime backend selection belongs to `platform_infra/mass-runtime-memory` and
`platform_infra/mass-runtime-redis`, selected by server/SDK assembly. Redis is
runtime state, not a replacement for control-plane storage. Redis is also an
inspectable offline-analysis surface for runtime structures; runtime state does
not need to be copied into SQLite just to make it observable.

Trace/audit belongs to the trace layer. Future trace data may be queued and
written into a database by a trace-owned pipeline, but that is a separate
roadmap. Profile convergence must not make SQLite the trace owner or add
trace-shaped DB writes as part of prod bootstrap.

`prod` is an incremental production baseline, not a finished production HA
claim. The first owner goal is observability and offline analysis: control-plane
truth must be visible in SQLite, runtime truth must be visible in Redis, and the
server should not hide all operational state inside process memory. This does
not mean every layer writes to the same database.

Test server lifetime belongs to the test harness. Do not infer "local profile
exits" from `@SpringBootTest`; the test context exits because JUnit closes it.
This roadmap does not add a `local` profile or a profile-owned timed shutdown
path.

## Boundary Decision

Converge the public server profile matrix to two top-level profiles:

| Profile | Intended Use | Control-plane Storage | Runtime State | Lifetime |
| --- | --- | --- | --- | --- |
| `dev` | normal developer server | file H2 | memory | resident until process stop |
| `prod` | inspectable single-node production baseline | SQLite | Redis | resident until process stop |

The old `local` runnable-shape ambiguity is removed. Backend properties remain
overrideable for tests and manual diagnosis, but operators do not need to
compose backend overlay profiles for normal paths. The old `h2` and
`redis-runtime` overlays were removed; compose now uses `prod`.

## Target Shape

- No-arg `XaMassServerApplication` starts a developer-useful resident server
  through `dev`; `application.yml` and `XaMassServerApplication.main(...)` agree
  on that default.
- Tests keep using `@SpringBootTest` context shutdown and per-test dynamic
  properties. They are not represented as a server runtime profile.
- `dev` uses file-backed H2 for control-plane storage and memory runtime for
  fast interactive development.
- `prod` uses SQLite for control-plane storage and Redis for runtime work,
  result, worker registry, transport delivery, and transport presence. This is
  intentionally not pure memory because operators should be able to inspect the
  stored structures, run scripts, and perform offline analysis after process
  restart.
- SQLite covers the current JDBC-backed control-plane and review
  materialization surfaces first: task shell, rule storage, principal storage,
  and server-local review rows. Worker topology/declaration persistence is not
  a prerequisite for this profile convergence roadmap.
- SQLite support is first-cut and may be destructive with respect to existing
  SQLite files. Do not preserve historical SQLite data during schema changes in
  this roadmap.

## Non-Goals

- No change to Task, Worker, Scheduling Plane, result convergence, or transport
  protocol semantics.
- No JDBC ownership of runtime queues, active leases, retry indexes, worker
  locks, dispatch gates, worker presence churn, or high-volume attempt history.
- No trace DB pipeline in this roadmap. Trace remains a separate infra layer;
  queue-to-DB trace materialization can be designed later under trace ownership.
- No requirement to persist every control-plane declaration in SQLite in the
  first slice. Existing in-memory worker declarations, worker group records, and
  node-group binding records may remain follow-up persistence hardening.
- No production HA story for SQLite. The first prod slice is an inspectable
  single-node baseline, not a clustered production deployment contract.
- No Docker/compose startup convergence. Local `prod` startup may fail when
  Redis is missing; the requirement is a clear operator-visible failure, not an
  auto-provisioned Redis path.
- No compatibility obligation for the old `postgres` profile; it is removed.
  The remaining `h2` and `redis-runtime` profile files are compose legacy only
  while compose startup remains out of scope.
- No historical SQLite migration/backfill guarantee. Existing SQLite files may
  be reset during early schema evolution.

## Do Not Start With

Implementation started by making `dev` and `prod` selection explicit, making
`dev` fully assemble, and moving `prod` to SQLite plus Redis only after both
backends existed. The retired `local`, `postgres`, `h2`, and `redis-runtime`
profile overlays are now removed.

## SP-0 Inventory And Decision Check

Scope:

- Inventory every `@Profile("dev")`, `@ActiveProfiles("dev")`,
  `spring.profiles.active`, `application-*.yml`, and compose/runbook reference.
- Classify each site as server bootstrap, test harness, backend override,
  sample/demo bootstrap, or documentation.
- Confirm no-arg server startup defaults to `dev`; the code and
  `application.yml` must agree.

Acceptance:

- Inventory lists all production and test profile sites separately.
- The roadmap owner decision is confirmed or updated with the chosen no-arg
  default.
- No code behavior changes are made in this slice.

Verification candidates:

```bash
rg -n "@Profile|@ActiveProfiles|spring.profiles.active|application-(local|dev|prod|h2|postgres|redis-runtime)|redis-runtime|jdbc-h2|jdbc-postgres" xa-mass-server compose.yaml README.md xa-mass-testing doc platform_infra
```

## SP-1 Server Profile Assembly

Scope:

- Replace `@Profile("dev")` runnable-server guards with an allowlist that
  covers the intended runnable profiles, `dev || prod`, while keeping test-only
  bootstrap classes test-scoped.
- Replace the current system-property-only defaulting with Spring-compatible
  default handling. Explicit profiles from `-Dspring.profiles.active`,
  `SPRING_PROFILES_ACTIVE`, application arguments, or normal Spring config must
  not be overwritten by `XaMassServerApplication.main(...)`; no-arg startup
  should still resolve to `dev`.
- Make `application-dev.yml` and `application-prod.yml` own the full backend
  shape rather than depending on backend overlay profiles.
- Remove `application-local.yml` as a runtime profile after test/docs no longer
  depend on it.
- Keep sample workers off by default unless a dev-only property explicitly
  enables them.

Acceptance:

- Starting with only `dev` reaches a complete Spring context.
- Selecting `prod` is not overwritten by no-arg/default profile logic. Full
  prod context completion is accepted in SP-4 after SQLite storage and Redis
  runtime assembly are implemented.
- `dev` does not require Redis.
- Before SP-4, `prod` either assembles the profiles already implemented or
  fails fast with a clear missing-storage/runtime configuration error; it must
  not silently fall back to `dev` or memory-only operation.
- Test-owned context shutdown remains test-owned and is not described as
  profile-owned shutdown.

Verification candidates:

```bash
./mvnw -pl xa-mass-server -am -Dtest=CleanServerStartupIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl xa-mass-server -am -Dtest=XaMassServerApplicationTransportRuntimeConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## SP-2 Dev Becomes H2 Plus Memory Runtime

Scope:

- Move the H2 file-backed storage settings into `application-dev.yml`.
- Keep `mass.runtime.mode=memory`, transport delivery store `memory`, and
  transport presence store `memory` in `dev`.
- Update docs and runbook language so `dev` means resident developer server
  with H2 control-plane persistence and memory runtime.

Acceptance:

- `-Dspring.profiles.active=dev` writes control-plane task/rule/principal
  truth through H2 without also activating `h2`.
- Runtime work/result and worker registry remain memory-backed under `dev`.
- Spring tests using `@ActiveProfiles("dev")` keep isolated H2 properties or
  dynamic test properties; they must not write to the shared developer H2 file
  unless a test intentionally verifies that path.
- Existing H2 external-worker polling proof still passes with isolated H2 test
  properties.

Verification candidates:

```bash
./mvnw -pl xa-mass-server -am -Dtest=H2ExternalWorkerPollingApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl platform_infra/mass-storage-jdbc -am -Dtest=JdbcStorageH2Test -Dsurefire.failIfNoSpecifiedTests=false test
```

## SP-3 Add SQLite Control-plane Storage

Scope:

- Add SQLite JDBC runtime dependency and Flyway SQLite support if required by
  the selected Flyway version.
- Add `JdbcStorageMode.JDBC_SQLITE`.
- Add `SQLiteJdbcDialect` for task and rule upsert statements, and update
  `JdbcSubmitterRegistry` principal upsert handling for SQLite.
- Add or adapt SQLite-compatible schema handling. Because this roadmap has no
  historical SQLite migration obligation, the first implementation may use
  clean file creation/reset semantics for prod-like startup.
- Fix current JDBC schema/query mismatches that block SQLite parity instead of
  carrying them forward. Known examples to verify are `xa_task.create_time`
  usage in paged task listing and server-local review-store ordering syntax
  such as `NULLS LAST`.
- Keep server-local review materialization as storage/read-model material, not
  trace truth. SQLite may store review rows that the server already materializes,
  but trace event history and trace DB ingestion remain out of scope.
- Add storage contract tests for SQLite task shell, rule storage, and principal
  persistence.
- Add SQLite coverage for server-local review materialization only if the
  shared JDBC runtime path is used there under `prod`.

Acceptance:

- `mass.storage.mode=jdbc-sqlite` creates and uses a SQLite DB file.
- Task shell, rule, and principal persistence match the existing JDBC storage
  contract.
- Existing JDBC review materialization queries that run under `prod` are
  SQLite-compatible, or are explicitly kept off the prod path with a documented
  owner reason.
- SQLite storage tests do not require Docker.
- SQLite does not introduce worker runtime registry or hot-path runtime tables.
- SQLite does not introduce trace/audit tables or trace queue ingestion.

Verification candidates:

```bash
./mvnw -pl platform_infra/mass-storage-jdbc -am -Dtest=*SQLite*,JdbcStorageH2Test -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl xa-mass-server -am -Dtest=*SQLite* -Dsurefire.failIfNoSpecifiedTests=false test
```

## SP-4 Prod Becomes SQLite Plus Redis

Scope:

- Move prod backend selection into `application-prod.yml`:
  - `mass.storage.mode=jdbc-sqlite`
  - SQLite file path under `./data/xa-mass-sqlite/xa_mass.db` or another
    documented runtime path
  - `mass.runtime.mode=redis`
  - `mass.transport.delivery.store=redis`
  - `mass.transport.presence.store=redis`
  - fixed or externally supplied `mass.transport.node-id`
- Keep Redis host/port/password/database externally overrideable.
- Document the operator-visible SQLite file path and Redis namespaces so the
  data structures can be inspected or analyzed by local scripts.
- Keep trace/audit as a separate layer. SQLite and Redis improve current
  inspectability, but they do not become high-volume attempt history or trace
  ownership. A future trace-owned queue-to-DB writer may be added separately;
  this roadmap only prevents profile convergence from blocking that direction.
- Do not update `compose.yaml` in this roadmap. Docker startup is not part of
  the first profile convergence target.

Acceptance:

- `-Dspring.profiles.active=prod` no longer requires `redis-runtime` or `h2`.
- Local `prod` startup without Redis fails fast with a clear Redis connection or
  configuration error.
- The prod profile exposes deterministic storage/runtime locations or
  namespaces suitable for local inspection and script-based analysis.
- Redis runtime contract tests and representative server Redis E2E still pass.

Verification candidates:

```bash
./mvnw -pl platform_infra/mass-runtime-redis -am test
./mvnw -pl xa-mass-server -am -Dtest=TaskApiDelayedWorkerAvailabilityRedisRuntimeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl xa-mass-server -am -DskipTests package
```

## SP-5 Remove Overlay Profile Residue

Scope:

- Remove or archive `application-h2.yml` and `application-redis-runtime.yml`
  after all non-compose in-repo callers and docs stop depending on them. Leave
  compose cleanup for a later compose-specific decision if needed.
- Remove `application-local.yml` once `application.yml` and main startup default
  to `dev` and tests do not reference `local`.
- Remove `application-postgres.yml`; PostgreSQL remains available only through
  explicit `mass.storage.mode=jdbc-postgres` and JDBC properties.
- Update `README.md`, `xa-mass-server/README.md`,
  `xa-mass-testing/VERIFIED_RUNBOOK.md`, and module READMEs that mention the
  old profile combinations.
- Add a source/documentation guard if profile drift has been a repeated issue.

Acceptance:

- `rg` finds no active non-compose runbook or server test dependency on
  `local`, `dev,h2`, `dev,postgres`, `dev,redis-runtime`, `h2`, or
  `redis-runtime`.
- Remaining backend selection docs describe properties, not required overlay
  profile combinations.
- The two profile meanings are documented in one server-owned place.

Verification candidates:

```bash
rg -n "application-local|spring.profiles.active=local|@ActiveProfiles\\(\"local\"\\)|dev,h2|dev,postgres|dev,redis-runtime|application-h2|application-postgres|application-redis-runtime|redis-runtime" README.md xa-mass-server xa-mass-testing doc platform_infra
./mvnw -pl xa-mass-server -am -Dtest=ServerMainSourceArchitectureGuardTest,ServerMainlineE2eArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Suggested Implementation Order

1. SP-0: inventory and confirm no-arg default.
2. SP-1: make `dev/prod` selectable without default-profile drift, and make
   `dev` assemble as the complete developer server.
3. SP-2: make `dev` own H2 + memory runtime.
4. SP-3: add SQLite storage support in `mass-storage-jdbc`.
5. SP-4: make `prod` own SQLite + Redis, document inspectable paths/namespaces,
   and document the local Redis requirement.
6. SP-5: remove overlay profile residue and add drift guards.

## Open Decisions

- PostgreSQL now remains as a manually supported storage property path through
  `mass.storage.mode=jdbc-postgres`; the `postgres` overlay profile is removed.
- `application-h2.yml` and `application-redis-runtime.yml` were removed.
  Compose now runs `prod` with SQLite plus Redis.

## Verification Summary

Minimum roadmap completion signal:

```bash
./mvnw -pl platform_infra/mass-storage-jdbc -am -Dtest=JdbcStorageH2Test,*SQLite* -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl platform_infra/mass-runtime-redis -am test
./mvnw -pl xa-mass-server -am -Dtest=CleanServerStartupIntegrationTest,H2ExternalWorkerPollingApiIntegrationTest,TaskApiDelayedWorkerAvailabilityRedisRuntimeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl xa-mass-server -am -DskipTests package
```
