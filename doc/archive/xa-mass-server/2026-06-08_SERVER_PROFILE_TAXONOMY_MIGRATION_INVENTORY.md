# Archived: Server Profile Taxonomy Migration Inventory

Archived on: 2026-06-08

Current truth owner: `AGENTS.md`, `README.md`, `xa-mass-server/README.md`,
`doc/INFRA_TRUTH_LAYERS.md`, `compose.yaml`, and current server profile code.
This file is historical implementation context only. Do not use its
pre-migration `dev` / `prod` inventory rows as current behavior proof.

Status: implemented and archived with
`2026-06-08_SERVER_PROFILE_TAXONOMY_MIGRATION_ROADMAP.md`.

This inventory records the current `dev` / `prod` profile dependencies before
the migration to `memory-local` and `durable-local`. It is the mechanical input
for SPR-1 and SPR-2. Current code remains authoritative; update this inventory
when implementation changes the facts below.

## Transition Decision

- Use a direct breaking rename for in-repo callers.
- Do not keep long-lived `dev` or `prod` aliases.
- If an implementation slice needs a temporary alias to keep review size
  bounded, the alias may exist for that slice only and must not enter root or
  server README normal startup paths.
- Tests must not add new dependencies on old profile names.

## Current Profile Files

| Current file | Current meaning | Target |
| --- | --- | --- |
| `xa-mass-server/src/main/resources/application.yml` | Base defaults; currently `spring.profiles.default=dev`; blank operator auth fallback comment still says `dev -> dev-header, prod -> session` | Default becomes `durable-local`; comments must not describe profile names as auth semantics |
| `xa-mass-server/src/main/resources/application-dev.yml` | File-backed H2 control-plane, memory runtime, memory transport, dev-header fixture-friendly local shell | Rename to `application-memory-local.yml`; set `mass.auth.operator.mode` explicitly |
| `xa-mass-server/src/main/resources/application-prod.yml` | SQLite control-plane plus Redis runtime/transport local verification shell; not formal production | Rename to `application-durable-local.yml`; set `mass.auth.operator.mode=session` explicitly |
| `xa-mass-server/src/test/resources/application-dev.yml` | Test fixture override selecting memory storage and legacy sample bootstrap knobs | Rename or replace with `application-memory-local.yml` test resource if still needed |

## Main Source Dependencies

| Owner | Current usage | Classification | Target |
| --- | --- | --- | --- |
| `XaMassServerApplication` | `@Profile({"dev", "prod"})` gates runnable server beans | server assembly | Replace with `@Profile({"memory-local", "durable-local"})` |
| `XaMassServerApplication` | `isProdProfile()` drives storage/runtime/transport fail-closed checks | fail-closed infrastructure guard | Replace with `isDurableLocalProfile()` / `isDurableRuntimeProfile()` and `requireDurableRuntimeInfra(...)` |
| `XaMassServerApplication` | error messages say `prod requires ...` | fail-closed diagnostics | Rename to durable runtime/storage requirement messages |
| `ServerControlPlaneStoreConfiguration` | `@Profile({"dev", "prod"})` and `isProdProfile()` for storage fail-closed | server store assembly | Replace profile names and durable storage guard |
| `ServerControlPlaneMigrationConfiguration` | `@Profile({"dev", "prod"})` | server migration assembly | Replace with new profile names |
| `ControlPlaneSeedImportConfiguration` | `@Profile({"dev", "prod"})`; raw secret policy uses `Profiles.of("prod")` | seed secret policy | Replace profile names; raw secret allowance must use explicit local/sample seed policy, not old prod name |
| `CatalogConfiguration` | `@Profile("!dev & !prod")` fallback catalog | default catalog fallback guard | Replace with `!memory-local & !durable-local` and keep fallback excluded from runnable profiles |
| `TestDevBootstrapConfiguration` | test `@Profile("dev")` | test fixture | Replace with `memory-local` or a narrowly named fixture profile if still needed |
| `OperatorAuthProperties` | blank auth mode maps `prod -> session`, otherwise `dev-header`; property `allow-unsafe-dev-header-in-prod` | auth trust default | New profile files set auth mode explicitly; remove prod wording and do not make profile name imply dev-header |
| `OperatorAuthReadinessGuard` / `OperatorSessionService` | use `OperatorAuthProperties.isProdProfile()` | auth readiness/cookie security | Replace with explicit session/durable-local semantics |

## Test Dependencies

| Current usage | Classification | Target |
| --- | --- | --- |
| Many server E2E tests use `@ActiveProfiles("dev")` | test harness using current lightweight local profile | Replace with `@ActiveProfiles("memory-local")`; trusted-auth proof names must not contain `dev` |
| `XaMassServerApplicationTransportRuntimeConfigTest` asserts `prod requires ...` | focused infra guard proof | Rename assertions to durable-local guard wording |
| `ServerControlPlaneStoreConfigurationTest` asserts prod storage fail-closed | focused store guard proof | Rename to durable-local storage guard |
| `ServerMainSourceArchitectureGuardTest` reads `application-dev.yml` / `application-prod.yml` and asserts `@Profile({"dev", "prod"})` | architecture guard | Update to new files/profile names and add residue guard against old names |
| Redis-backed E2E tests currently use `@ActiveProfiles("dev")` with Redis property overrides | Redis runtime proof, not profile taxonomy proof | Convert to `memory-local` or create dedicated `durable-local` context proof |

## External Docs And Run Commands

| File | Current usage | Target |
| --- | --- | --- |
| `compose.yaml` | starts server with `-Dspring.profiles.active=prod` | use `durable-local` |
| `README.md` | compose text says `prod` uses SQLite + Redis | use `durable-local`; formal production naming remains deferred |
| `xa-mass-server/README.md` | describes `dev` / `prod`, prod fail-closed, and prod auth override | replace with `memory-local` / `durable-local` and explicit auth mode |
| `doc/INFRA_TRUTH_LAYERS.md` | may use `dev` / `prod` as infra shorthand | update if active current truth |
| `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` | may mention profile/seed boundary | update if profile names are referenced |
| `integrations` and `sdk` docs | may mention server `dev` / `prod` startup | update active startup references |

## Seed Field Residue

- `devOnly` exists in checked-in seed JSON and `ControlPlaneSeedCatalog`.
- This roadmap keeps the field name for the first profile taxonomy migration.
- Target meaning after migration: fixture/local/sample-only, not profile-`dev`
  trust.
- A later follow-up should decide whether to rename it to `fixtureOnly`,
  `localOnly`, or `sampleOnly`.

## Proof Requirements

- New profile files must explicitly set `mass.auth.operator.mode`.
- `memory-local` startup proof must show H2 control-plane plus memory
  runtime/transport assembly.
- `durable-local` must have fail-fast proof for invalid/missing/unavailable
  Redis runtime/transport.
- `durable-local` completion requires one positive Redis-backed startup/context
  proof. Completed verification used `ServerDurableLocalProfileContextTest`
  with SQLite control-plane storage plus Redis runtime/transport initialization.
