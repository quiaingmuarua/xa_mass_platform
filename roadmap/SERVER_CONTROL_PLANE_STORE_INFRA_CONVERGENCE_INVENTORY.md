# Server Control-Plane Store Infra Convergence Inventory

Status: current code inventory for
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
| `CredentialAuthProjectionWriter` / `JdbcSubmitterRegistry` | SDK auth projection contract, host persistence adapter in server | `JdbcSubmitterRegistry` when JDBC storage is enabled; SDK memory registry otherwise | principal credential truth | Durable lifecycle schema can proceed without depending on broad `SubmitterOperations`. |
| `TaskReviewStore` | server review/read-model owner | `JdbcTaskReviewStore` for JDBC modes, `InMemoryTaskReviewStore` fallback | server-local review materialization | Keep server-owned and selectable; do not promote to runtime result truth. |
| `ApiKeyApplicationStore` | server IAM/API-key owner | `InMemoryApiKeyApplicationStore` via `@Component` | stable API-key request workflow truth | Add JDBC implementation and explicit store assembly. |
| `ApiKeyCredentialStore` | server IAM/API-key owner | `InMemoryApiKeyCredentialStore` via `@Component` | stable API-key lifecycle truth | Add JDBC implementation and make it consistent with `SubmitterRegistry` projection. |
| `UserRolePermissionStore` | server IAM owner | `InMemoryUserRolePermissionStore` via `@Component` with bootstrap defaults | stable operator/user/role truth | Add JDBC implementation with explicit bootstrap/seed defaults. |
| `ApiUsageLedgerStore` | server usage/audit owner | `InMemoryApiUsageLedgerStore` via `@Component` | append-only API usage ledger | Add JDBC implementation for inspectable low-volume usage evidence. Do not use it for runtime event streams. |
| `SubmitterViewerSessionStore` | server submitter-viewer session owner | `InMemorySubmitterViewerSessionStore` via `@Component` | session/runtime convenience state | Keep memory-only. Future cross-process sharing, if needed, belongs to runtime/Redis session design, not JDBC. |
| `SyncTaskResultBridge.pendingByMessage` | server sync HTTP bridge | in-process `ConcurrentHashMap` | request-local wait state | Keep memory-only; it is not restart-required truth. |
| `TaskSyncRequestSupervisor` counters | server sync HTTP guardrail | in-process counters + Micrometer metrics | request-local admission/metrics state | Keep memory-only; optionally make limits Spring properties. |
| `FrontendConsoleRoutingService.LEGACY_ROUTE_MAPPING` | server console route owner | static map | route alias mapping | Keep static; not store infra. |

## Current Code Observations

- `XaMassServerApplication` already switches task shell, review store, runtime,
  transport delivery, and transport presence by configured infra mode.
- The API-key/IAM/session/usage stores are selected by component scanning
  because the in-memory implementations carry `@Component`.
- `ApiKeyCredentialService.createOperatorKey(...)` writes an
  `ApiKeyCredentialRecord` into `ApiKeyCredentialStore`, then projects the
  generated secret through `CredentialAuthProjectionWriter`.
- In JDBC storage mode, `JdbcSubmitterRegistry` can persist the credential
  auth projection in `xa_principal`.
- After restart, `JdbcSubmitterRegistry` can reload and authenticate the
  credential, but `InMemoryApiKeyCredentialStore` is empty. The later
  `ApiKeyCredentialService.validateAuthenticatedPrincipal(...)` lookup by
  `apiKeyId` can reject a principal that the JDBC auth projection loaded.
- `InMemoryUserRolePermissionStore` owns operator bootstrap users and roles
  inside code. That is acceptable as a default seed source, but not as the only
  production backing.
- `ApiUsageLedgerStore` is low-volume server audit/usage evidence. It should
  be durable for product inspection, but it must not become a high-volume
  runtime or trace event table.
- Current shared JDBC migration execution lives in
  `platform_infra/mass-storage-jdbc` and runs
  `classpath:db/migration/control-plane`. Server API-key/IAM/usage schema does
  not yet have a server-owned migration runner/location.
- API-key credential records currently carry `projectScopes` and `eventScopes`
  as lists. A durable schema must add an explicit mode or equivalent contract
  so omitted, wildcard, and bounded scopes are distinguishable.

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

## Immediate Gaps

1. Server control-plane stores do not have a single assembly owner comparable
   to task shell/review/runtime wiring.
2. Stable API-key lifecycle truth is memory-only while authentication
   projection can be JDBC-backed.
3. Operator user/role state is memory-only, so console/IAM mutations do not
   survive server restart.
4. API usage ledger is memory-only, so accepted/rejected/failed-after-accept
   usage evidence disappears after restart.
5. `@Component` on concrete in-memory stores makes profile/store selection
   implicit and hard to guard.
6. Existing JDBC `xa_principal` stores submitter profiles but does not directly
   represent the richer `ApiKeyCredentialRecord` lifecycle fields.
7. Submitter/auth projection-port convergence is complete enough for durable
   API-key lifecycle table design to proceed. Facade rename, runtime scope
   behavior migration, and viewer-session principal type are not store-infra
   blockers unless they become schema inputs.
8. Server-owned migrations need an execution owner before Slice 1 adds JDBC
   API-key/IAM/usage tables.
9. DB schema changes may delete/recreate local/prod DBs in this pre-release
   stage; there is no historical upgrade compatibility requirement.

## Existing Proof Surfaces

| Test | Current Role | Target Use |
| --- | --- | --- |
| `JdbcSubmitterRegistryTest` | proves SQLite/H2 submitter principal persistence and auth reload | Extend or pair with API-key lifecycle restart proof. |
| `ApiKeyControllerTest` | unit proof for in-memory API-key create/revoke behavior | Keep as store-contract behavior, then add JDBC parity. |
| `ApiKeyApplicationControllerTest` | unit proof for application workflow | Keep as store-contract behavior, then add JDBC parity. |
| `IdentityAccessControllerTest` | unit proof for user/role operations and API-key disabling | Keep and add JDBC store parity. |
| `ApiUsageControllerTest` / `TaskApiControllerTest` | usage ledger behavior with memory store | Add JDBC ledger parity and one server wiring proof. |
| `ServerMainSourceArchitectureGuardTest` | source guard for server boundaries | Add guard that in-memory stores are not component-selected in production mainline. |
