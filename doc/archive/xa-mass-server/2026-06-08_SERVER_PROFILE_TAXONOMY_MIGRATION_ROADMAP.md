# Archived: Server Profile Taxonomy Migration Roadmap

Archived on: 2026-06-08

Current truth owner: `AGENTS.md`, `README.md`, `xa-mass-server/README.md`,
`doc/INFRA_TRUTH_LAYERS.md`, `compose.yaml`, and current server profile code.
This file is historical implementation context only. Do not use its
pre-migration `dev` / `prod` observations as current behavior proof.

Status: implemented and archived.

## Summary

The current server profile names still use `dev` and `prod`. Those names now
hide too many independent decisions:

```text
runtime infrastructure
control-plane persistence
seed source
operator auth trust mode
test proof lane
```

This is becoming actively misleading. A new agent sees `dev` and tends to read
it as permission bypass, while `prod` currently means a local SQLite + Redis
verification shell rather than a formal production deployment profile.

Target profile names:

```text
memory-local
durable-local
```

The new names describe infrastructure and trust intent directly. They must not
change public API contracts, seed taxonomy, SDK/API-key requirements, worker
registration behavior, or operator command authorization.

This roadmap is a profile/config migration. It does not introduce a formal
production profile. It is related to
`SERVER_SDK_API_KEY_TRUST_BOOTSTRAP_ROADMAP.md`, but it has a different proof
surface: Spring profile resolution, startup guards, config files, docs, and
test profile wiring.

## Current Code Observations

- `xa-mass-server/src/main/resources/application.yml` currently defaults to
  `spring.profiles.default=dev`.
- `application-dev.yml` is the memory-local shape in practice:
  file-backed H2 control-plane storage, memory runtime, memory transport, and
  local sample convenience.
- `application-prod.yml` is the durable-local shape in practice:
  SQLite control-plane storage, Redis runtime/transport, explicit seed, and
  session-oriented operator auth.
- `XaMassServerApplication` uses `@Profile({"dev", "prod"})` across the main
  server assembly and uses `isProdProfile()` for fail-closed infrastructure
  checks.
- `OperatorAuthProperties` currently derives blank auth mode from profile:
  `dev -> dev-header`, `prod -> session`.
- `ControlPlaneSeedImportConfiguration` currently rejects `devOnly` API-key
  raw secrets when the `prod` profile is active.
- `ServerMainSourceArchitectureGuardTest` and related focused tests still look
  for `application-dev.yml`, `application-prod.yml`, and `prod requires ...`
  guard messages.
- Current owner docs already say runtime infra, auth trust mode, and seed source
  are separate axes. The profile names have not caught up with that direction.

## Owner Decision

Server profile assembly belongs to `xa-mass-server`.

SDK and integrations may consume the selected server profile through documented
run commands, but they must not define profile semantics or use profile names
as auth contracts.

`memory-local` and `durable-local` are assembly names only. They select
defaults for infra, seed, and auth mode. They do not own seedability, public API
contract, task/worker lifecycle truth, or SDK credential requirements.

## Boundary Decision

Profile names must encode deployment assembly, not permission trust shortcuts.

Use these profile semantics:

| Profile | Control Plane | Runtime | Seed Source | Operator Auth | Dev-Only Secrets |
| --- | --- | --- | --- | --- | --- |
| `memory-local` | file-backed H2 by default; memory override only for focused tests | memory | optional sample | explicit `dev-header` or `session`; any fixture default must remain an explicit auth mode | allowed only for sample/local seed |
| `durable-local` | SQLite current-schema storage | Redis | explicit local file with limited seed set | explicit `session` or `dev-header`; default should be `session` | rejected unless seed source is explicitly sample/local |

Interpretation rules:

1. `memory-local` does not mean every infra layer is memory. Its default
   control-plane path remains file-backed H2 so local state can be inspected;
   focused tests may override it to pure memory.
2. `memory-local` does not mean auth bypass.
3. `durable-local` is fixed as SQLite control-plane plus Redis
   runtime/transport. Invalid Redis properties, unavailable Redis backend, or
   selected Redis mode initialization failure are startup failures, not optional
   local degradation.
4. `durable-local` is the main local development and validation profile, not a
   reduced API/runtime mode.
5. `local` means current-schema storage and a limited local seed set. It does
   not mean public API, SDK/worker trust, runtime behavior, or endpoint behavior
   is intentionally downgraded.
6. This roadmap has no formal production profile. Production deployment naming
   is deferred until DB/schema ownership, external environment seed, and
   operational proof are ready.
7. `dev-header` is an operator-console fixture, not SDK/worker trust.
8. Trusted-auth proof may run on `memory-local` or `durable-local`, but it
   must use operator session + CSRF and API keys for SDK/worker-api calls.
9. Seed taxonomy is independent of profile. Profiles may change seed source and
   secret policy, not which resources are seedable.

## Target Shape

Target resource files:

```text
xa-mass-server/src/main/resources/application.yml
xa-mass-server/src/main/resources/application-memory-local.yml
xa-mass-server/src/main/resources/application-durable-local.yml
```

Target defaults:

```text
spring.profiles.default=durable-local

memory-local:
  control-plane = file-backed jdbc-h2 by default; memory override only for focused tests
  runtime = memory
  transport = memory
  seed = optional sample
  auth = explicit configured mode in profile file; no blank fallback

durable-local:
  control-plane = jdbc-sqlite
  runtime = redis
  transport = redis
  seed = explicit local file with limited local seed set
  auth = explicit session by default; dev-header only by explicit override
  redis unavailable / invalid redis config / selected redis mode cannot initialize = fail startup
```

Target Java naming:

```text
isDurableLocalProfile()
isMemoryLocalProfile()
isDurableRuntimeProfile()
requireDurableRuntimeInfra(...)
```

Do not keep `isProdProfile()` as the semantic owner after the migration. `prod`
is the old ambiguous name; future guards should mention the concrete
requirement, such as durable runtime infra or session-auth proof, not a prod
profile.

## Non-Goals

1. No change to task scheduling, worker matching, or runtime truth.
2. No change to seed taxonomy.
3. No WorkerGroup seed support.
4. No task/worker runtime state seed.
5. No change that allows task API keys to call operator lifecycle commands.
6. No broad auth redesign beyond profile trust semantics.
7. No production Docker/image contract in this roadmap.
8. No requirement to migrate every support test to session auth.
9. No PostgreSQL/external DB productization and no historical DB migration
   compatibility. The current durable path remains current-schema storage.

## Residual Decision: `devOnly` Seed Field

The first migration phase may keep the seed JSON field name `devOnly` to avoid
mixing profile taxonomy with seed-model churn.

After this roadmap, the intended meaning is fixture/local/sample-only, not
profile-`dev` trust. A follow-up should decide whether the field becomes
`fixtureOnly`, `localOnly`, or `sampleOnly`. That rename is useful residue work,
but it does not block the profile taxonomy migration.

## Risks And Mitigations

- Switching the default profile to `durable-local` breaks no-Redis no-arg
  startup. That is intentional for the main local validation path after SPR-1B.
  Mitigation: update runbooks and IDE configs in the same slice, and keep
  `memory-local` as the explicit lightweight fallback for no-Redis startup.
- Redis proof must not stop at property selection. `durable-local` completion
  requires a positive Redis-backed startup/context proof that initializes the
  selected Redis runtime/transport path, plus fail-fast proof for invalid
  properties or unavailable backend.
- Auth mode must not rely on the old blank fallback while old `dev/prod`
  profiles still exist. New profile files must set `mass.auth.operator.mode`
  explicitly from SPR-1A onward.

## Do Not Start With

Do not start by only renaming `application-dev.yml` and `application-prod.yml`.

That would leave the real bug intact: Java guards, auth defaults, docs, tests,
and future agents would still treat `dev` as a trust mode and `prod` as both
local persistent verification and production deployment.

Start with inventory and semantic decisions, then migrate profile assembly,
then remove old vocabulary.

## SPR-0: Inventory And Compatibility Decision

Goal: enumerate every current profile semantic dependency before changing
startup behavior.

Scope:

1. Inventory active uses of:
   - `application-dev.yml`
   - `application-prod.yml`
   - `spring.profiles.default=dev`
   - `@Profile({"dev", "prod"})`
   - `isProdProfile()`
   - `Profiles.of("prod")`
   - `prod requires ...`
   - `allow-unsafe-dev-header-in-prod`
   - docs that say `dev -> dev-header` or `prod -> session`
2. Classify each usage:
   - server assembly
   - fail-closed infrastructure guard
   - auth trust default
   - seed secret policy
   - test fixture
   - docs/runbook
   - archived/historical
3. Decide transition support:
   - prefer direct breaking rename for in-repo callers only
   - allow a temporary alias only if a slice would otherwise become
     unreviewably large
4. Record the chosen alias behavior in this roadmap before implementation.
5. If a temporary alias is used, constrain it:
   - it may exist for one slice only
   - it must not enter root/server README normal startup paths
   - tests must not add new dependencies on old names
   - SPR-5 must remove it through architecture/residue guard proof

Acceptance:

1. Every main-source `dev` / `prod` profile semantic usage is classified.
2. Test-only and archived references are separated from production wiring.
3. The transition rule for old profile names is explicit and does not promise
   permanent compatibility.
4. Alias policy defaults to no alias.

Suggested search:

```sh
rg -n "application-dev|application-prod|spring.profiles|@Profile|isProdProfile|Profiles.of\\(\"prod\"\\)|dev-header|allow-unsafe-dev-header-in-prod|prod requires|dev ->|prod ->" \
  xa-mass-server integrations sdk frontend doc roadmap
```

## SPR-1A: Add New Profiles With Explicit Startup Proof

Goal: introduce the new profile files and prove explicit profile startup before
changing the default profile.

Scope:

1. Add `application-memory-local.yml` with the current `application-dev.yml`
   shape, preserving file-backed H2 as the default control-plane storage.
2. Add `application-durable-local.yml` with the current local SQLite + Redis
   shape from `application-prod.yml`.
3. Do not add a formal production profile in this roadmap.
4. Keep old `application-dev.yml` and `application-prod.yml` only as
   transition aliases if SPR-0 permits a one-slice alias.
5. Do not change `spring.profiles.default` in this slice.
6. Set `mass.auth.operator.mode` explicitly in both new profile files.
   `memory-local` may choose `dev-header` or `session`, but the value must be
   written. `durable-local` defaults to explicit `session`.
7. Update config comments so they no longer say `dev -> dev-header` or
   `prod -> session`.

Acceptance:

1. Explicit `memory-local` context starts with memory runtime/transport and
   file-backed H2 control-plane storage by default.
2. `application-memory-local.yml` and `application-durable-local.yml` set
   `mass.auth.operator.mode` explicitly. New profiles must not rely on the old
   blank-mode `dev/prod` fallback during the transition.
3. Explicit `durable-local` has one positive Redis-backed startup/context proof
   using one of:
   - embedded/test Redis-compatible fixture
   - Testcontainers Redis-compatible fixture
   - documented conditional Redis integration test
   The slice must not be marked complete without positive Redis-backed proof.
4. Explicit `durable-local` fails with a named error when Redis properties are
   missing/invalid, Redis backend is unavailable, or selected Redis mode cannot
   initialize.
5. Existing `dev` / `prod` aliases, if retained, are documented as transitional
   and do not appear in target docs as normal profiles.

Verification candidates:

```sh
./mvnw -q -pl xa-mass-server -Dtest=CleanServerStartupIntegrationTest test

./mvnw -q -pl xa-mass-server -Dtest=XaMassServerApplicationTransportRuntimeConfigTest test

# Must be added in this slice before this command is valid.
./mvnw -q -pl xa-mass-server -Dtest=ServerProfileTaxonomyContextTest test
```

## SPR-1B: Switch Default Profile To `durable-local`

Goal: switch no-arg local startup only after the new explicit profile files have
startup proof.

Scope:

1. Change `spring.profiles.default` from `dev` to `durable-local`.
2. Update local run docs and IDE-facing examples to use the new profile names.
3. Keep this slice small so default-startup regressions are attributable to the
   default switch, not profile-file creation.

Acceptance:

1. No-arg local server startup resolves to `durable-local`.
2. Root/server README normal startup paths do not use `dev` or `prod` as active
   profile names.
3. Tests that intentionally activate the default profile prove the default path,
   not an old alias.
4. No-arg local server startup requires Redis after this slice.
5. `memory-local` is documented as the explicit lightweight fallback for
   no-Redis startup.
6. Runbook and IDE config updates land in the same slice as the default switch.

Verification candidates:

```sh
./mvnw -q -pl xa-mass-server -Dtest=CleanServerStartupIntegrationTest test
```

## SPR-2: Replace Profile Predicates And Fail-Closed Guards

Goal: move Java guard semantics from `prod` to explicit profile taxonomy.

Scope:

1. Replace `isProdProfile()` with explicit helpers:
   - `isDurableLocalProfile()`
   - `isMemoryLocalProfile()`
   - `isDurableRuntimeProfile()` where the decision is really about Redis or
     durable runtime infra
2. Replace `requireNonProdMode(...)` with a guard name that says what it means:
   fail under `durable-local` when Redis runtime/transport is missing,
   invalid, unavailable, or cannot initialize; use auth-mode-specific guards
   for session/dev-header decisions.
3. Update `OperatorAuthProperties` so blank mode resolution is not `dev/prod`
   based.
4. Update unsafe override naming:
   - old: `mass.auth.operator.allow-unsafe-dev-header-in-prod`
   - target: remove the prod wording; if a fixture override remains, name it
     as local fixture auth and keep it explicit
5. Update `ControlPlaneSeedImportConfiguration` so dev-only raw secret rejection
   is based on explicit seed-source classification, not `prod` alone. First
   version rules:
   - `memory-local`: `devOnly` rawSecret is allowed only for sample/local seed
     files.
   - `durable-local`: rawSecret is rejected unless the seed import request
     explicitly marks a sample/local fixture seed source.
   - checked-in scenario seed must not be the default `durable-local` seed.

Acceptance:

1. No main source method named `isProdProfile()` remains.
2. No main source fail-closed error says `prod requires ...`.
3. No profile name implicitly selects dev-header auth.
4. `durable-local + dev-header` can start only when explicitly configured
   and is documented as local fixture/debug, not trusted-auth proof.
5. `durable-local` cannot start when Redis properties are missing/invalid, the
   Redis backend is unavailable, or selected Redis mode cannot initialize.
6. `memory-local + session` can be tested as trusted-auth proof even though the
   runtime backend is memory.
7. `durable-local` does not allow checked-in scenario seed rawSecret material by
   default.

Verification candidates:

```sh
./mvnw -q -pl xa-mass-server -Dtest=OperatorAuthPropertiesTest,OperatorAuthReadinessGuardTest test

./mvnw -q -pl xa-mass-server -Dtest=ControlPlaneSeedImportConfigurationTest,ControlPlaneSeedImporterTest test
```

## SPR-3: Migrate Tests And Proof Labels

Goal: keep support tests cheap while stopping profile names from overstating
trust proof.

Scope:

1. Update tests that assert `application-dev.yml` / `application-prod.yml` file
   names to the new profile files.
2. Rename support tests or test data that read as production proof but still use
   fixture auth.
3. Add a focused trusted-auth startup/context proof for:
   - `memory-local + session`
   - `durable-local + session`
4. Keep broad controller tests free to use explicit `dev-header` support
   fixtures when auth/session behavior is not the subject.
5. Update test failure messages from `prod requires ...` to the new taxonomy.
6. Add startup-level proof using `ApplicationContextRunner` or `@SpringBootTest`
   for the profile matrix.

Acceptance:

1. Trusted-auth proof names do not contain `dev`.
2. Support fixture proof names explicitly mention `fixture-header` or support.
3. At least one memory-backed trusted-auth proof exists and uses session/CSRF +
   API keys, proving memory infra is not auth bypass.
4. Durable-local startup proof rejects missing/invalid Redis properties,
   unavailable Redis backend, and selected Redis mode initialization failure;
   it also proves SQLite current-schema control-plane wiring.
5. Profile context proof covers:
   - `memory-local` starts
   - `memory-local` and `durable-local` both set explicit auth mode
   - `durable-local` fails when Redis cannot initialize
   - `durable-local` has one positive Redis-backed startup/context proof
   - `durable-local + session` trusted-auth proof starts

Verification candidates:

```sh
./mvnw -q -pl xa-mass-server -Dtest=ServerMainSourceArchitectureGuardTest test

./mvnw -q -pl xa-mass-server -Dtest=ApiAuthInterceptorTest,AuthControllerTest test

# Must be added by this roadmap before this command is valid.
./mvnw -q -pl xa-mass-server -Dtest=ServerProfileTaxonomyContextTest test
```

## SPR-4: Update Docs, Runbooks, Compose, And Integrations

Goal: make the new names the only active vocabulary outside transition notes.

Scope:

1. Update `xa-mass-server/README.md`.
2. Update `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`.
3. Update `doc/INFRA_TRUTH_LAYERS.md` if it still uses `dev/prod` as semantic
   shorthand.
4. Update `SERVER_SDK_API_KEY_TRUST_BOOTSTRAP_ROADMAP.md` so it points to the
   new profile names and keeps axes as owner vocabulary.
5. Update compose/runbook commands that currently activate `prod`.
6. Update integration docs that mention server `dev` or `prod` startup.

Acceptance:

1. Active docs define:
   - `memory-local`
   - `durable-local`
2. Active docs no longer describe `dev` as the normal local profile or `prod` as
   the local SQLite + Redis profile.
3. Docs explicitly say `memory-local` may use `dev-header` only as a fixture and
   does not bypass SDK/worker API-key requirements.
4. Docs explicitly say `durable-local` is the main local development and
   validation profile, not a reduced API/runtime mode.
5. Docs explicitly say formal production deployment naming is deferred.

Suggested search:

```sh
rg -n "\\bdev\\b|\\bprod\\b|application-dev|application-prod|dev-header|prod-like" \
  README.md xa-mass-server doc integrations sdk roadmap
```

## SPR-5: Remove Old Profile Residue

Goal: retire `dev` and `prod` as active server profile names after callers move.

Scope:

1. Delete or archive transition alias files if SPR-0 kept them.
2. Remove active docs that present `dev` / `prod` as supported profile names.
3. Add source guards against:
   - new `@Profile({"dev", "prod"})`
   - new `isProdProfile()`
   - new `application-dev.yml` / `application-prod.yml` active references
   - new `allow-unsafe-dev-header-in-prod` production guard wording
4. Keep archived roadmap/doc references as historical only.
5. Confirm `devOnly` appears only as the deferred seed-field residue documented
   above, not as active profile taxonomy.

Acceptance:

1. `rg "@Profile.*dev|@Profile.*prod|isProdProfile|application-dev|application-prod|allow-unsafe-dev-header-in-prod" xa-mass-server/src/main xa-mass-server/src/main/resources` returns no active hits.
2. Old profile aliases cannot be used accidentally in normal startup.
3. Architecture/source guard fails if a future change reintroduces `dev` or
   `prod` as profile-semantic owners.
4. Temporary aliases, if ever introduced, are removed.

## Roadmap Completion Criteria

This roadmap is complete only when:

1. `memory-local` and `durable-local` are the only active server profile names.
2. No main-source guard or config comment uses `dev` or `prod` as trust
   semantics.
3. `durable-local` is fail-closed for SQLite current-schema control-plane
   wiring, Redis runtime/transport, and local seed policy.
4. `memory-local` can run trusted-auth proof with session/CSRF and API keys.
5. `durable-local` can run trusted-auth proof with session/CSRF and API keys.
6. Seed taxonomy remains unchanged and guarded.
7. SDK task producers and worker-api callers still use API keys regardless of
   selected profile.
8. PostgreSQL/external DB productization, historical migration support, and
   formal production profile naming remain deferred outside this roadmap.

## Related Roadmaps And Docs

- `roadmap/SERVER_SDK_API_KEY_TRUST_BOOTSTRAP_ROADMAP.md` owns trusted-auth
  bootstrap proof. This roadmap owns profile names and profile assembly.
- `roadmap/SERVER_OPERATOR_AUTH_PROD_TRUST_HARDENING_ROADMAP.md` is the prior
  auth hardening direction. It should be updated or archived once profile names
  stop using `prod`.
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` owns the global seed/API/SDK
  boundary rule and must stay aligned with this roadmap.
- `xa-mass-server/src/main/resources/db/schema/server-control-plane/README.md`
  owns seed/import taxonomy for server control-plane schema.

## Verification Candidates

Exact commands should be finalized per slice, but the initial proof set should
include:

```sh
./mvnw -q -pl xa-mass-server -Dtest=ServerMainSourceArchitectureGuardTest test

./mvnw -q -pl xa-mass-server -Dtest=XaMassServerApplicationTransportRuntimeConfigTest,ServerControlPlaneStoreConfigurationTest test

./mvnw -q -pl xa-mass-server -Dtest=ServerProfileTaxonomyContextTest test

./mvnw -q -pl xa-mass-server -Dtest=OperatorAuthPropertiesTest,OperatorAuthReadinessGuardTest,AuthControllerTest test

./mvnw -q -pl xa-mass-server -Dtest=ControlPlaneSeedImporterTest,ControlPlaneSeedImportConfigurationTest test
```

Residue scan after SPR-5:

```sh
rg -n "@Profile.*dev|@Profile.*prod|isProdProfile|Profiles.of\\(\"prod\"\\)|application-dev|application-prod|allow-unsafe-dev-header-in-prod|prod requires|dev ->|prod ->" \
  xa-mass-server/src/main xa-mass-server/src/main/resources xa-mass-server/README.md doc integrations sdk roadmap
```

Seed-field residue classification after SPR-5:

```sh
rg -n "\\bdevOnly\\b" xa-mass-server doc integrations sdk roadmap
```
