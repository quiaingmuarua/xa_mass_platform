# Server Operator Auth Prod Trust Hardening Inventory

Status: current code inventory for
`SERVER_OPERATOR_AUTH_PROD_TRUST_HARDENING_ROADMAP.md`; AUTH-1 through AUTH-5
facts are reflected. Deferred credential lifecycle hardening remains a
successor direction, not a current mainline gap.

## Decisions

- Operator auth mode defaults:
  - `dev`: `mass.auth.operator.mode=dev-header`
  - `prod`: `mass.auth.operator.mode=session`
  - focused tests may explicitly select `dev-header`, `session`, or `disabled`
- Public auth config endpoint:
  - `GET /api/v1/auth/config`
  - returns only non-sensitive browser contract flags
  - no credential, session token, or password policy material
- Operator credential seed shape:
  - `userId`
  - `passwordHash`
  - optional `hashAlgorithm` only if the chosen encoder does not embed it
  - no plaintext `password`
- Operator credential store shape:
  - owner: `xa-mass-server`
  - contract: `OperatorCredentialStore`
  - table: `xa_operator_credential`
  - table location: server-control-plane migration directory
  - password material must not be stored in `UserRecord.attributes`
- First active login-capable operator bootstrap:
  - `mass.control-plane.seed.enabled=true`
  - `mass.control-plane.seed.mode=apply`
  - `mass.control-plane.seed.operator-credentials-location=<resource>`
  - optional one-shot unlock:
    `mass.auth.operator.bootstrap.allow-empty-before-seed=true`
  - final prod auth readiness must re-check after seed import
- Session defaults:
  - `mass.auth.operator.session.ttl=8h`
  - `mass.auth.operator.session.cookie-secure=false` in dev
  - `mass.auth.operator.session.cookie-secure=true` in prod unless explicitly
    configured
  - CSRF is always enforced for unsafe cookie-backed operator routes in
    `session` mode
  - no local CSRF bypass property exists in this roadmap
- Password hashing dependency:
  - prefer a narrow crypto dependency such as `spring-security-crypto`
  - do not add Spring Security web filter chain for this roadmap

## Server Trust Inputs

| Symbol | Current Owner | Current Behavior | Classification | Target |
| --- | --- | --- | --- | --- |
| `ApiAuthService.USER_MODE_HEADER` / `X-Mass-User-Mode` | `xa-mass-server` | Selects `admin`, `viewer`, `anonymous`, or `custom` operator identity only when `mass.auth.operator.mode=dev-header`. | dev-header trust input | Keep only as dev/test fixture. |
| `ApiAuthService.USER_ID_HEADER` / `X-Mass-User-Id` | `xa-mass-server` | Used by `HeaderPrincipalContextFactory` to create a custom operator principal. | dev-header trust input | Test/dev fixture only. |
| `ApiAuthService.USER_NAME_HEADER` / `X-Mass-User-Name` | `xa-mass-server` | Custom display-name fixture. | dev-header trust input | Test/dev fixture only. |
| `ApiAuthService.USER_EMAIL_HEADER` / `X-Mass-User-Email` | `xa-mass-server` | Custom email fixture. | dev-header trust input | Test/dev fixture only. |
| `ApiAuthService.USER_ROLES_HEADER` / `X-Mass-Roles` | `xa-mass-server` | Custom role-label fixture. | dev-header trust input | Test/dev fixture only; not permission truth. |
| `ApiAuthService.USER_PERMISSIONS_HEADER` / `X-Mass-Permissions` | `xa-mass-server` | Custom permission fixture. | dev-header trust input | Test/dev fixture only. |
| no operator header | `ApiAuthService` | Resolves built-in `ops-admin` only in `dev-header`; `session` and `disabled` are unauthenticated. | dev-header fixture / fail-closed proof | Keep only in `dev-header`; `session` and `disabled` fail closed. |
| `OperatorAuthProperties` | `xa-mass-server` | Owns `dev-header`, `session`, and `disabled` mode selection; prod defaults to `session`. | operator auth mode owner | Keep as explicit trust-mode owner. |
| `OperatorAuthStartupGuard` | `xa-mass-server` | Rejects `prod + dev-header` unless `mass.auth.operator.allow-unsafe-dev-header-in-prod=true` is deliberately set. | prod trust guard | Keep as prod fail-closed guard. |
| `DefaultOperatorPrincipalDirectory` | `xa-mass-server` | Resolves IAM users/roles/permissions to `PrincipalContext`. | operator IAM directory | Remains operator identity/permission source for sessions. |

## Route Catalog Entries

| Route | Current Owner | Current Behavior | Classification | Target |
| --- | --- | --- | --- | --- |
| `GET /api/v1/auth/me` | `AuthController` + `ApiAuthInterceptor` | Auth-only route; current dev-header default returns `ops-admin` without headers. | operator current-user route | `dev-header` keeps fixture behavior; `session` requires cookie after AUTH-3; `disabled` fails closed. |
| `POST /api/v1/auth/logout` | `AuthController` + `ApiAuthInterceptor` | Auth-only route; revokes cookie-backed session in session mode. | operator logout | Keep bounded session command. |
| `GET /api/v1/auth/config` | `AuthController` + `ApiRouteAuthorizationCatalog` | Public route returning non-sensitive auth mode flags. | public browser contract | Keep as frontend auth mode discovery surface. |
| `POST /api/v1/auth/login` | `AuthController` + `ApiRouteAuthorizationCatalog` | Public credential exchange in session mode; sets session cookie and returns CSRF token. | public credential exchange | Keep public but bounded to operator session auth. |
| `SDK_CREDENTIAL_BYPASS` routes | `ApiRouteAuthorizationCatalog` | SDK/API-key credential can bypass operator auth for typed public SDK routes. | SDK ingress | Must not be changed by operator auth mode. |
| `SDK_OR_OPERATOR_ROUTE` routes | `ApiRouteAuthorizationCatalog` | SDK credential first, otherwise operator auth. | mixed SDK/operator read | SDK side remains available in all operator modes. |

## Frontend Header Senders

| File | Current Behavior | Classification | Target |
| --- | --- | --- | --- |
| `frontend/src/api/http.ts` | Sends `X-Mass-User-Mode` only when backend auth config reports dev-header support; attaches session credentials/CSRF only for operator-console session calls. | frontend auth transport owner | Keep SDK API-key and submitter-viewer calls free of operator cookies/CSRF. |
| `frontend/src/auth/backend-auth.ts` | Stores backend auth mode flags and session CSRF token in memory. | frontend auth mode owner | Keep as browser contract projection, not credential truth. |
| `frontend/src/auth/operator-mode.ts` | Stores admin/viewer selection and exposes header value. | frontend dev-header fixture | Visible only in `dev-header`. |
| `frontend/src/layouts/AppHeader.vue` | Shows admin/viewer select only in dev-header mode and exposes logout. | frontend dev-header UI / logout action | Keep mode-aware. |
| `frontend/src/auth/provider.backend.ts` | Loads `/auth/config`, `/auth/me`, posts `/auth/login`, and logs out via `/auth/logout`. | backend auth provider | Keep login/session contract here. |
| `frontend/src/pages/app/LoginPage.vue` | Public operator login page. | frontend session entry | Keep as public console route. |

## Seed And Store Touchpoints

| Symbol | Current Owner | Current Behavior | Classification | Target |
| --- | --- | --- | --- | --- |
| `ControlPlaneSeedImporter` | `xa-mass-server` | Imports catalog, project, submitter, rules, and operator credentials. | control-plane seed importer | Keep operator credentials hash-only. |
| `ControlPlaneSeedImportConfiguration` | `xa-mass-server` | `CommandLineRunner` `@Order(2)`. | seed assembly | Keep before final prod auth readiness guard. |
| `V2__server_operator_iam.sql` | `xa-mass-server` | Creates IAM user/role/permission tables only. | operator IAM schema | Keep IAM profile/role truth separate from credential lifecycle. |
| `V4__server_operator_credentials.sql` | `xa-mass-server` | Creates `xa_operator_credential`. | operator credential schema | Keep server-owned; do not move to platform_infra. |
| `UserRecord.attributes` | `xa-mass-server` | Generic IAM user attributes JSON. | profile metadata | Must not store password hashes. |

## Test Fixtures

| Test Area | Current Behavior | Classification | Target |
| --- | --- | --- | --- |
| `ApiAuthTestSupport.defaultOperatorAuthService()` | Creates header-backed auth service with bootstrap IAM users. | dev-header test fixture | Keep for broad controller tests. |
| `ApiAuthInterceptorTest` | Uses `X-Mass-User-Mode` and custom headers broadly, with focused session/CSRF cases. | dev-header route auth fixture plus session proof | Keep broad tests cheap; keep focused session cases. |
| `AuthControllerTest` | Proves `/auth/config`, `/auth/login`, `/auth/me`, and logout under dev-header/session cases. | controller fixture and session proof | Keep mode-specific coverage focused. |
| Controller tests under `xa-mass-server/src/test/java/com/xa/mass/api/internal` | Most use `ApiAuthTestSupport`. | broad dev-header fixtures | Do not retrofit every test to login. |
| frontend `http` / backend provider tests | Prove dev-header header sending, session CSRF/cookie behavior, login/logout, and SDK/submitter credential isolation. | frontend auth proof | Keep as AUTH-4 regression coverage. |

## Immediate Gaps

No immediate mainline gaps remain for the seeded password-hash session path.
Deferred successor topics: password rotation, lockout, OIDC/trusted proxy
identity, and multi-process operator session storage.
