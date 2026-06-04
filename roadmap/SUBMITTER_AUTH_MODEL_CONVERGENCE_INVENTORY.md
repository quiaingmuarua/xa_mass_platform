# Submitter Auth Model Convergence Inventory

Status: current code inventory for
`SUBMITTER_AUTH_MODEL_CONVERGENCE_ROADMAP.md`.

## Scope

This inventory covers the current `Submitter*`, API-key, principal, and
submitter-viewer session model across the embedded SDK and `xa-mass-server`.

It does not cover JDBC durability implementation. That remains owned by
`SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md` after this model
convergence lands.

## Symbols

| Symbol | Current Owner | Current Role | Current Issue | Target |
| --- | --- | --- | --- | --- |
| `SubmitterRegistration` | `sdk/xa-mass-embedded-sdk-api` | Raw credential registration plus profile source | Name implies task submitter, but it also registers worker/service credentials. Defaults can create broad task credentials. | Keep as the initial projection payload if useful; rename only after the projection port lands and only without adding a third credential shape. |
| `SubmitterProfile` | `sdk/xa-mass-embedded-sdk-api` | Credential read model without raw secret | Name implies task submitter profile, but it is the public read model for any service credential principal. | Keep as the read/profile shape unless Slice 4 renames it; do not add an extra snapshot/view DTO. |
| `SubmitterOperations` | `sdk/xa-mass-embedded-sdk` | Resource operations plus credential authentication | Mixes resource registration/list/get with `authenticateSubmitter(...)`. | API-key lifecycle must stop depending on it; facade rename is secondary and non-blocking. |
| `SubmitterRegistry` | `sdk/xa-mass-embedded-sdk-api` | `register(...)` plus `AuthProvider` plus `PrincipalDirectory` | One interface owns writes, authentication, and principal lookup. | First implement the narrow projection/write port; then split remaining write/auth/read coupling without forcing facade rename. |
| `InMemorySubmitterRegistry` | `sdk/xa-mass-embedded-sdk-api` | In-memory credential projection/auth store | Correct test/embedded projection, but implements the over-broad registry interface. | Implement the projection/write port and keep current behavior. |
| `JdbcSubmitterRegistry` | `xa-mass-server` | JDBC host adapter for `xa_principal` auth projection | Persists auth projection but not API-key lifecycle truth. Uses `Submitter*` names for general principal credentials. | Implement the projection/write port before durable API-key lifecycle work; naming cleanup may follow. |
| `MassSdkApplication` | `sdk/xa-mass-embedded-sdk` | Exposes `registerSubmitter`, `listSubmitters`, `getSubmitter`, `authenticateSubmitter`, `AuthProvider`, `PrincipalDirectory` | Public facade exposes old Submitter vocabulary and auth calls together. | Do not rename as a blocker; first ensure API-key lifecycle and host auth use narrow ports instead of facade resource operations. |
| `ApiKeyCredentialService` | `xa-mass-server` | API-key lifecycle owner that writes lifecycle store and projects into `SubmitterOperations` | Lifecycle owner depends on broad resource operations instead of a dedicated credential projection port. | Depend on a dedicated credential projection/write port. |
| `ApiAuthorizationService` | `xa-mass-server` | Authenticates API-key credentials and fallback viewer sessions | Uses generic `submitter` vocabulary for task producers and workers; session fallback shares service principal shape. | Use principal/credential vocabulary internally; keep viewer session as separate volatile auth path. |
| `PrincipalContext` | `sdk/xa-mass-embedded-sdk-api` | Authenticated caller context for operator, service, worker, and session flows | Empty `projectScopes` / `eventScopes` currently allow all values; explicit wildcard is not required. | Record explicit scope semantics and make storage schema distinguish omitted/wildcard/bounded states; runtime denial may be a later authorization slice. |
| `DefaultAuthorizationPolicy` | `sdk/xa-mass-embedded-sdk` | Permission/scope/worker-binding policy | Allows several resource types after required-permission check and inherits empty-scope semantics from `PrincipalContext`. | Add tests around the chosen compatibility or deny-by-default decision before claiming authorization semantics changed. |
| `SubmitterViewerSessionStore` | `xa-mass-server` | Volatile submitter viewer session token store | Correctly session/runtime convenience state, but store roadmap previously left DB backing open. | Keep memory-only in this roadmap; future cross-process sharing belongs to runtime/Redis decision, not DB. |
| `SubmitterViewerSessionService` | `xa-mass-server` | Creates viewer-only sessions from API-key credentials | Session principal uses default service type with only attributes marking session identity. | Keep memory-only; `PrincipalType.SESSION` is non-blocking unless audit/auth tests prove attributes are insufficient. |

## Current Code Observations

- `SubmitterRegistry` currently extends both `AuthProvider` and
  `PrincipalDirectory`, so registering credentials, authenticating secrets, and
  reading principals are coupled in one interface.
- `SubmitterOperations` exposes `authenticateSubmitter(...)`, so resource
  operations and host authentication are coupled on the embedded SDK facade.
- `ApiKeyCredentialService` writes `ApiKeyCredentialStore` first and then calls
  `SubmitterOperations.registerSubmitter(...)` to project the credential into
  authentication.
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

## Immediate Gaps

1. `Submitter` vocabulary hides whether a principal is a task producer, worker
   credential, service credential, operator, or viewer session.
2. Write/auth/read responsibilities are coupled in `SubmitterRegistry`.
3. Server API-key lifecycle writes through broad `SubmitterOperations` instead
   of a projection-specific port.
4. Empty scope means all scope, which is risky before real external
   credentials become durable. This must be made explicit in storage/docs, but
   runtime behavior change can be a dedicated later slice.
5. Viewer sessions are named as submitter sessions and use service principal
   shape, but their store correctly remains volatile.
6. The minimum store-infra blocker is `ApiKeyCredentialService` depending on
   `SubmitterOperations`; facade rename and session principal type are
   secondary cleanup.

## Existing Proof Surfaces

| Test | Current Role | Target Use |
| --- | --- | --- |
| `SubmitterRegistrationTest` | verifies current registration/profile defaults | Keep for current shape; update only if Slice 4 renames or scope semantics change. |
| `InMemorySubmitterRegistryTest` | verifies in-memory auth projection | Retarget to the projection port first; keep behavior unchanged. |
| `MassSdkTest` submitter sections | verifies embedded facade registration/auth | Keep unless Slice 4 renames facade methods; do not force early churn. |
| `JdbcSubmitterRegistryTest` | verifies JDBC auth projection persistence | Retarget to the projection port; keep as projection proof, not lifecycle proof. |
| `ApiKeyControllerTest` / `ApiKeyApplicationControllerTest` | verifies API-key lifecycle behavior | Update to ensure lifecycle writes use projection-specific port. |
| `ExternalWorkerApiControllerTest` | verifies worker credential authorization and binding | Extend for explicit scope/binding semantics. |
| `SubmitterViewerSessionControllerTest` | verifies viewer session flow | Keep memory-only; update wording if session principal identity changes. |
