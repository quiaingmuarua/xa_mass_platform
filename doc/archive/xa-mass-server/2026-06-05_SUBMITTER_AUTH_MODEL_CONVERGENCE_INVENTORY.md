# Submitter Auth Model Convergence Inventory

Archived on 2026-06-05 with the submitter/auth model convergence roadmap.

Current truth owners:

- `sdk/README.md` for SDK/public-contract placement.
- `xa-mass-server/README.md` for server auth and control-plane store status.
- `xa-mass-server/src/main/resources/db/schema/server-control-plane/README.md`
  for server SQL schema ownership.

This document is historical context only. Do not use it as proof of current
implementation behavior; verify against current code, tests, owner READMEs,
and active schema docs.

Status: archived code inventory for submitter/auth model convergence.

## Scope

This inventory covers the current `Submitter*`, API-key, principal, and
submitter-viewer session model across the embedded SDK and `xa-mass-server`.

It does not cover JDBC durability implementation. Current server store truth is
owned by `xa-mass-server/README.md` and
`xa-mass-server/src/main/resources/db/schema/server-control-plane/README.md`
after this model convergence lands.

## Symbols

| Symbol | Current Owner | Current Role | Current Issue | Target |
| --- | --- | --- | --- | --- |
| `SubmitterRegistration` | `sdk/xa-mass-embedded-sdk-api` | Raw credential registration plus profile source | Name implies task submitter, but it also registers worker/service credentials. Defaults can create broad task credentials. | Kept as the write-with-secret shape. Do not add a third credential wrapper. |
| `SubmitterProfile` | `sdk/xa-mass-embedded-sdk-api` | Credential read model without raw secret | Name implies task submitter profile, but it is the public read model for any service credential principal. | Kept as the read/profile shape. Do not add an extra snapshot/view DTO. |
| `SubmitterOperations` | `sdk/xa-mass-embedded-sdk` | Resource operations plus credential authentication | Mixes resource registration/list/get with `authenticateSubmitter(...)`. | Kept as embedded resource facade vocabulary; API-key lifecycle no longer depends on it. |
| `CredentialAuthProjectionWriter` | `sdk/xa-mass-embedded-sdk-api` | Narrow lifecycle-to-auth-projection port | New convergence port. | API-key lifecycle writes active/disabled auth projection through this port. |
| `SubmitterRegistry` | `sdk/xa-mass-embedded-sdk-api` | Submitter resource registry only | Previously also inherited auth/directory contracts. | Now only owns resource registry methods; auth, directory, and projection writes are separate contracts. |
| `InMemorySubmitterRegistry` | `sdk/xa-mass-embedded-sdk-api` | In-memory credential projection/auth store | Backend still implements multiple narrow contracts. | Implements `SubmitterRegistry`, `CredentialAuthProjectionWriter`, `AuthProvider`, and `PrincipalDirectory` explicitly. |
| `JdbcSubmitterRegistry` | `xa-mass-server` | JDBC host adapter for `xa_principal` auth projection | Persists auth projection but not API-key lifecycle truth. | Implements `CredentialAuthProjectionWriter` before durable API-key lifecycle work. |
| `MassSdkApplication` | `sdk/xa-mass-embedded-sdk` | Exposes register/list/get/auth facade plus narrow auth contracts | Public facade keeps old Submitter vocabulary. | Uses split fields for resource registry, projection writer, auth provider, and principal directory. |
| `ApiKeyCredentialService` | `xa-mass-server` | API-key lifecycle owner that writes lifecycle store and projects auth state | Previously depended on broad `SubmitterOperations`. | Depends on `CredentialAuthProjectionWriter`. |
| `ApiAuthorizationService` | `xa-mass-server` | Authenticates API-key credentials and fallback viewer sessions | Uses generic `submitter` vocabulary for task producers and workers; session fallback shares service principal shape. | Use principal/credential vocabulary internally; keep viewer session as separate volatile auth path. |
| `PrincipalContext` | `sdk/xa-mass-embedded-sdk-api` | Authenticated caller context for operator, service, worker, and session flows | Empty `projectScopes` / `eventScopes` currently allow all values; explicit wildcard is also available. | Current empty-scope broad-access compatibility is tested. Future storage schema must use explicit scope mode for omitted/wildcard/bounded states. |
| `DefaultAuthorizationPolicy` | `sdk/xa-mass-embedded-sdk` | Permission/scope/worker-binding policy | Allows several resource types after required-permission check and inherits empty-scope semantics from `PrincipalContext`. | No runtime scope-denial change in this roadmap. |
| `SubmitterViewerSessionStore` | `xa-mass-server` | Volatile submitter viewer session token store | Correctly session/runtime convenience state, but store roadmap previously left DB backing open. | Keep memory-only in this roadmap; future cross-process sharing belongs to runtime/Redis decision, not DB. |
| `SubmitterViewerSessionService` | `xa-mass-server` | Creates viewer-only sessions from API-key credentials | Session principal uses default service type with attributes marking session identity. | Keep memory-only; no `PrincipalType.SESSION` required now. |

## Current Code Observations

- `SubmitterRegistry` no longer extends `AuthProvider` or `PrincipalDirectory`.
  Registering credentials, authenticating secrets, writing auth projection, and
  reading principals are separate contracts.
- `SubmitterOperations` exposes `authenticateSubmitter(...)`, so resource
  operations and host authentication are coupled on the embedded SDK facade.
- `ApiKeyCredentialService` writes `ApiKeyCredentialStore` first and then calls
  `CredentialAuthProjectionWriter.projectCredential(...)` to project the
  credential into authentication.
- `JdbcSubmitterRegistry` persists only the authentication projection in
  `xa_principal`; it is not the full API-key lifecycle store.
- `PrincipalContext.allowsScope(...)` returns `true` for empty scopes.
  Combined with current builder defaults, an omitted scope can behave as broad
  access instead of denied access. Changing that behavior is an authorization
  semantics migration, not part of the minimum projection-port split.
- `SubmitterViewerSessionStore` is session/runtime convenience state. It is not
  a durable control-plane store and must not block API-key lifecycle
  convergence.

## Placement Decisions

- API-key application/lifecycle records: layer=control-plane storage;
  reason=stable credential lifecycle and operator workflow.
- Authentication projection: layer=control-plane storage for durable
  credential hash/profile lookup; reason=restart auth needs it, but it is not
  the full lifecycle owner.
- Principal directory: read contract over active principals; reason=operator
  and credential principals need a unified lookup without owning raw secrets.
- Submitter-viewer sessions: layer=runtime/session state; reason=short-lived
  delegated session token, not credential truth.
- Usage ledger: not part of Submitter model convergence; keep with later
  server store infra work.

## Current State And Residue

1. `Submitter` vocabulary hides whether a principal is a task producer, worker
   credential, service credential, operator, or viewer session. It is accepted
   as embedded resource vocabulary for now and must not drive server storage
   schema names.
2. Write/auth/read responsibilities have been split at the interface boundary;
   current backends may still implement multiple narrow contracts explicitly.
3. Server API-key lifecycle writes through `CredentialAuthProjectionWriter`
   instead of broad `SubmitterOperations`.
4. Empty scope means all scope, which is risky before real external
   credentials become durable. This must be made explicit in storage/docs, but
   runtime behavior change can be a dedicated later slice.
5. Viewer sessions are named as submitter sessions and use service principal
   shape, but their store correctly remains volatile.
6. The minimum store-infra blocker has been removed:
   `ApiKeyCredentialService` no longer depends on `SubmitterOperations`.

## Existing Proof Surfaces

| Test | Current Role | Target Use |
| --- | --- | --- |
| `SubmitterRegistrationTest` | verifies current registration/profile defaults | Keep for current shape; update only if facade names or runtime scope behavior change. |
| `PrincipalContextScopeCompatibilityTest` | verifies current empty-scope compatibility, explicit wildcard, and bounded scopes | Keeps runtime behavior explicit while schema design remains mode-based. |
| `InMemorySubmitterRegistryTest` | verifies in-memory auth projection and projection writer port | Current projection-port proof. |
| `MassSdkTest` submitter sections | verifies embedded facade registration/auth | Keep unless Slice 4 renames facade methods; do not force early churn. |
| `JdbcSubmitterRegistryTest` | verifies JDBC auth projection persistence and projection writer port | Projection proof, not lifecycle proof. |
| `ApiKeyControllerTest` / `ApiKeyApplicationControllerTest` | verifies API-key lifecycle behavior | Proves lifecycle still projects active/disabled auth state through the new port. |
| `ExternalWorkerApiControllerTest` | verifies worker credential authorization and binding | Extend for explicit scope/binding semantics. |
| `SubmitterViewerSessionControllerTest` | verifies viewer session flow | Keep memory-only; update wording if session principal identity changes. |
