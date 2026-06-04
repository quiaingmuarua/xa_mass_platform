# Server Control-Plane Store Infra Convergence Roadmap

Status: proposed direction document; API-key lifecycle store execution is no
longer blocked by Submitter/Auth projection coupling.

## Current Code Observations

- `xa-mass-server` already treats task shell storage, task review
  materialization, engine runtime, transport delivery, and transport presence as
  selectable infrastructure.
- Server-owned IAM/API-key/session/usage stores have interfaces, but their
  production wiring is still implicit because concrete in-memory
  implementations are `@Component`s.
- `ApiKeyCredentialService` currently writes API-key lifecycle records to
  `ApiKeyCredentialStore` and separately projects credentials through
  `CredentialAuthProjectionWriter`.
- In JDBC storage mode, `JdbcSubmitterRegistry` can persist the auth projection
  in `xa_principal`, but `ApiKeyCredentialStore` remains memory-only. This can
  split credential truth after restart: auth projection reloads while API-key
  lifecycle validation/list/revoke state is lost.
- `InMemoryUserRolePermissionStore` seeds operator users and roles in code and
  is the only current backing for IAM mutations.
- `InMemoryApiUsageLedgerStore` is the only current backing for API usage
  evidence.
- `SyncTaskResultBridge` and `TaskSyncRequestSupervisor` also use in-process
  maps, but those are request-local runtime coordination surfaces, not
  control-plane storage gaps.

Detailed inventory:
`roadmap/SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_INVENTORY.md`.

Prerequisite satisfied:
`roadmap/SUBMITTER_AUTH_MODEL_CONVERGENCE_ROADMAP.md`.

API-key lifecycle projection writes now go through
`CredentialAuthProjectionWriter` instead of broad `SubmitterOperations`. Facade
rename, runtime scope behavior migration, and viewer-session principal type are
not prerequisites for this store roadmap unless they become schema inputs. This
roadmap should persist the projection-port model, not the historical
`Submitter*` resource facade write path.

API-key lifecycle JDBC schema must also honor the scope-representation decision
from `SUBMITTER_AUTH_MODEL_CONVERGENCE_ROADMAP.md`: wildcard, omitted, and
bounded project/event scopes must be distinguishable before scope fields are
persisted.

## Owner Review

Server IAM, API-key workflow, submitter-viewer sessions, and usage ledgers are
server product/control-plane concerns. They belong in `xa-mass-server` as
host-side stores and adapters.

`platform_infra` should not grow SDK/IAM/server API concepts just because these
stores need JDBC backing. `platform_infra/mass-storage-jdbc` may continue to
own generic task shell/rule/principal migration support, but richer server
API-key and IAM lifecycle stores should stay server-owned.

Server-owned JDBC store schema must also stay server-owned. Do not add
API-key, IAM, submitter-viewer session, or API-usage tables under
`platform_infra/mass-storage-jdbc` just because that module currently launches
Flyway for generic control-plane tables. Slice 0 must define the server-owned
migration execution mechanism before Slice 1 writes JDBC stores. The preferred
shape is a server-owned Flyway location such as
`classpath:db/migration/server-control-plane` executed by `xa-mass-server`
against the same `JdbcStorageRuntime` `DataSource`; an equivalent
server-owned migrator is acceptable. Either path must keep `platform_infra`
free of server API/IAM schema concepts.

`CredentialAuthProjectionWriter` is the SDK auth-projection write contract used
by API-key lifecycle code. `JdbcSubmitterRegistry` is the current host adapter
that can persist that projection and serve auth/directory reads in JDBC mode.
API-key lifecycle storage must converge with that projection; it must not
become an independent second credential truth that can approve, revoke, or
expire keys differently.

## Boundary Decision

Use one server-owned store assembly boundary:

- `mass.storage.mode=memory`: use in-memory server control-plane stores.
- `mass.storage.mode=jdbc-h2`, `jdbc-sqlite`, or `jdbc-postgres`: use JDBC
  server control-plane stores by default.
- Optional explicit overrides may exist only as startup/debug tools, for
  example `mass.server.control-plane.store=memory|jdbc`.
- Slice 0 must decide whether to implement that override immediately or derive
  only from `mass.storage.mode` for this roadmap. If implemented, the override
  must be logged as a debug/startup override and must not change public API
  behavior.

The backing implementation is selectable. The public server routes and SDK
contracts do not change.

## Target Shape

- Add a server configuration class that creates all server control-plane store
  beans explicitly.
- Remove component scanning from concrete in-memory store implementations.
- Add server-owned JDBC implementations for stable control-plane stores:
  `ApiKeyApplicationStore`, `ApiKeyCredentialStore`,
  `UserRolePermissionStore`, and `ApiUsageLedgerStore`.
- Add server-owned migrations/schema for those implementations. The schema
  owner is `xa-mass-server`, not `platform_infra/mass-storage-jdbc`.
- Add server-owned migration execution for those schemas. It may share the
  existing JDBC `DataSource`, but the migration resources and runner contract
  are server-owned.
- Keep `SubmitterViewerSessionStore` memory-only. If cross-process viewer
  sessions become necessary later, that is a runtime/Redis session decision,
  not a JDBC/control-plane store decision.
- Keep sync wait bridge and sync request counters memory-only.
- Keep SQLite as the normal prod control-plane DB and Redis as runtime truth.
- Do not add DB tables for runtime queues, active leases, worker online churn,
  dispatch/callback streams, trace events, or high-volume item history.

## Hard Rules

These are current server-stage owner rules, not only roadmap-local rules.
Future server roadmaps must follow the same constraints unless the owner docs
are deliberately changed first. Current truth owners:

- `AGENTS.md`
- `doc/INFRA_TRUTH_LAYERS.md`
- `xa-mass-server/README.md`

- Server API-key, IAM, and usage schemas belong to `xa-mass-server`, not
  `platform_infra`.
- Slice 0 must define server-owned migration execution before any JDBC
  API-key/IAM/usage store is added.
- API-key credential schema must distinguish omitted, wildcard, and bounded
  project/event scopes before it persists scope fields. Use explicit scope mode
  fields, an explicit wildcard sentinel, or an equivalent durable contract.
- This pre-release project does not maintain historical DB compatibility.
  Schema changes may require deleting/recreating the local/prod DB. Proof
  targets are clean DB creation, current-schema restart durability, and clear
  seed/import behavior, not upgrade migration from old local data.
- `SubmitterViewerSessionStore`, sync wait bridges, runtime queues, worker
  presence, trace events, and high-volume runtime history must not be persisted
  as SQLite/JDBC control-plane tables.

## Non-Goals

- Do not split a new server infra module.
- Do not move server IAM/API-key store contracts into `platform_infra`.
- Do not put server API-key, IAM, session, or usage schemas in
  `platform_infra/mass-storage-jdbc`.
- Do not replace SDK `SubmitterRegistry`, `AuthProvider`, or
  `PrincipalDirectory` contracts.
- Do not add compatibility aliases or duplicate active API-key lifecycle paths.
- Do not persist `SubmitterViewerSessionStore` in SQLite/JDBC.
- Do not persist sync wait futures, in-flight counters, runtime queues, leases,
  or worker presence in SQLite.
- Do not add commercial schema-history migration guarantees in this roadmap.
  Current local/prod DBs may be deleted and recreated when schema changes.
- Do not introduce Docker/compose requirements.

## Do Not Start With

Do not start by adding random JDBC tables for every in-memory map. First classify
which maps are stable control-plane truth and which are request-local runtime
state. Then create explicit server store assembly, then add JDBC adapters.

## Slice 0 - Inventory And Store Assembly Contract

Goal:

Classify every server store and introduce an explicit assembly point without
changing behavior.

Scope:

- Keep the inventory current.
- Add a `ServerControlPlaneStoreConfiguration` or equivalent server-owned
  configuration class.
- Move store bean creation from `@Component` scanning to explicit `@Bean`
  methods.
- Decide and document the effective mode rule:
  derive server store mode from `mass.storage.mode` only, or add
  `mass.server.control-plane.store` as a startup/debug override. This decision
  must be made before Slice 1 starts.
- Define server-owned migration execution for future server schemas. Slice 0
  may add the runner with no server-owned migrations yet, but Slice 1 must not
  invent the migration mechanism locally.
- Keep current effective implementations for IAM/API-key/session/usage in this
  slice: API-key, IAM, and usage still use memory stores even when
  `mass.storage.mode` is a JDBC mode; submitter-viewer sessions remain
  memory-only by design.
- Add a guard that concrete in-memory server control-plane stores are not
  component-selected.

Acceptance:

- No `InMemoryApiKeyApplicationStore`,
  `InMemoryApiKeyCredentialStore`, `InMemoryUserRolePermissionStore`,
  `InMemorySubmitterViewerSessionStore`, or
  `InMemoryApiUsageLedgerStore` class carries `@Component`.
- A Spring context/assembly test proves `dev` startup creates exactly one bean
  for each server control-plane store contract after removing `@Component`.
- Store selection is centralized in one server assembly class.
- Server-owned migration execution is documented and, if introduced in this
  slice, covered by a no-op/empty-location startup proof.
- The mode decision is recorded in this roadmap and the inventory.
- Focused auth/IAM tests still pass.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=ApiKeyControllerTest,ApiKeyApplicationControllerTest,IdentityAccessControllerTest,SubmitterViewerSessionControllerTest,ApiUsageControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Add a focused Spring assembly test, for example:

```text
ServerControlPlaneStoreConfigurationTest
```

## Slice 1 - JDBC API-Key Lifecycle Stores

Goal:

Make API-key application and API-key credential lifecycle truth durable and
consistent with `JdbcSubmitterRegistry`.

Scope:

- Add server-owned JDBC implementations for `ApiKeyApplicationStore` and
  `ApiKeyCredentialStore`.
- Add server-owned control-plane migration tables for application and
  credential records. Do not place these migrations in `platform_infra`.
- Store full records, including status, review metadata, expiry, revoke
  metadata, scopes, permissions, attributes, key prefix, and credential hash.
- Persist scope state with an explicit durable representation that distinguishes
  omitted, wildcard, and bounded project/event scopes. Do not serialize the
  current normalized empty list as the only representation for all three.
- Ensure `ApiKeyCredentialService.validateAuthenticatedPrincipal(...)` works
  after server restart when auth projection reloads from `xa_principal`.
- Decide whether `xa_principal` and API-key credential rows are written in the
  same transaction or through a clearly ordered best-effort sequence. Record the
  chosen failure behavior in tests.
- If a best-effort sequence is chosen, define the split-brain repair behavior:
  either roll back the lifecycle row when projection fails, or persist a visible
  projection-failed/repair-needed state that cannot authenticate as active.

Acceptance:

- Creating an API key in JDBC mode persists both API-key lifecycle row and
  submitter auth projection.
- Restarting the store/auth projection preserves authentication and
  `validateAuthenticatedPrincipal(...)`.
- Revocation, disabling by user, and expiry update both lifecycle store and
  submitter auth projection.
- A forced lifecycle/projection partial failure cannot leave a key that is
  authenticated by `SubmitterRegistry` but rejected or invisible to
  `ApiKeyCredentialStore` without a visible repair state.
- Duplicate principal and duplicate key constraints are enforced in JDBC.
- API-key credential rows include a scope representation that distinguishes
  omitted, wildcard, and bounded project/event scopes.
- H2 and SQLite pass the same store contract tests.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=JdbcSubmitterRegistryTest,ApiKeyControllerTest,ApiKeyApplicationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Add a new focused JDBC test, for example:

```text
JdbcApiKeyCredentialStoreTest
```

## Slice 2 - JDBC Operator IAM Store

Goal:

Make operator users, roles, and role bindings durable while preserving the
current built-in bootstrap defaults.

Scope:

- Add a JDBC implementation for `UserRolePermissionStore`.
- Add server-owned control-plane migration tables for users, roles, and
  user-role bindings, or an equivalent normalized-plus-JSON layout. Do not
  place these migrations in `platform_infra`.
- Define bootstrap behavior for default operator users/roles:
  seed-if-empty is acceptable; overwriting existing operator data is not.
- Keep `DefaultOperatorPrincipalDirectory` dependent only on
  `UserRolePermissionStore`.

Acceptance:

- User create/update, role create/update, bind/unbind role, and permission
  listing work with JDBC backing.
- Built-in default roles/users are present in a clean DB.
- Existing DB operator data is not overwritten by bootstrap defaults.
- `ApiAuthService` and operator route authorization continue to resolve
  operator principals through `PrincipalDirectory`.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=IdentityAccessControllerTest,DefaultOperatorPrincipalDirectoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Add a new focused JDBC test, for example:

```text
JdbcUserRolePermissionStoreTest
```

## Slice 3 - JDBC API Usage Ledger

Goal:

Make low-volume API usage evidence inspectable and restart-durable without
turning it into runtime or trace history.

Scope:

- Add a JDBC implementation for `ApiUsageLedgerStore`.
- Add a server-owned migration table for usage records with indexes for
  `keyId`, `principalId`, task id, status, operation, and created time. Do not
  place this migration in `platform_infra`.
- Preserve append idempotency by `usageId`.
- Keep usage ledger writes best-effort only if controller behavior already
  treats them as non-authoritative. Otherwise document and test failure
  behavior explicitly.
- Define a bounded query and retention decision. A cleanup implementation is
  not required in this slice, but the table must not be documented as
  unbounded audit or trace history.

Acceptance:

- Accepted, rejected, and failed-after-accept usage records persist in JDBC.
- `ApiUsageController` can query usage after store restart.
- Usage ledger does not write per-dispatch, per-heartbeat, per-lease, or trace
  events.
- Query/read behavior has a documented bound or retention/open-cleanup
  decision.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=ApiUsageLedgerServiceTest,ApiUsageControllerTest,TaskApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Add a new focused JDBC test, for example:

```text
JdbcApiUsageLedgerStoreTest
```

## Slice 4 - Store Mode Wiring And Prod Profile Proof

Goal:

Make `dev` and `prod` select the expected control-plane store backing without
hidden component-scan behavior.

Scope:

- Add explicit configuration properties for server control-plane store backing
  if needed.
- Default JDBC storage modes to JDBC server stores.
- Default memory storage mode to memory server stores.
- Keep `SubmitterViewerSessionStore` explicitly memory-only.
- Add startup proof that `prod` uses SQLite-backed server control-plane stores
  and fails visibly when Redis is unavailable for runtime surfaces.

Acceptance:

- `application-prod.yml` selects SQLite control-plane stores for server
  IAM/API-key/usage surfaces.
- `application-dev.yml` behavior is explicit and no longer depends on concrete
  in-memory `@Component` scanning.
- Tests may delete/recreate the configured test DB. They do not need to prove
  historical schema upgrade compatibility.
- If `mass.server.control-plane.store` exists, tests prove it is a startup/debug
  override only. If it does not exist, docs state that server store mode derives
  only from `mass.storage.mode`.
- Server startup logs or diagnostics expose selected control-plane store mode.
- Architecture guard fails if an in-memory stable control-plane store is
  component-selected in main source.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=CleanServerStartupIntegrationTest,ControlPlaneSeedImportIntegrationTest,ServerMainSourceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 5 - Restart-Oriented Server E2E

Goal:

Prove the product path that matters for single-node prod: API keys, operators,
seed/import metadata, and usage evidence survive process/store restart while
runtime state remains Redis-owned.

Scope:

- Add one focused JDBC/SQLite restart test for API-key lifecycle and
  authentication.
- Add one focused JDBC/SQLite restart test for operator IAM state.
- Add one usage-ledger restart query test.
- Avoid broad task lifecycle restart claims unless Redis runtime proof is also
  part of the test.

Acceptance:

- A key created before store restart authenticates after restart and can still
  be listed/revoked.
- A revoked key remains rejected after restart.
- An operator user/role mutation remains visible after restart.
- Usage records remain queryable after restart.
- The tests do not imply task queues, leases, worker presence, or trace events
  are SQLite truth.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=*Jdbc*StoreTest,*ApiKey*Test,*Identity*Test,*Usage*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 6 - Residue Guard And Documentation

Goal:

Prevent this class of rough server store wiring from returning.

Scope:

- Add source guard coverage for stable server control-plane store assembly.
- Add source guard coverage that server API-key/IAM/session/usage schema names
  do not appear under `platform_infra/mass-storage-jdbc` migration resources.
- Update `xa-mass-server/README.md` key config/store section to describe
  server control-plane stores separately from engine runtime and transport
  stores.
- Keep `doc/INFRA_TRUTH_LAYERS.md` unchanged unless this work changes a global
  boundary. The target is already consistent with that doc.
- Remove stale wording that describes memory stores as the production backing.

Acceptance:

- Guard blocks `@Component` on stable concrete in-memory server stores.
- Guard blocks server-owned API-key/IAM/session/usage migrations from being
  added to `platform_infra/mass-storage-jdbc`.
- README states which server stores are JDBC-backed under prod and which remain
  memory-only by design.
- No doc claims SQLite owns runtime queue/lease/result/trace truth.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=ServerMainSourceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Suggested Implementation Order

1. Slice 0: assembly and guard, behavior-preserving.
2. Slice 1: API-key lifecycle durability, because it fixes the credential
   split and is the main production blocker.
3. Slice 2: operator IAM durability.
4. Slice 3: usage ledger durability.
5. Slice 4: mode/profile proof.
6. Slice 5: restart-oriented E2E.
7. Slice 6: docs and residue guard.

## Open Decisions

- Which server-owned migration execution mechanism is used for API-key/IAM/
  usage schemas. Must resolve in Slice 0 before any JDBC server store is added.
- Whether API-key lifecycle rows and `xa_principal` projection should share one
  table family or remain separate tables with explicit reconciliation rules.
  Must resolve in Slice 1.
- Whether `SubmitterViewerSessionStore` should move to Redis for cross-process
  sessions is deferred outside this roadmap. JDBC backing is not an option for
  the current control-plane store convergence.
- Whether API usage ledger write failures should fail the request or remain
  best-effort with visible diagnostics. Must resolve in Slice 3.
- Whether store mode should be derived only from `mass.storage.mode` or exposed
  through `mass.server.control-plane.store`. Must resolve in Slice 0 before
  any JDBC store implementation starts.

## Completion Criteria

The roadmap is complete when:

- stable server IAM/API-key/usage control-plane stores have memory and JDBC
  implementations selected through explicit server assembly;
- `prod` SQLite mode no longer loses API-key lifecycle, operator IAM, or usage
  evidence across restart;
- authentication projection and API-key lifecycle validation cannot split after
  restart;
- request-local sync bridge/counters remain clearly memory-only;
- source guards prevent implicit concrete in-memory store component scanning
  from returning.
