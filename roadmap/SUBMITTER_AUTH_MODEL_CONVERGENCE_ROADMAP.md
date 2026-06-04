# Submitter Auth Model Convergence Roadmap

Status: implemented; archive candidate after owner handoff.

## Current Code Observations

- `CredentialAuthProjectionWriter` is the narrow auth-projection write port for
  API-key lifecycle code.
- `ApiKeyCredentialService` owns API-key lifecycle state and now projects
  active or disabled credentials through `CredentialAuthProjectionWriter`, not
  broad embedded SDK resource operations.
- `SubmitterRegistry` is now only the embedded SDK resource registry contract;
  it no longer extends `AuthProvider` or `PrincipalDirectory`.
- Current in-memory/JDBC credential backends may still implement
  `SubmitterRegistry`, `CredentialAuthProjectionWriter`, `AuthProvider`, and
  `PrincipalDirectory`, but they do so as explicit narrow contracts rather than
  through one all-in-one registry interface.
- `SubmitterOperations` still exposes embedded resource methods plus
  `authenticateSubmitter(...)`, but it is no longer required by server API-key
  lifecycle or auth projection writes.
- `SubmitterRegistration` and `SubmitterProfile` remain the only credential DTO
  shapes: write-with-secret and read/profile-without-secret.
- `PrincipalContext` still treats empty project/event scope lists as broad
  access. This compatibility behavior is tested and must not be encoded as the
  only durable representation for wildcard, omitted, and bounded scopes.
- `SubmitterViewerSessionStore` remains volatile session state. It stays
  memory-only; future cross-process sharing belongs to a runtime/Redis session
  decision, not SQLite/JDBC control-plane storage.

Detailed inventory:
`roadmap/SUBMITTER_AUTH_MODEL_CONVERGENCE_INVENTORY.md`.

Implemented decisions:

- Projection port name: `CredentialAuthProjectionWriter`.
- Store-infra unblock point: after `ApiKeyCredentialService` writes auth
  projection through `CredentialAuthProjectionWriter`; full
  auth/directory/write guard cleanup remains part of this roadmap's completion.
- Facade naming: keep `Submitter*` as embedded resource vocabulary for now.
  It is acceptable because server API-key lifecycle no longer depends on it and
  durable schema work must use credential/principal vocabulary.
- Scope storage representation: future API-key lifecycle schema must use an
  explicit mode representation for project and event scopes, for example
  `project_scope_mode` / `event_scope_mode` with `OMITTED`, `WILDCARD`, and
  `BOUNDED` values plus bounded-value storage. Runtime empty-scope behavior
  remains temporary broad-access compatibility and is covered by tests.
- Viewer session identity: no `PrincipalType.SESSION` is required now; current
  delegated-session attributes remain the accepted representation.

## Owner Review

There are four different owners hidden behind the current Submitter vocabulary:

- API-key lifecycle owner: server IAM/API-key workflow.
- Credential auth projection owner: host-side credential hash/profile lookup
  used by `AuthProvider`.
- Principal directory owner: unified lookup by principal id.
- Task producer / external worker credential caller: public credential user,
  not an owner of auth storage internals.

The model should converge before
`SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md` implements JDBC
server stores. Otherwise the old Submitter vocabulary and split responsibility
will become table and API names.

## Boundary Decision

- Treat `Submitter` as historical task-producer vocabulary, not the canonical
  owner name for all service credentials.
- The minimal blocker for server store infra is not a broad rename. It is a
  narrow credential auth-projection/write port that lets API-key lifecycle code
  stop depending on broad `SubmitterOperations`.
- Add the projection port and retarget API-key lifecycle writes before durable
  API-key lifecycle tables are added. Full auth/directory/write coupling
  cleanup remains required for this roadmap completion and guards, but it is
  not the first store-infra implementation blocker once Slice 2 lands.
- Embedded SDK facade naming and public `Submitter*` vocabulary can converge
  after the projection port is in place. Rename only when it removes a real
  owner ambiguity; do not create a second facade just to make names cleaner.
- Scope semantics are a policy decision. This roadmap must prevent durable
  schema from freezing implicit empty-scope-as-global-access assumptions, but
  changing runtime authorization behavior is not a hard prerequisite for store
  infra.
- Keep `SubmitterViewerSessionStore` memory-only in this convergence. Do not
  add JDBC tables or server control-plane store mode for viewer sessions.
- `PrincipalType.SESSION` is non-blocking. The current delegated-session
  attribute representation can remain if it is documented and tested.
- Do not keep old and new credential APIs as two live mainlines inside the repo.
  If compatibility names are temporarily kept, they must forward only at the
  edge and be removed or explicitly marked as transitional in the same roadmap.

## Target Shape

- A narrow credential auth-projection/write interface used by API-key lifecycle
  code to publish active/disabled credential auth state. A current
  `SubmitterRegistry` implementation may temporarily implement this port while
  the facade vocabulary converges.
- `AuthProvider` remains the credential -> `PrincipalContext` authentication
  interface.
- `PrincipalDirectory` remains the principal id -> `PrincipalContext` read
  interface.
- API-key lifecycle no longer imports or depends on `SubmitterOperations`.
- Embedded SDK resource operations may keep current `Submitter*` method names
  until a later slice proves that renaming removes a real owner ambiguity
  without model duplication.
- Credential model shape stays bounded: one write request that may include a
  raw secret plus one read/profile shape that never includes a raw secret. Do
  not add extra viewer/snapshot/wrapper DTOs for the same credential state.
- Project/event scopes must be stored and documented explicitly enough to
  distinguish wildcard, omitted, and bounded values. Runtime empty-scope
  behavior may remain unchanged until a dedicated authorization slice changes
  it with tests.
- Before JDBC API-key lifecycle schema is designed, choose a durable scope
  representation that distinguishes omitted, wildcard, and bounded values. The
  representation may be `scopeMode`, an explicit `*` sentinel, or an equivalent
  field contract. Do not persist the current normalized empty list as the only
  representation of all three states.
- Submitter viewer sessions remain short-lived volatile delegated credentials.

## Hard Rules

- Do not add JDBC server API-key lifecycle tables until API-key lifecycle writes
  auth projection through a narrow projection port instead of broad
  `SubmitterOperations`.
- Do not move server API-key lifecycle concepts into `platform_infra`.
- Do not persist `SubmitterViewerSessionStore` in SQLite/JDBC.
- Do not broaden API-key or worker credential scope as part of a rename.
- Do not make empty project/event scope deny-by-default as part of the
  projection-port split. That is a separate authorization semantics change.
- Do not design API-key lifecycle JDBC scope columns until the durable scope
  representation distinguishes omitted, wildcard, and bounded values.
- Do not use rename-only churn without also changing the owner boundary.
- Do not add more than two credential DTO shapes for the same auth credential:
  write request with secret, and read/profile without secret.
- Do not make SDK external Java client depend on embedded SDK auth internals.
- Do not leave API-key lifecycle coupled to broad embedded SDK resource
  operations after this roadmap completes.

## Non-Goals

- No commercial migration/backward-compatibility story.
- No external publication or binary compatibility guarantee in this slice.
- No UI redesign.
- No DB implementation for API-key lifecycle, IAM, usage ledger, or sessions.
- No Redis session implementation; only record it as a future direction if
  needed.
- No operator IAM redesign beyond keeping `PrincipalDirectory` compatibility.
- No mandatory `Submitter*` facade rename in the minimum store-infra unblocking
  path.
- No runtime scope-denial migration unless a later slice explicitly takes that
  behavior change.

## Do Not Start With

Do not start by renaming every `Submitter` symbol or changing scope behavior.
First split the minimum owner boundary that blocks storage work: API-key
lifecycle needs a projection-specific auth write port, not broad embedded SDK
resource operations.

## Slice 0 - Inventory And Blocker Classification

Goal:

Classify which issues block server store infra and which are later cleanup or
authorization-policy decisions.

Scope:

- Keep the inventory current.
- Classify each current symbol as one of:
  - store-infra blocker
  - facade/name cleanup
  - authorization semantics follow-up
  - session identity follow-up
- Record the minimum projection-port name used by Slices 1 and 2:
  `CredentialAuthProjectionWriter`.
- Record that the exact replacement names for these symbols are non-blocking
  unless the implementation slice proves otherwise:
  - `SubmitterRegistration`
  - `SubmitterProfile`
  - `SubmitterOperations`
  - `SubmitterRegistry`
- Record that `PrincipalType.SESSION` is a non-blocking viewer-session identity
  improvement; delegated-session attributes may remain.
- Mark `SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md` as dependent
  only on the projection-port and API-key retargeting slices, not on facade
  rename or scope behavior migration.

Acceptance:

- Inventory lists all main-source Submitter/Auth model symbols and targets.
- Each symbol has a blocker/deferred classification.
- The projection-port name and responsibility are recorded.
- `SubmitterViewerSessionStore` is explicitly memory-only; future Redis
  session sharing is out of this roadmap.
- Scope semantics are recorded as an authorization follow-up unless the roadmap
  later accepts a dedicated behavior-change slice.
- No code changes are required in this slice.

Verification candidates:

```bash
rg -n "SubmitterRegistry|SubmitterOperations|SubmitterRegistration|SubmitterProfile|SubmitterViewerSessionStore" sdk xa-mass-server -g "*.java" -g "*.md"
```

## Slice 1 - Add Minimal Credential Projection Port

Goal:

Create the smallest owner boundary needed to decouple API-key lifecycle from
the broad embedded SDK resource facade.

Scope:

- Add a narrow credential projection/write contract for replacing
  active/disabled credential auth state. Candidate name:
  `CredentialAuthProjectionWriter`.
- The port may accept the existing `SubmitterRegistration` shape in this slice
  to avoid DTO churn; naming cleanup is not required here.
- Let current in-memory/JDBC auth projection implementations implement the new
  port without changing authentication behavior.
- Keep `AuthProvider` and `PrincipalDirectory` unchanged.
- Keep behavior unchanged after the split.

Acceptance:

- A projection/write contract exists and is implemented by current auth
  projection owners.
- The new contract does not expose list/get resource facade operations or
  authentication methods.
- No scope behavior, facade naming, or DTO-shape behavior changes are included.
- Existing auth and submitter registry tests pass after retargeting.

Verification candidates:

```bash
mvn --% -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,xa-mass-server -am -Dtest=InMemorySubmitterRegistryTest,JdbcSubmitterRegistryTest,ApiKeyControllerTest,ApiKeyApplicationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 2 - Retarget API-Key Lifecycle Projection

Goal:

Make server API-key lifecycle write into the narrowed credential projection
instead of `SubmitterOperations`.

Scope:

- Change `ApiKeyCredentialService` to depend on the projection/write contract.
- Keep API-key lifecycle truth in `ApiKeyCredentialStore`.
- Keep auth projection as a derived active/disabled credential view.
- Preserve current create/revoke/disable/expire behavior.
- Preserve current project/event scope semantics. Do not change empty-scope
  runtime behavior in this slice.
- Add tests around projection failure behavior if the projection remains a
  separate write from lifecycle state.

Acceptance:

- `ApiKeyCredentialService` no longer imports or depends on
  `SubmitterOperations`.
- Active credential creation projects an authenticatable principal.
- Revocation, user disable, and expiry project a disabled auth state.
- A projection write failure cannot silently leave lifecycle and auth
  projection inconsistent without visible failure semantics.
- `SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md` is unblocked for
  API-key lifecycle store design after this slice, provided Slice 0 recorded
  the remaining facade/scope/session work as deferred or non-blocking and the
  JDBC schema design still honors the scope-representation rule in this
  roadmap. Slice 3 remains required for roadmap completion and guard coverage,
  but not for starting API-key lifecycle store schema/design.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=ApiKeyControllerTest,ApiKeyApplicationControllerTest,IdentityAccessControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 3 - Split Auth/Directory Coupling

Goal:

Remove the all-in-one registry owner shape without forcing facade rename.

Scope:

- Ensure no single credential interface owns writes, authentication, and
  principal lookup together.
- Keep `AuthProvider` as the authentication SPI.
- Keep `PrincipalDirectory` as the principal lookup SPI.
- Keep the projection/write port from Slice 1 as the write/projection contract.
- Do not require `MassSdkApplication` method rename. If `SubmitterOperations`
  still exposes `authenticateSubmitter(...)`, either move that method to a host
  auth SPI or document the remaining method as explicit transitional residue
  with a removal slice.

Acceptance:

- No single main-source interface extends `AuthProvider`, `PrincipalDirectory`,
  and a credential write/projection method together.
- API-key lifecycle remains on the projection port.
- Resource operations do not need to rename, but they must not be the required
  path for host authentication/projection writes.
- No external Java SDK module imports embedded SDK auth internals.

Verification candidates:

```bash
mvn --% -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk -am test
```

## Slice 4 - Facade Naming And DTO Shape Decision

Goal:

Decide whether the remaining `Submitter*` facade vocabulary must change now,
without creating duplicate credential models.

Scope:

- Review remaining `SubmitterRegistration`, `SubmitterProfile`,
  `SubmitterOperations`, and `MassSdkApplication` public/embedded usages after
  Slices 1-3.
- If old names still materially mislead schema/API work, rename in one pass.
- If old names are tolerable as embedded resource vocabulary, record that
  decision and do not rename.
- Preserve the two-shape credential rule: one write request with optional raw
  secret, one read/profile shape without raw secret.
- Do not add credential viewer/snapshot/wrapper DTOs for the same data.
- Do not keep old and new facade names as two live mainlines.

Acceptance:

- A recorded decision says rename now or keep current facade naming.
- If renamed, old names are removed or kept only as explicitly transitional
  edge methods with removal acceptance in this roadmap.
- If not renamed, the slice records an owner decision that current facade names
  are acceptable embedded resource vocabulary and are no longer misleading for
  storage schema or API surface work.
- Credential DTO shape count does not grow beyond write-with-secret and
  read-without-secret.
- Store-infra remains unblocked either way because API-key lifecycle already
  depends on the projection port.

Verification candidates:

```bash
mvn --% -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,xa-mass-server -am -Dtest=SubmitterRegistrationTest,MassSdkTest,ApiKeyControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 5 - Scope Semantics Decision And Proof

Goal:

Prevent durable credentials from accidentally hiding global authorization in
ambiguous scope values, without making behavior change a prerequisite for store
infra.

Scope:

- Record one explicit decision:
  - keep current runtime empty-scope-as-all behavior temporarily, but require
    store schemas/docs to distinguish omitted, wildcard, and bounded scopes; or
  - change runtime behavior so explicit wildcard (`*`) means all and empty
    means no scoped access.
- If behavior changes, update registration defaults, seed/import examples, and
  worker credential tests in the same slice.
- If behavior does not change, add tests/documentation that make the temporary
  compatibility behavior visible and prevent schemas/docs from treating empty
  as an intentional wildcard.
- Keep worker binding checks independent of this scope decision.

Acceptance:

- Scope semantics are no longer implicit in roadmap prose.
- Durable credential schema design can represent wildcard, omitted, and bounded
  scope states without forcing a runtime behavior change in the same slice.
- Before API-key lifecycle JDBC schema is designed, a concrete durable
  representation is chosen for wildcard, omitted, and bounded scopes.
- If runtime behavior changes, empty project/event scopes no longer authorize
  arbitrary scoped resources and intentional broad access uses explicit `*`.
- If runtime behavior is deferred, a follow-up decision is recorded and tests
  cover the current compatibility behavior.

Verification candidates:

```bash
mvn --% -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,xa-mass-server -am -Dtest=SubmitterRegistrationTest,DefaultAuthorizationPolicyTest,ExternalWorkerApiControllerTest,TaskApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 6 - Viewer Session Boundary

Goal:

Keep submitter-viewer sessions out of control-plane storage and make their
delegated identity explicit enough for auth and audit.

Scope:

- Keep `SubmitterViewerSessionStore` memory-only.
- Remove any roadmap/inventory text that suggests DB backing for viewer
  sessions.
- Do not require `PrincipalType.SESSION`. Keep delegated-session attributes if
  they remain sufficient for auth and audit.
- If `PrincipalType.SESSION` is added, prove it with current session
  controller/auth tests and do not add new permission semantics.
- Ensure viewer sessions carry source key id/session id and only viewer
  permissions.

Acceptance:

- No JDBC or SQLite implementation exists or is planned for
  `SubmitterViewerSessionStore` in active roadmaps.
- Session identity representation is documented as either delegated attributes
  or `PrincipalType.SESSION`.
- Viewer session auth continues to reject revoked/expired source API keys.
- Viewer sessions do not authenticate worker API paths or create new broad
  permission semantics.

Verification candidates:

```bash
mvn --% -pl xa-mass-server -Dtest=SubmitterViewerSessionControllerTest,CurrentSubmitterControllerTest,ApiAuthInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Slice 7 - Guards, Docs, And Store-Roadmap Unblock

Goal:

Prevent the old coupled Submitter model from returning and unblock server store
infra convergence.

Scope:

- Add source guards for the new split:
  - API-key lifecycle service must not depend on broad resource operations.
  - No write/auth/read all-in-one credential registry interface returns.
  - Submitter-viewer sessions are not JDBC/control-plane stores.
- Update `sdk/README.md`, embedded SDK README, server docs, and active
  roadmaps.
- Update `SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md` so it can
  proceed from the converged credential projection model while listing facade
  rename, scope behavior migration, and session principal type as deferred if
  they remain open.

Acceptance:

- Guard tests fail if `ApiKeyCredentialService` imports
  `SubmitterOperations`.
- Guard tests fail if a single registry interface again combines projection
  write, `AuthProvider`, and `PrincipalDirectory`.
- Docs identify which Submitter/Auth cleanup items are required before store
  infra and which are deferred.
- Store-infra roadmap no longer carries blocking Submitter model decisions.

Verification candidates:

```bash
mvn --% -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,xa-mass-server -am -Dtest=ServerMainSourceArchitectureGuardTest,*Submitter*Test,*Authorization*Test,*ApiKey*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

## Suggested Implementation Order

1. Slice 0: classify blockers versus deferred cleanup.
2. Slice 1: add the minimal projection port without behavior/name churn.
3. Slice 2: retarget API-key lifecycle to the projection port.
4. Slice 3: split remaining write/auth/directory coupling.
5. Slice 4: decide facade naming and DTO shape only if still needed.
6. Slice 5: record or implement scope semantics with tests.
7. Slice 6: lock viewer session as memory-only delegated session state.
8. Slice 7: add guards/docs and unblock server store infra roadmap.

## Open Decisions

- No open blocker remains for the store-infra first implementation slice.
- Runtime empty-scope denial remains a future authorization behavior migration,
  not part of this roadmap's implemented mainline.

## Completion Criteria

The roadmap is complete when:

- API-key lifecycle writes auth projection through a narrow projection port;
- credential write/projection, authentication, and principal lookup are split
  enough that no single owner combines all three responsibilities;
- API-key lifecycle no longer depends on broad submitter resource operations;
- credential DTO shape does not grow beyond write-with-secret and
  read-without-secret;
- facade naming is converged, or Slice 4 records an owner decision that current
  names are acceptable embedded resource vocabulary and are no longer
  misleading for storage schema or API surface work;
- scope semantics either require explicit wildcard for broad access or document
  a tested bounded compatibility exception;
- submitter-viewer session storage is memory-only and no active roadmap points
  it at JDBC;
- server control-plane store infra roadmap can proceed without freezing the old
  Submitter model into durable schema.
