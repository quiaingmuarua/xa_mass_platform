# Server Operator Auth Prod Trust Hardening Roadmap

Status: proposed roadmap.

This roadmap converges `xa-mass-server` operator-console authentication from
the current dev-header trust shell into a real seeded-password login / session /
logout flow while preserving convenient integration tests.

The first implementation slice should perform real password-hash verification
against seeded operator credentials. It intentionally does not include public
registration, OAuth / OIDC, password reset, password change, MFA, or account
recovery. Those omissions keep the first production-shaped auth slice small
without falling back to fake authentication.

## Current Code Observations

- `ApiAuthService` currently defaults to the built-in `ops-admin` principal
  when no operator header is present:
  `xa-mass-server/src/main/java/com/xa/mass/api/auth/ApiAuthService.java`.
- `ApiAuthService` and `HeaderPrincipalContextFactory` currently accept
  `X-Mass-User-Mode`, `X-Mass-User-Id`, `X-Mass-Roles`, and
  `X-Mass-Permissions` as direct operator identity input.
- `AuthController` exposes `/api/v1/auth/me` and `/api/v1/auth/logout`, but
  logout only acknowledges the current principal; it does not own a session.
- `ApiAuthInterceptor` currently treats only `/api/v1/auth/me` and
  `/api/v1/auth/logout` as auth-only special routes. A new
  `POST /api/v1/auth/login` route will be rejected before the controller unless
  the route catalog or interceptor explicitly marks it public.
- `UserRecord` and the `xa_iam_user` table currently model operator identity,
  profile, status, attributes, and role bindings. They do not contain password
  material or a credential lifecycle field.
- `frontend/src/auth/provider.backend.ts` already has `loadCurrentUser()` and
  `logout()`, but `login()` is not implemented.
- `frontend/src/api/http.ts` automatically sends `X-Mass-User-Mode` for backend
  API calls when mock auth is disabled.
- `frontend/src/app/config.ts` currently has `useMockAuth`, but no backend auth
  mode field. The frontend cannot know whether it should send dev-header
  identity headers or cookie-backed session requests.
- `frontend/src/layouts/AppHeader.vue` exposes the admin/viewer operator-mode
  switch in backend mode.
- Frontend route guards already distinguish authenticated and unauthenticated
  users through the auth store.
- Existing server controller tests rely heavily on explicit operator headers.
  That is useful test fixture behavior and should remain available under an
  explicit dev/test auth mode.
- The current control-plane seed importer loads catalog, project, submitter,
  and rule metadata. Operator password credentials are not yet part of the
  import contract.

## Owner Decision

Operator login, operator sessions, and operator trust-mode selection are
`xa-mass-server` host concerns.

They must not move into:

- engine lifecycle or scheduling policy
- runtime queue / lease / result state
- worker data-plane authentication
- SDK API-key credential lifecycle
- trace / audit stream

The existing IAM user / role / permission store remains the operator identity
and permission directory. The new session flow should resolve a session to a
`PrincipalContext` through that directory instead of inventing another
permission model.

Operator password material gets its own server-owned credential lifecycle:

- introduce `OperatorCredentialStore`
- add a server-control-plane table such as `xa_operator_credential`
- key credentials by `userId`
- store `passwordHash`, optional encoder metadata if the hash does not embed
  it, status, and timestamps
- do not store password hashes in `UserRecord.attributes`

This keeps IAM profile/role truth separate from credential lifecycle, matching
the existing API-key split between principal directory data and credential
records.

## Target Shape

Introduce an explicit operator auth mode:

```text
mass.auth.operator.mode=dev-header | session | disabled
```

Mode behavior:

- `dev-header`
  - allowed in dev and tests
  - keeps the existing `X-Mass-User-Mode` and custom-header identity fixture
  - keeps integration tests cheap
  - must be rejected in `prod` unless
    `mass.auth.operator.allow-unsafe-dev-header-in-prod=true` is deliberately
    set
- `session`
  - default target for `prod`
  - protected operator routes require a valid operator session cookie
  - login creates the session
  - logout revokes the session and clears the cookie
  - login verifies a submitted password against a seeded password hash
- `disabled`
  - no operator console authentication surface
  - operator console routes fail closed
  - `GET /api/v1/auth/config` returns `disabled` so the frontend can render a
    closed state
  - login, `/auth/me`, and logout fail closed
  - SDK API-key routes and worker data-plane routes keep their existing owners

Session cookie target:

```text
HttpOnly
SameSite=Lax
Secure when HTTPS / explicit secure-cookie property is enabled
Path=/
bounded TTL configured by mass.auth.operator.session.ttl, default 8h
```

First-slice credential verification target:

- introduce a narrow `OperatorCredentialVerifier`
- first implementation verifies password material against a stored password
  hash
- use a standard password hashing component, preferably Spring Security
  `PasswordEncoder` / `DelegatingPasswordEncoder` with bcrypt-compatible hashes
- if Spring Security is used, depend only on the narrow crypto component needed
  for password hashing; do not introduce the Spring Security web filter chain in
  the first slice
- verifier must check that the requested user exists and is active in
  `DefaultOperatorPrincipalDirectory`
- verifier must not accept omitted passwords, raw shared tokens, or header-only
  identity in `session` mode
- no roadmap slice may imply MFA, OAuth, external IdP, registration, password
  reset, or password change until those features exist

Backend auth mode discovery target:

- expose the active operator auth mode to the frontend through a small public
  `GET /api/v1/auth/config` endpoint
- response shape should be minimal: `authMode`, session cookie support flags,
  and CSRF header name when applicable
- AUTH-2 depends on this signal to decide whether to send `X-Mass-User-Mode`
  or rely on cookie-backed session auth
- do not require the frontend to infer backend trust mode from `useMockAuth`
  alone

## Hard Rules

1. `prod` must not silently authenticate missing headers as `ops-admin`.
2. `prod` must not accept `X-Mass-User-Mode` or custom operator identity
   headers by default.
3. Test and dev header auth must remain explicit and easy to use.
4. Login/logout must be server-owned operator-console behavior, not SDK API-key
   behavior.
5. Submitter API-key auth and submitter-viewer sessions are not operator login.
6. Worker API credential auth remains worker data-plane auth; do not route it
   through operator sessions.
7. `dev` and `prod` seed/import paths must accept only `passwordHash` for
   operator password credentials. They must reject plaintext `password`.
8. Tests may use helper APIs to produce password hashes, but test-only plaintext
   fixture shortcuts must not become runtime seed fields.
9. Every protected operator route must require a session in `session` mode.
10. Do not add engine/runtime/trace dependencies for operator auth.
11. Do not persist operator sessions in SQLite as control-plane truth. If
   multi-process operator sessions become necessary, that is a separate
   runtime/session-store decision.
12. Do not make every integration test perform login. Use `dev-header` test
    mode for most controller and Boot-shell tests, and add focused session-flow
    tests.
13. `POST /api/v1/auth/login` must be the only public operator credential
    exchange route in the first slice.
14. Cookie-backed session requests need an explicit CSRF decision before the
    route is considered production-shaped.
15. `prod` unsafe overrides must have deliberately named properties and focused
    startup-guard tests.

## Non-Goals

- No plaintext password seed/import in dev or prod.
- No OAuth / OIDC / SSO provider integration in the first slice.
- No public user registration, password reset, account recovery, or invitation
  workflow.
- No password change UI or self-service credential rotation in the first slice.
- No rewrite of API-key credential, submitter-viewer session, or worker
  credential authentication.
- No engine, task lifecycle, scheduling, worker matching, result convergence,
  runtime queue, or trace changes.
- No durable session persistence unless a later session-store roadmap proves a
  multi-instance requirement.

## Do Not Start With

Do not start by building a polished login page while the backend still defaults
to `ops-admin`.

The first backend slice must make trust mode explicit and make `session` mode
fail closed. The frontend page is useful only after the server has a real
login/logout/session contract to call.

## AUTH-0 Inventory And Mode Decision

Goal: record all current trust inputs and choose the default profile behavior.

Tasks:

1. Inventory every server read of:
   - `X-Mass-User-Mode`
   - `X-Mass-User-Id`
   - `X-Mass-User-Name`
   - `X-Mass-User-Email`
   - `X-Mass-Roles`
   - `X-Mass-Permissions`
2. Inventory every frontend caller that sends operator-mode headers.
3. Inventory tests that rely on operator headers.
4. Decide exact profile defaults:
   - `dev`: `mass.auth.operator.mode=dev-header`
   - `prod`: `mass.auth.operator.mode=session`
   - test resources: `dev-header` unless the test is a session-flow test
5. Decide the operator credential seed shape:
   - `userId`
   - `passwordHash`
   - optional `hashAlgorithm` only if the chosen encoder does not embed it
   - optional status / expiry fields only if they are needed for first-slice
     verification
6. Decide the operator credential store shape:
   - store owner: `OperatorCredentialStore`
   - table owner: `xa-mass-server` server-control-plane schema
   - table name: `xa_operator_credential` or a similarly explicit
     operator-credential table
   - lifecycle fields needed for first-slice verification
   - no password material in `UserRecord.attributes`
7. Use this concrete bootstrap path for the first active login-capable
   operator:
   - `mass.control-plane.seed.enabled=true`
   - `mass.control-plane.seed.mode=apply`
   - `mass.control-plane.seed.operator-credentials-location=<resource>`
   - optional one-shot unlock:
     `mass.auth.operator.bootstrap.allow-empty-before-seed=true`
   - the unlock is accepted only in `prod + session` when an operator
     credential seed location is configured and the seed mode is `apply`
   - the seed/import contract accepts `passwordHash` only
   - startup must run the seed import before the final prod auth readiness
     guard, then re-check that at least one active login-capable operator
     exists
   - if the post-seed re-check fails, the server exits instead of running with
     an empty operator set
8. Decide session lifecycle defaults:
   - `mass.auth.operator.session.ttl=8h`
   - `mass.auth.operator.session.cookie-secure` defaults to `false` in dev and
     `true` in prod unless explicitly configured
   - `mass.auth.operator.csrf.enabled=true` by default in `session` mode
   - local-only bypass property, if implemented:
     `mass.auth.operator.csrf.allow-local-bypass=true`
9. Decide how local developers generate hashes:
   - documented helper command
   - test utility
   - or explicit sample hash checked into a dev-only seed file
10. Use `GET /api/v1/auth/config` as the backend auth mode discovery endpoint.
11. Confirm that dev/prod runtime seed import rejects plaintext `password`.

Acceptance:

- Current code observations are reflected in this roadmap or a sibling
  inventory if the caller list grows.
- The first executable slice has a narrow target and does not require changing
  all tests.
- The credential-store decision is explicit before AUTH-1 starts.
- The login public-route strategy is explicit before AUTH-1 starts.
- The bootstrap import path for the first hashed operator is explicit before
  prod fail-closed startup guards are implemented.
- AUTH-1 must deliver `GET /api/v1/auth/config`; AUTH-2 must not start until
  that endpoint is available.
- Session TTL and any cookie/CSRF configuration keys are explicit before
  `OperatorSessionService` is implemented.
- Prod unsafe override property names are explicit before startup guards are
  implemented.

## AUTH-1 Backend Trust Mode And Fail-Closed Session Contract

Goal: introduce explicit operator auth modes and make `session` mode reject
implicit trust.

Implementation shape:

1. Add configuration object for `mass.auth.operator.*`.
2. Split operator resolution into mode-specific behavior:
   - dev-header resolver
   - session resolver
   - disabled resolver
3. Add an `OperatorSessionStore` interface with an in-process implementation
   for first slice.
4. Add `OperatorSessionService`:
   - create session
   - resolve session
   - revoke session
   - enforce TTL
   - read TTL from `mass.auth.operator.session.ttl`, default `8h`
5. Add `OperatorCredentialStore` and first implementation:
   - server-owned control-plane table such as `xa_operator_credential`
   - in-memory implementation for dev/test when JDBC is unavailable
   - JDBC implementation for `dev`/`prod` storage modes
   - clean DB creation proof for SQLite/H2; no historical migration support is
     required in the current pre-release stage
6. Add `OperatorCredentialVerifier`:
   - password-hash verifier first implementation
   - resolves only active known operators
   - verifies submitted password with `PasswordEncoder`
   - rejects operators without a credential hash in `session` mode
7. Extend `AuthController`:
   - `POST /api/v1/auth/login`
   - `GET /api/v1/auth/config`
   - `GET /api/v1/auth/me`
   - `POST /api/v1/auth/logout`
8. Mark login/config route access explicitly:
   - `POST /api/v1/auth/login` is public
   - `GET /api/v1/auth/config` is public
   - `/api/v1/auth/me` and `/api/v1/auth/logout` continue to require a valid
     authenticated session in `session` mode
9. Update `ApiAuthService` so:
   - no-header in `session` mode is unauthenticated
   - `X-Mass-User-Mode` in `session` mode is ignored or rejected
   - `dev-header` retains current behavior
10. Add operator credential seed/import support:
   - extends control-plane seed import with
     `mass.control-plane.seed.operator-credentials-location`
   - reads only `passwordHash`
   - rejects plaintext `password`
   - can validate-only before applying
   - can prove at least one active login-capable operator for `prod + session`
11. Add prod startup guard:
   - `prod + dev-header` fails unless
     `mass.auth.operator.allow-unsafe-dev-header-in-prod=true` is set
   - `prod + session + missing operator password verifier` fails closed
   - `prod + session + no active login-capable operator` is allowed only before
     seed import when all bootstrap conditions from AUTH-0 item 7 are true
   - the guard re-checks after seed import and fails startup if no active
     login-capable operator exists
   - seed/import must run before the final auth readiness guard; HTTP exposure
     must not be treated as production-ready until the guard passes
12. Add cookie and CSRF policy:
   - dev HTTP session cookies do not set `Secure`
   - prod cookies set `Secure` by default or require an explicit secure-cookie
     property
   - login returns or exposes a session-bound CSRF token for browser callers
   - mutating cookie-backed operator routes require `X-Mass-Csrf-Token`
   - `GET /api/v1/auth/config` and `POST /api/v1/auth/login` are CSRF-exempt
     because they do not require an existing session
   - a deliberately named local-only bypass, if implemented, is rejected in
     `prod`

Acceptance:

- `session` mode:
  - protected operator route without cookie returns 401
  - login with a valid seeded password returns current user and sets cookie
  - login with an invalid password returns 401
  - `/api/v1/auth/me` returns user with cookie
  - logout clears/revokes cookie
  - old operator headers do not authenticate
  - `POST /api/v1/auth/login` is reachable without a prior session
  - auth mode discovery lets the frontend distinguish `dev-header` from
    `session`
  - role/permission changes are reflected by resolving the current
    `PrincipalContext` from IAM on `/auth/me` or session refresh; permissions
    are not snapshotted permanently into the session token
- `GET /api/v1/auth/config` is public and returns the active operator auth
  mode without exposing credential, session token, or password policy material.
- `disabled` mode returns a closed auth config and fails login, `/auth/me`, and
  logout closed.
- `dev-header` mode:
  - existing header-based tests remain cheap
  - admin/viewer/custom fixtures still work
- `prod` profile cannot silently run with implicit `ops-admin`.
- dev/prod seed import accepts `passwordHash` and rejects plaintext `password`.
- dev HTTP can complete a browser login flow; prod defaults to secure cookie
  behavior.
- `prod + session` rejects any temporary CSRF bypass property; a local-only
  bypass must be impossible to enable in prod.
- Mutating cookie-backed operator routes reject missing or invalid CSRF tokens.
- `OperatorCredentialStoreContractTest` proves shared behavior for in-memory
  and JDBC implementations.
- The prod bootstrap path is a one-shot seed/import allowance, not a long-term
  empty-operator runtime mode.
- `prod + dev-header` requires
  `mass.auth.operator.allow-unsafe-dev-header-in-prod=true`; the property name
  appears in the failing startup message.

Suggested verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,InMemoryOperatorCredentialStoreContractTest,JdbcOperatorCredentialStoreContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AUTH-2 Frontend Login / Logout Flow

Goal: make the control console behave like a real login-based app without
turning dev/test convenience into production trust.

Implementation shape:

1. Add `/login` public route.
2. Implement backend `AuthProvider.login()` against
   `POST /api/v1/auth/login`.
3. Update `requestJson` so operator-console requests include credentials only
   in `session` mode.
   - requests with `submitterCredential` keep SDK API-key auth only
   - requests with `includeOperatorAuth=false` must not attach browser session
     credentials
4. Stop sending `X-Mass-User-Mode` when backend auth mode is `session`.
5. Keep operator-mode select only for `dev-header` mode.
6. Add logout action in the header that calls `/api/v1/auth/logout` and clears
   frontend auth state.
7. Update router guard:
   - unauthenticated protected route redirects to `/login`
   - authenticated user visiting `/login` returns to main console
8. Load backend auth mode from `GET /api/v1/auth/config`; AUTH-2 depends on
   AUTH-1 delivering this endpoint.

Acceptance:

- Opening the console unauthenticated shows the login page.
- Login with `userId` and password moves into the main console and loads the
  current user.
- Invalid credentials stay on the login page with a bounded error message.
- Logout returns to login and subsequent protected calls fail until login.
- Dev-header mode keeps the admin/viewer switch for local verification.
- Session mode hides the admin/viewer dev-header switch and does not send
  operator identity headers.
- SDK API-key and submitter-viewer routes keep their existing credential
  behavior and do not gain browser session credentials by default.
- `includeOperatorAuth=false` and `submitterCredential` calls do not attach
  operator cookies or CSRF headers.
- Frontend tests cover backend login provider and route guard transitions.

Suggested verification:

```powershell
cd frontend
pnpm test -- --run auth provider router
pnpm test -- --run
```

## AUTH-3 Integration Test Strategy

Goal: keep broad integration tests fast while proving the production-shaped
session flow.

Test policy:

- Broad controller tests use `mass.auth.operator.mode=dev-header`.
- Existing header-based permission tests remain valid as dev-header tests.
- Add a focused backend session suite for login / me / logout / prod header
  rejection.
- Add one Boot-shell session-flow E2E only if the existing Boot-shell E2E
  harness can inject `session` auth mode, operator seed credentials, and cookie
  assertions without adding a new slow fixture. Otherwise prove the same flow
  with a focused `@SpringBootTest` / MockMvc session test.
- Do not retrofit every task, worker, API-key, and console E2E to perform
  login.

Proof cases:

1. `dev-header` mode:
   - `X-Mass-User-Mode=viewer` resolves existing viewer
   - custom header principal can still model limited permissions in tests
2. `session` mode:
   - no cookie -> 401
   - valid seeded password login -> cookie -> route succeeds
   - invalid password -> 401
   - logout -> route fails
   - `X-Mass-User-Mode=admin` alone does not authenticate
3. `prod` profile guard:
   - unsafe dev-header mode fails startup
   - unsafe dev-header mode starts only with
     `mass.auth.operator.allow-unsafe-dev-header-in-prod=true`
   - configured session mode with at least one active hashed operator
     credential starts
   - one-shot bootstrap import starts only when the explicit AUTH-0 bootstrap
     properties are set and exits/fails if the post-seed operator check fails
   - CSRF local bypass fails startup in `prod + session`
   - seed with plaintext `password` fails in dev/prod

Suggested verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,ServerControlPlaneStoreConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-server -am -DskipTests compile
```

## AUTH-4 Later Credential Hardening

Goal: improve credential lifecycle and external identity without changing
route/session semantics.

The first slice already uses real password-hash verification. Later hardening is
about lifecycle and identity provider maturity, not replacing fake auth.

Possible paths:

- password rotation / admin reset workflow
- credential expiry and lockout policy
- external OIDC verifier mapped into existing `UserRecord`
- reverse-proxy authenticated principal verifier, explicitly configured and
  not accepted by default

Acceptance for future hardening:

- credential material is hashed or externally verified
- disabled users cannot authenticate
- later credential lifecycle changes do not change the first-slice rule that
  role and permission changes are resolved from IAM on `/auth/me` or session
  refresh
- no dev/prod seed/import path accepts plaintext passwords
- production profile refuses external header-based identity unless an explicit
  trusted-proxy verifier is configured

## Cross-Roadmap Touchpoints

- `xa-mass-server/doc/roadmap/IDENTITY_ACCESS_ROADMAP.md`
  - owns IAM users, roles, permissions, API-key application/lifecycle, usage
    ledger, and submitter viewer sessions
  - this roadmap consumes operator IAM identity; it does not replace IAM
- `doc/archive/xa-mass-server/2026-06-04_SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md`
  - historical context for store wiring and prod memory fallback discipline
  - not an active execution roadmap
- `xa-mass-server/README.md` and `doc/INFRA_TRUTH_LAYERS.md`
  - catalog persistence is implemented owner truth; operator session rollout
    must not redefine catalog persistence except for route authorization
    behavior
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md`
  - must be updated when `/api/v1/auth/login` is added or when auth route modes
    change

## Completion Criteria

The roadmap is complete only when:

1. `prod` no longer defaults to implicit `ops-admin`.
2. `prod` no longer accepts dev operator headers by default.
3. Login, `/auth/me`, and logout run through a real session contract.
4. Frontend has a login page and logout action.
5. Broad tests can still use explicit dev-header mode.
6. Focused tests prove session login/logout and prod header rejection.
7. API inventory and server README reflect current behavior.
8. Dev/prod seed/import accepts only password hashes for operator password
   credentials.
9. There is no remaining simulated operator verifier in `session` mode.
10. Operator credential lifecycle has an explicit server-owned store/table and
    is not hidden in IAM user attributes.
11. Public login/config route access, cookie security, and CSRF behavior are
    documented and covered by focused tests.
12. `OperatorCredentialStore` memory/JDBC behavior is covered by a shared
    contract test.
13. `requestJson` credential inclusion is scoped to operator-console session
    mode and does not alter SDK API-key route behavior.
14. Prod bootstrap and unsafe override properties are deliberately named,
    guarded, and visible in startup failure messages.
