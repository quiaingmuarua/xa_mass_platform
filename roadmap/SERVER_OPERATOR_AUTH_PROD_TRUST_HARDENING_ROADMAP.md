# Server Operator Auth Prod Trust Hardening Roadmap

Status: implemented mainline; AUTH-0 through AUTH-5 are implemented. AUTH-6 is
a deferred credential-lifecycle successor direction, not a completion blocker
for this roadmap.

This roadmap converges `xa-mass-server` operator-console authentication from
the current dev-header trust shell into a real seeded-password login / session /
logout flow while preserving convenient integration tests.

The first landed implementation slice makes operator trust mode explicit and
fail-closed in `session` / `disabled` modes. The next backend slice introduces
real password-hash verification against seeded operator credentials. The
roadmap intentionally does not include public registration, OAuth / OIDC,
password reset, password change, MFA, or account recovery. Those omissions keep
the first production-shaped auth path small without falling back to fake
authentication.

## Current Code Observations

- `OperatorAuthProperties` now owns explicit
  `mass.auth.operator.mode=dev-header|session|disabled` selection:
  `dev` and broad tests use `dev-header`; `prod` defaults to `session`.
- `ApiAuthService` now resolves operator headers only when the mode is
  `dev-header`. `session` and `disabled` modes fail closed for missing headers,
  `X-Mass-User-Mode`, and custom operator identity headers.
- `prod + dev-header` fails startup unless the deliberately named unsafe
  override `mass.auth.operator.allow-unsafe-dev-header-in-prod=true` is set.
- `AuthController` exposes public `GET /api/v1/auth/config` with non-sensitive
  auth mode flags and public `POST /api/v1/auth/login` for session mode.
  `/api/v1/auth/me` resolves cookie-backed sessions and `/api/v1/auth/logout`
  revokes the session.
- `ApiAuthInterceptor` supports explicit public route catalog entries and
  enforces CSRF for mutating cookie-backed operator routes. SDK credential
  bypass routes do not require operator CSRF.
- `UserRecord` and the `xa_iam_user` table currently model operator identity,
  profile, status, attributes, and role bindings. They do not contain password
  material or a credential lifecycle field.
- `OperatorCredentialStore` now owns separate operator password credential
  lifecycle, backed by `xa_operator_credential` in server-control-plane JDBC
  modes and in-memory storage for dev/test memory mode.
- `ControlPlaneSeedImporter` accepts
  `mass.control-plane.seed.operator-credentials-location`, reads only
  `passwordHash`, and rejects plaintext `password`.
- `OperatorAuthReadinessGuard` makes `prod + session` fail startup when no
  active login-capable operator credential exists after seed/import.
- `frontend/src/auth/provider.backend.ts` loads `/api/v1/auth/config`, logs in
  through `POST /api/v1/auth/login`, stores the returned CSRF token, and logs
  out through `/api/v1/auth/logout`.
- `frontend/src/api/http.ts` sends `X-Mass-User-Mode` only when backend auth
  config reports `operatorHeaderSupported=true`. Session-mode operator-console
  requests use same-origin credentials and CSRF; `submitterCredential` and
  `includeOperatorAuth=false` calls omit operator cookies and CSRF.
- `frontend/src/layouts/AppHeader.vue` exposes the admin/viewer operator-mode
  switch only in backend `dev-header` mode and has a logout action.
- `frontend/src/pages/app/LoginPage.vue` provides the public login route.
- Frontend route guards already distinguish authenticated and unauthenticated
  users through the auth store: protected routes redirect to `/login`, and
  authenticated users visiting `/login` return to the main console.
- Existing server controller tests rely heavily on explicit operator headers.
  That fixture behavior remains available only under explicit `dev-header`.
- The current control-plane seed importer loads catalog, project, submitter,
  rule, and operator credential metadata.
- Current control-plane seed import runs as a `CommandLineRunner` with
  `@Order(2)`, after the server full-stack starter runner at `@Order(0)`.
  Any prod auth readiness guard must make its ordering explicit instead of
  assuming seed has already run.
- `xa-mass-server` now depends on `spring-security-crypto` for password
  hashing only; it does not install the Spring Security web filter chain.

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

Credential-slice verification target:

- introduce a narrow `OperatorCredentialVerifier`
- first implementation verifies password material against a stored password
  hash
- use a standard password hashing component, preferably Spring Security
  `PasswordEncoder` / `DelegatingPasswordEncoder` with bcrypt-compatible hashes
- if Spring Security is used, depend only on the narrow crypto component needed
  for password hashing; do not introduce the Spring Security web filter chain in
  the first credential slice
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
- AUTH-4 depends on this signal to decide whether to send `X-Mass-User-Mode`
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
    exchange route in the first login-capable backend slice.
14. Cookie-backed session requests need an explicit CSRF decision before the
    route is considered production-shaped.
15. `prod` unsafe overrides must have deliberately named properties and focused
    startup-guard tests.
16. Operator auth mode governs operator principal resolution only. SDK API-key
    bypass routes, submitter API-key routes, and worker data-plane credential
    routes must keep their existing credential owners in all operator modes.
17. Password hashing may add a narrow crypto dependency such as
    `spring-security-crypto`; the first backend slices must not introduce the
    Spring Security web filter chain or make Spring Security the route owner.

## Non-Goals

- No plaintext password seed/import in dev or prod.
- No OAuth / OIDC / SSO provider integration in this roadmap phase.
- No public user registration, password reset, account recovery, or invitation
  workflow.
- No password change UI or self-service credential rotation in this roadmap phase.
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

Status: implemented. The sibling inventory records the current trust inputs,
route surface, frontend senders, seed touchpoints, test fixtures, and AUTH-2+
remaining gaps.

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
   - optional status / expiry fields only if they are needed for AUTH-2
     verification
6. Decide the operator credential store shape:
   - store owner: `OperatorCredentialStore`
   - table owner: `xa-mass-server` server-control-plane schema
   - table name: `xa_operator_credential` or a similarly explicit
     operator-credential table
   - lifecycle fields needed for AUTH-2 verification
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
   - CSRF is always enforced for unsafe cookie-backed operator routes in
     `session` mode
   - no local-only CSRF bypass property exists in this roadmap
9. Decide how local developers generate hashes:
   - documented helper command
   - test utility
   - or explicit sample hash checked into a dev-only seed file
10. Use `GET /api/v1/auth/config` as the backend auth mode discovery endpoint.
11. Confirm that dev/prod runtime seed import rejects plaintext `password`.

Acceptance:

- A sibling `SERVER_OPERATOR_AUTH_PROD_TRUST_HARDENING_INVENTORY.md` exists
  before implementation. It classifies:
  - server main-source header trust readers
  - frontend header senders
  - route catalog public/auth-only entries
  - seed/import touchpoints
  - test-only dev-header fixtures
- Current code observations are reflected in this roadmap and inventory.
- The first executable slice has a narrow target and does not require changing
  all tests.
- The credential-store decision is explicit before AUTH-2 starts.
- The login public-route strategy is explicit before AUTH-3 starts.
- The bootstrap import path for the first hashed operator is explicit before
  AUTH-2 prod fail-closed startup guards are implemented.
- AUTH-1 must deliver `GET /api/v1/auth/config`; AUTH-4 must not start until
  that endpoint is available.
- Session TTL and any cookie/CSRF configuration keys are explicit before
  AUTH-3 `OperatorSessionService` is implemented.
- Prod unsafe override property names are explicit before startup guards are
  implemented.
- The password hashing dependency decision is explicit before AUTH-2 starts.

## AUTH-1 Backend Trust Mode And Auth Config

Goal: introduce explicit operator auth modes and make the current trust mode
observable before adding credential/session behavior.

Status: implemented. Current proof:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,OperatorAuthPropertiesTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-06-05: 58 tests passed, 0 failures.

Implementation shape:

1. Add configuration object for `mass.auth.operator.*`.
2. Add mode enum / value object:
   - `dev-header`
   - `session`
   - `disabled`
3. Set profile defaults:
   - `dev`: `dev-header`
   - `prod`: `session`
   - tests: `dev-header` unless a test opts into session mode
4. Add public `GET /api/v1/auth/config`.
5. Mark `/api/v1/auth/config` as public in the route catalog/interceptor.
6. Make `session` and `disabled` modes fail closed for operator header trust:
   - no-header does not resolve `ops-admin`
   - `X-Mass-User-Mode` and custom operator identity headers do not
     authenticate
   - `dev-header` keeps the current `ops-admin`, viewer, anonymous, and custom
     fixture behavior
7. Add prod startup guard:
   - `prod + dev-header` fails unless
     `mass.auth.operator.allow-unsafe-dev-header-in-prod=true` is set
   - `prod + disabled` is allowed only as an explicitly closed operator-console
     state
8. Preserve SDK/API-key route behavior:
   - `SDK_CREDENTIAL_BYPASS` and `SDK_OR_OPERATOR_ROUTE` callers keep their
     existing credential owner
   - disabled/session mode changes only operator principal resolution, not SDK
     API-key ingress or worker data-plane authentication

Acceptance:

- `GET /api/v1/auth/config` is public and returns active auth mode plus only
  non-sensitive browser contract flags.
- `session` mode protected operator route without a session returns 401.
- `session` mode ignores or rejects old operator headers.
- `disabled` mode returns a closed auth config; `/auth/me` and logout fail
  closed, and login either remains unmapped or fails closed until AUTH-3 adds
  the login route.
- `dev-header` mode keeps existing header-based tests cheap.
- `prod` profile cannot silently run with implicit `ops-admin`.
- `prod + dev-header` requires
  `mass.auth.operator.allow-unsafe-dev-header-in-prod=true`; the property name
  appears in the failing startup message.
- SDK API-key and worker data-plane credential routes are not broken by
  operator auth mode selection.

Suggested verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AUTH-2 Operator Credential Store, Seed Import, And Prod Readiness

Goal: add real operator credential lifecycle and make `prod + session` start
only when a login-capable operator exists or when the explicit one-shot seed
bootstrap contract is satisfied.

Status: implemented. Current proof:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=OperatorCredentialStoreTest,OperatorCredentialVerifierTest,OperatorAuthReadinessGuardTest,ControlPlaneSeedImporterTest,ServerControlPlaneStoreConfigurationTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-06-05: 43 tests passed, 0 failures.

Implementation shape:

1. Add `OperatorCredentialStore`:
   - in-memory implementation for dev/test when JDBC is unavailable
   - JDBC implementation for server-control-plane storage modes
   - server-owned table such as `xa_operator_credential`
   - no password material in `UserRecord.attributes`
2. Add `OperatorCredentialVerifier`:
   - resolves only active known operators from IAM
   - verifies submitted password against stored `passwordHash`
   - rejects omitted password, raw shared token, and header-only identity
3. Add the chosen narrow password hashing dependency:
   - prefer `spring-security-crypto` / `PasswordEncoder`
   - do not add Spring Security web filter chain in this roadmap
4. Extend control-plane seed import:
   - add `mass.control-plane.seed.operator-credentials-location`
   - read only `passwordHash`
   - reject plaintext `password`
   - support validate-only and apply modes
5. Add prod readiness guard:
   - `prod + session + missing operator credential verifier` fails closed
   - `prod + session + no active login-capable operator` fails closed
   - one-shot empty-before-seed allowance is accepted only when all AUTH-0
     bootstrap properties are present
6. Define startup ordering explicitly:
   - seed import must run before the final auth readiness check
   - the final readiness check must run before the server is considered
     production-shaped
   - if implemented as runners, the roadmap executor must prove runner order;
     if HTTP exposure before `CommandLineRunner` completion is unacceptable,
     use an earlier lifecycle hook instead of a final `CommandLineRunner`

Acceptance:

- `OperatorCredentialStoreTest` proves shared behavior for in-memory and JDBC
  implementations.
- Clean DB creation covers the operator credential table for SQLite and an
  in-memory H2 CI schema proof where applicable.
- Dev/prod seed import accepts `passwordHash` and rejects plaintext
  `password`.
- `prod + session` can start with at least one active login-capable operator.
- `prod + session` without an active login-capable operator fails closed unless
  the one-shot seed/import allowance is fully configured and passes the
  post-seed readiness check.
- The prod bootstrap path is not a long-term empty-operator runtime mode.

Suggested verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=OperatorCredentialStoreTest,ControlPlaneSeedImporterTest,ServerControlPlaneStoreConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AUTH-3 Backend Session Login / Logout And CSRF Contract

Goal: make login, `/auth/me`, and logout run through a real backend session
contract using the credential verifier from AUTH-2.

Status: implemented. Current proof:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,OperatorCredentialVerifierTest,OperatorAuthPropertiesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-06-05: 39 tests passed, 0 failures.

Implementation shape:

1. Add `OperatorSessionStore` with in-process first implementation.
2. Add `OperatorSessionService`:
   - create session
   - resolve session
   - revoke session
   - enforce `mass.auth.operator.session.ttl`, default `8h`
3. Extend `AuthController`:
   - `POST /api/v1/auth/login`
   - `GET /api/v1/auth/config`
   - `GET /api/v1/auth/me`
   - `POST /api/v1/auth/logout`
4. Mark route access explicitly:
   - `POST /api/v1/auth/login` is public
   - `GET /api/v1/auth/config` is public
   - `/api/v1/auth/me` and `/api/v1/auth/logout` require a valid session in
     `session` mode
5. Add cookie policy:
   - dev HTTP session cookies do not set `Secure`
   - prod cookies set `Secure` by default or require an explicit secure-cookie
     property
6. Add CSRF backend contract:
   - login returns or exposes a session-bound CSRF token for browser callers
   - mutating cookie-backed operator routes require `X-Mass-Csrf-Token`
   - `GET /api/v1/auth/config` and `POST /api/v1/auth/login` are CSRF-exempt
   - a local-only CSRF bypass, if implemented, is rejected in `prod`
7. Resolve current permissions from IAM on `/auth/me` or session refresh; do
   not snapshot permissions permanently into the session token.

Acceptance:

- Login with a valid seeded password returns current user and sets cookie.
- Login with an invalid password returns 401.
- `/api/v1/auth/me` returns user with cookie.
- Logout clears/revokes cookie.
- Old operator headers do not authenticate in `session` mode.
- `POST /api/v1/auth/login` is reachable without a prior session.
- Role/permission changes are reflected by resolving the current
  `PrincipalContext` from IAM on `/auth/me` or session refresh.
- `prod + session` rejects any temporary CSRF bypass property; a local-only
  bypass is impossible to enable in prod.
- Mutating cookie-backed operator routes reject missing or invalid CSRF tokens.

Suggested verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,OperatorSessionServiceTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AUTH-4 Frontend Login / Logout Flow

Goal: make the control console behave like a real login-based app without
turning dev/test convenience into production trust.

Status: implemented. Current proof:

```powershell
.\node_modules\.bin\vitest.CMD run src/auth/provider.backend.test.ts src/api/http.test.ts src/router/guards.test.ts src/pages/app/LoginPage.test.ts src/layouts/AppHeader.test.ts src/router/routes.test.ts
.\node_modules\.bin\vue-tsc.CMD --noEmit
.\node_modules\.bin\vitest.CMD run
```

Result on 2026-06-05: focused AUTH-4 tests passed (20 tests, 0 failures);
frontend typecheck passed; full frontend test suite passed (80 tests, 0
failures).

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
8. Load backend auth mode from `GET /api/v1/auth/config`; frontend login
   depends on AUTH-1 delivering config and AUTH-3 delivering login/logout.

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

## AUTH-5 Integration Test Strategy

Goal: keep broad integration tests fast while proving the production-shaped
session flow.

Status: implemented. Current proof:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=AuthControllerTest,ApiAuthInterceptorTest,OperatorAuthPropertiesTest,OperatorCredentialStoreTest,OperatorCredentialVerifierTest,OperatorAuthReadinessGuardTest,ControlPlaneSeedImporterTest,ServerControlPlaneStoreConfigurationTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-06-05: 81 tests passed, 0 failures.

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

## AUTH-6 Later Credential Hardening

Goal: improve credential lifecycle and external identity without changing
route/session semantics.

Status: deferred successor direction. The current roadmap completes when the
seeded password-hash session path, prod trust guards, frontend login flow, and
focused proof surfaces are implemented. Password rotation, lockout, OIDC, and
trusted-proxy identity are intentionally not part of this execution pass.

AUTH-2 already uses real password-hash verification. Later hardening is about
lifecycle and identity provider maturity, not replacing fake auth.

Possible paths:

- password rotation / admin reset workflow
- credential expiry and lockout policy
- external OIDC verifier mapped into existing `UserRecord`
- reverse-proxy authenticated principal verifier, explicitly configured and
  not accepted by default

Acceptance for future hardening:

- credential material is hashed or externally verified
- disabled users cannot authenticate
- later credential lifecycle changes do not change the AUTH-3 rule that
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
    behavior test.
13. `requestJson` credential inclusion is scoped to operator-console session
    mode and does not alter SDK API-key route behavior.
14. Prod bootstrap and unsafe override properties are deliberately named,
    guarded, and visible in startup failure messages.
