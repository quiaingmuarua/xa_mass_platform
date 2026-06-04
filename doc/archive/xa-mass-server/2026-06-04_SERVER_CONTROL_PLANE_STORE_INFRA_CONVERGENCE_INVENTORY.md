# Server Control-Plane Store Infra Convergence Inventory

Archived on 2026-06-04 after the server control-plane store convergence roadmap
was implemented and current facts were moved to owner docs.

Current truth owners:

- `xa-mass-server/README.md`
- `xa-mass-server/src/main/resources/db/README.md`
- `xa-mass-server/src/main/resources/db/schema/server-control-plane/README.md`
- `doc/INFRA_TRUTH_LAYERS.md`

This document is historical context only. Do not use it as proof of current
implementation behavior without verifying current code, tests, and owner docs.

Status: archived code inventory for
`SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md`.

## Scope

This inventory covers server-owned stores and host-side state under
`xa-mass-server`. It classifies which values are stable control-plane truth,
which are runtime/request-local state, and which already have selectable
storage/runtime backing.

It does not cover engine runtime queues, transport protocol state, trace
materialization, or worker scheduling decisions.

## Current Store Symbols

| Symbol | Current Owner | Current Backing | Classification | Target |
| --- | --- | --- | --- | --- |
| `TaskShellStore` | `platform_infra/mass-storage-api`, assembled by server | `JdbcStorageRuntime.taskShellStore()` for JDBC modes, `InMemoryTaskShellStore` fallback | control-plane storage | Keep current profile-based switching. |
| `RuleStorage` | `platform_infra/mass-storage-api`, assembled through embedded SDK/server | `JdbcStorageRuntime.ruleStorage()` for JDBC modes | control-plane storage | Keep current JDBC-backed seed/import path. |
| `CredentialAuthProjectionWriter` / `JdbcSubmitterRegistry` | SDK auth projection contract, host persistence adapter in server | `JdbcSubmitterRegistry` when JDBC storage is enabled; SDK memory registry otherwise | credential auth projection / principal directory projection | Durable lifecycle schema can proceed without depending on broad `SubmitterOperations`; do not treat `xa_principal` as full API-key lifecycle truth. |
| `TaskReviewStore` | server review/read-model owner | `JdbcTaskReviewStore` for JDBC modes, `InMemoryTaskReviewStore` fallback | server-local review materialization | Keep store backing selectable; materialization writes are task opt-in via `sharedConfig.reviewMaterializationMode` and default `OFF`. Do not promote to runtime result truth. |
| `ApiKeyApplicationStore` | server IAM/API-key owner | `JdbcApiKeyApplicationStore` for JDBC modes, `InMemoryApiKeyApplicationStore` fallback through `ServerControlPlaneStoreConfiguration` | stable API-key request workflow truth | Keep server-owned JDBC implementation and parity tests; do not move application workflow tables to `platform_infra`. |
| `ApiKeyCredentialStore` | server IAM/API-key owner | `JdbcApiKeyCredentialStore` for JDBC modes, `InMemoryApiKeyCredentialStore` fallback through `ServerControlPlaneStoreConfiguration` | stable API-key lifecycle truth | Keep lifecycle storage consistent with `CredentialAuthProjectionWriter` / `JdbcSubmitterRegistry` auth projection. |
| `UserRolePermissionStore` | server IAM owner | `JdbcUserRolePermissionStore` for JDBC modes, `InMemoryUserRolePermissionStore` fallback through `ServerControlPlaneStoreConfiguration` | stable operator/user/role truth | Keep JDBC implementation with explicit seed-if-missing bootstrap defaults; do not overwrite existing operator data. |
| `DefaultOperatorPrincipalDirectory` | server operator principal directory adapter | Explicit bean assembled with `UserRolePermissionStore`; no production no-arg fallback | principal directory adapter | Keep dependent on the explicit `UserRolePermissionStore`; do not recreate a local bootstrap fallback. |
| `ApiAuthService` | server request principal resolver | `@Component` with constructor-injected `PrincipalDirectory` and `HeaderPrincipalContextFactory`; no production no-arg fallback | request principal resolver | Keep constructor-injected; do not recreate direct operator-directory/store fallback. |
| `ApiUsageLedgerStore` | server usage/audit owner | `JdbcApiUsageLedgerStore` for JDBC modes, `InMemoryApiUsageLedgerStore` fallback through `ServerControlPlaneStoreConfiguration` | append-only API usage ledger | Keep JDBC implementation for inspectable low-volume usage evidence. Do not use it for runtime event streams. |
| `SubmitterViewerSessionStore` | server submitter-viewer session owner | `InMemorySubmitterViewerSessionStore` explicitly assembled by `ServerControlPlaneStoreConfiguration` | session/runtime convenience state | Keep memory-only. Future cross-process sharing, if needed, belongs to runtime/Redis session design, not JDBC. |
| `SyncTaskResultBridge.pendingByMessage` | server sync HTTP bridge | in-process `ConcurrentHashMap` | request-local wait state | Keep memory-only; it is not restart-required truth. |
| `TaskSyncRequestSupervisor` counters | server sync HTTP guardrail | in-process counters + Micrometer metrics | request-local admission/metrics state | Keep memory-only; optionally make limits Spring properties. |
| `FrontendConsoleRoutingService.LEGACY_ROUTE_MAPPING` | server console route owner | static map | route alias mapping | Keep static; not store infra. |

## Current Code Observations

- `XaMassServerApplication` already switches task shell, review store, runtime,
  transport delivery, and transport presence by configured infra mode.
- The API-key/IAM/session/usage stores are explicitly assembled by
  `ServerControlPlaneStoreConfiguration`. Their concrete in-memory
  implementations no longer carry `@Component`.
- In JDBC modes, `ServerControlPlaneStoreConfiguration` selects
  `JdbcApiKeyApplicationStore`, `JdbcApiKeyCredentialStore`,
  `JdbcUserRolePermissionStore`, and `JdbcApiUsageLedgerStore`; in memory mode
  it selects the in-memory implementations.
- `ApiKeyCredentialService.createOperatorKey(...)` writes an
  `ApiKeyCredentialRecord` into `ApiKeyCredentialStore`, then projects the
  generated secret through `CredentialAuthProjectionWriter`.
- In JDBC storage mode, `JdbcSubmitterRegistry` persists the credential auth
  projection in `xa_principal`, while `JdbcApiKeyCredentialStore` persists the
  richer API-key lifecycle record in `xa_api_key_credential`.
- `JdbcApiKeyLifecycleStoreTest` proves H2 and SQLite restart behavior for
  API-key application approval, credential lifecycle, auth projection reload,
  `validateAuthenticatedPrincipal(...)`, and revocation.
- `UserRolePermissionBootstrapDefaults` owns built-in operator bootstrap users,
  roles, permissions, and bindings. `JdbcUserRolePermissionStore` seeds missing
  defaults only; it does not overwrite existing operator data.
- `JdbcUserRolePermissionStoreTest` proves H2 and SQLite restart behavior for
  operator users, roles, role bindings, and seed-if-missing defaults.
- `ApiUsageLedgerStore` is low-volume server audit/usage evidence. In JDBC
  modes, `JdbcApiUsageLedgerStore` persists that evidence for product
  inspection, but it must not become a high-volume runtime or trace event
  table.
- `JdbcApiUsageLedgerStoreTest` proves H2 and SQLite restart behavior for
  accepted, rejected, and failed-after-accept usage records.
- `DefaultOperatorPrincipalDirectory` is now assembled explicitly with
  `UserRolePermissionStore`; the production no-arg fallback to
  `InMemoryUserRolePermissionStore.bootstrapDefaults()` has been removed.
- `ApiAuthService` is now constructor-injected with `PrincipalDirectory` and
  `HeaderPrincipalContextFactory`; the production no-arg fallback that created
  `new DefaultOperatorPrincipalDirectory()` has been removed.
- `TaskReviewStore` can be JDBC-backed in JDBC storage modes, but review
  materialization writes are not default DB writes. Current policy defaults to
  `OFF`; a task must set `sharedConfig.reviewMaterializationMode=terminal` for
  terminal review rows, or `diagnostic` for attempt-level diagnostic rows.
- Current shared JDBC migration execution lives in
  `platform_infra/mass-storage-jdbc` and runs
  `classpath:db/migration/control-plane`. Server API-key/IAM/usage schema now
  has a server-owned Flyway runner that uses
  `classpath:db/migration/server-control-plane` and a separate
  `flyway_server_control_plane_schema_history` table. Because the platform
  migration normally creates generic control-plane tables first, the
  server-owned runner baselines its own history table at version `0` before
  applying server migrations. The server-owned resource locations are
  reserved under
  `xa-mass-server/src/main/resources/db/schema/server-control-plane` and
  `xa-mass-server/src/main/resources/db/migration/server-control-plane`.
- API-key credential records currently expose `projectScopes` and
  `eventScopes` as lists, but the JDBC table persists explicit
  `project_scope_mode` and `event_scope_mode` columns so omitted, wildcard,
  and bounded scopes are distinguishable at schema level. Runtime scope
  behavior migration remains outside this store-infra slice.

## Store Placement Decisions

- `ApiKeyApplicationStore`: layer=control-plane storage; reason=stable
  operator/API-key application workflow.
- `ApiKeyCredentialStore`: layer=control-plane storage; reason=restart must
  preserve key lifecycle state used by validation, revocation, expiry, and UI.
- `UserRolePermissionStore`: layer=control-plane storage; reason=stable
  operator identity, roles, and permissions.
- `ApiUsageLedgerStore`: layer=control-plane storage for bounded API usage
  audit; reason=operator/billing inspection, not runtime correctness.
- `SubmitterViewerSessionStore`: layer=runtime/session state; reason=sessions
  can be recreated and are not the source of credential truth. Keep memory-only
  for this roadmap.
- `SyncTaskResultBridge` and `TaskSyncRequestSupervisor`: layer=temporary
  request-local runtime state; reason=blocking HTTP wait coordination and
  in-flight counters.

## Remaining Non-Store Decisions

1. Existing JDBC `xa_principal` stores auth projection and principal-directory
   fields; richer API-key lifecycle fields now live in
   `xa_api_key_credential`. These must remain ordered consistently so lifecycle
   and projection cannot split.
2. Submitter/auth projection-port convergence is complete enough for durable
   API-key lifecycle table design to proceed. Facade rename, runtime scope
   behavior migration, and viewer-session principal type are not store-infra
   blockers unless they become schema inputs. Scope behavior migration is not a
   blocker; API-key lifecycle scope representation is now persisted with
   explicit scope mode columns.
3. DB schema changes may delete/recreate local/prod DBs in this pre-release
   stage; there is no historical upgrade compatibility requirement.

## Existing Proof Surfaces

| Test | Current Role | Target Use |
| --- | --- | --- |
| `JdbcSubmitterRegistryTest` | proves SQLite/H2 submitter principal persistence and auth reload | Keep as auth-projection persistence proof paired with API-key lifecycle restart proof. |
| `ApiKeyControllerTest` | unit proof for API-key create/revoke behavior | Keep as controller/store-contract behavior proof across backing implementations. |
| `ApiKeyApplicationControllerTest` | unit proof for application workflow | Keep as application workflow behavior proof across backing implementations. |
| `JdbcApiKeyLifecycleStoreTest` | proves H2/SQLite API-key application and credential lifecycle restart durability plus auth projection consistency | Keep as Slice 1 API-key lifecycle proof. |
| `IdentityAccessControllerTest` | unit proof for user/role operations and API-key disabling | Keep as controller/API behavior proof. |
| `JdbcUserRolePermissionStoreTest` | proves H2/SQLite operator user, role, binding, and seed-if-missing restart durability | Keep as Slice 2 IAM durability proof. |
| `ApiUsageControllerTest` / `TaskApiControllerTest` | usage ledger controller/task API behavior | Keep as controller/API behavior proof. |
| `JdbcApiUsageLedgerStoreTest` | proves H2/SQLite accepted, rejected, and failed-after-accept usage record restart durability | Keep as Slice 3 usage durability proof. |
| `DefaultOperatorPrincipalDirectoryTest` | explicit operator principal-directory proof with injected store | Keep as principal adapter behavior proof. |
| `ServerControlPlaneStoreConfigurationTest` | proves explicit store assembly creates one bean per control-plane store contract, JDBC modes select JDBC API-key/IAM/usage stores, and submitter-viewer sessions remain memory-only | Keep as store mode wiring proof. |
| `ServerControlPlaneMigrationRunnerTest` | proves memory mode does not run server-owned migrations | Keep paired with JDBC lifecycle proof that exercises server-owned migrations. |
| `ServerMainSourceArchitectureGuardTest` | source guard for server boundaries | Guards in-memory stores from becoming component-selected again, blocks production auth assembly from recreating implicit operator memory fallbacks, keeps server-owned migrations under server resources, and keeps submitter-viewer sessions out of JDBC. |
