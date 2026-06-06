# Server API Observability Inventory

Status: current code inventory and AOB-0 decisions for
[SERVER_API_OBSERVABILITY_ROADMAP.md](./SERVER_API_OBSERVABILITY_ROADMAP.md).

This inventory is the implementation contract for the first pass of endpoint
metrics and `SERVER_API_FAILURE` logging. It records current failure paths,
origin-surface rules, trace-id lifecycle, metrics exposure, and logback
ownership before code changes.

## Current Failure Emitters

| Site | Current Behavior | Failure Coverage | Target |
| --- | --- | --- | --- |
| `RequestMdcCleanupFilter` | Clears MDC, sets `httpMethod`, `httpPath`, and `traceId`, then clears MDC in `finally`. | No failure event today. | Keep as trace-id/MDC owner; also store `traceId` as a request attribute. |
| `ApiLogInterceptor` | Logs request start and clears MDC in `afterCompletion`. | No completion status, duration, or failure class. | Remove MDC cleanup from this interceptor; completion/failure logging belongs to the observability filter. |
| `ApiAuthInterceptor.writeError(...)` | Writes 400/401/403 `ApiResponse.error(...)` before controller execution. | Auth and authorization failures currently bypass `GlobalExceptionHandler`. | Set request failure attributes; final-status filter emits exactly one `SERVER_API_FAILURE`. |
| `GlobalExceptionHandler` | Converts framework/common exceptions to `ApiResponse.error(...)`; only unhandled 500 is logged. | 400/401/403/405/409 are not in a dedicated lane. | Set request failure attributes and sanitized message; final-status filter emits. |
| `ApiRequestSizeGuardFilter` | Writes 413 `ApiResponse.error(...)` directly when content length exceeds limits. | Direct filter failure, no dedicated lane. | Set request failure attributes; final-status filter emits if route is in scope. |
| Controller-local `@ExceptionHandler` methods | Several controllers convert `IllegalArgumentException` to 400 directly. | Not visible to a handler/interceptor-only logger. | Covered by final-status fallback; selected handlers may set richer attributes later. |
| Direct controller `ResponseEntity.status(...).body(ApiResponse.error(...))` | Many controllers return 4xx/5xx errors directly. | Largest current direct-error surface. | Covered by final-status fallback even when no detailed attributes are set. |

## Direct Controller Error Path Inventory

Representative current direct `ApiResponse.error(...)` paths:

| Controller | Direct Error Shape | Target Coverage |
| --- | --- | --- |
| `TaskApiController` | 400/401/403/404/409/429 helper methods and status-based `TaskApiException`. | Final-status fallback, with task id excluded from metric tags and log message sanitizer. |
| `AuthController` | 401 invalid operator credentials. | `originSurface=console`; sanitized auth failure. |
| `SubmitterViewerSessionController` | 401 missing/invalid submitter credential or viewer session; 400 local handler. | `originSurface=submitter-viewer`. |
| `CurrentSubmitterController` | 401 invalid/missing submitter credential. | `originSurface=sdk`. |
| `ApiUsageController` | 401/403 submitter credential failures. | `originSurface=sdk`. |
| `ApiKeyController` / `ApiKeyApplicationController` | 404 not found and 400 local handlers. | `originSurface=console` unless SDK credential route evidence is added later. |
| `IdentityAccessController` | 400/404 IAM operation failures. | `originSurface=console`. |
| `CatalogController` / `ProjectApiController` | 404 catalog/project misses. | `originSurface=sdk` for SDK credential attempts, otherwise `console`. |
| `InternalDebugTaskInvocationController` / `InternalTaskReviewController` | 400/401/403/debug/review failures. | `originSurface=console` for authenticated operator/internal calls. |
| `GlobalConfigController` | Returns `ApiResponse.error(500, ...)` without `ResponseEntity`. | In-scope final 200-with-error is not handled by first-pass status logging; keep as follow-up unless current controller changes the status. |
| `FrontendConsoleController` | 302 redirect and 503 static frontend unavailable. | Out of first pass unless classified as server static resource failure later. |

## Origin Surface Rules

First-pass `originSurface` is derived from route prefix plus available auth
context. It must not rely on browser-only headers.

| Rule | originSurface |
| --- | --- |
| Path starts with `/worker-api/v1/` | `worker-api` |
| Path starts with `/internal/v1/` | `console` |
| Path starts with `/api/v1/submitter-sessions` | `submitter-viewer` |
| Path starts with `/api/v1/submitters/me` or `/api/v1/submitters/me/usage` | `sdk` |
| Path starts with `/api/v1/catalog` and SDK credential attempt exists | `sdk` |
| Path starts with `/api/v1/tasks`, `/api/v1/projects`, or `/api/v1/api-keys/*/usage` and SDK credential attempt exists | `sdk` |
| Authenticated principal has `PrincipalType.OPERATOR` | `console` |
| Authenticated principal has `PrincipalType.SERVICE` and the route is an API-key/submitter route | `sdk` |
| Route is `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/roles/**`, `/api/v1/permissions`, `/api/v1/api-keys/**`, `/api/v1/api-key-applications/**`, `/api/v1/admin/rules/**`, or `/api/v1/runtime/**` without SDK credential attempt | `console` |
| Missing or ambiguous route/auth context | `unknown` |

`submitter-viewer` remains a route/auth-derived surface, not a new
`PrincipalType`. Current public principal types remain `OPERATOR`, `SERVICE`,
and `WORKER`.

## Failure Class Rules

| Status / Source | failureClass |
| --- | --- |
| 401 | `AUTHENTICATION` |
| 403 | `AUTHORIZATION` |
| 400 | `BAD_REQUEST` |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `CONFLICT` |
| 404 | `NOT_FOUND` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 429 | `RATE_LIMIT` |
| 500+ | `UNHANDLED` unless a more specific request attribute is set |

## TraceId And MDC Decision

- `RequestMdcCleanupFilter` remains the single MDC lifecycle owner.
- It creates `traceId`, writes it to MDC, and stores the same value as a
  request attribute before the chain.
- `ApiLogInterceptor.afterCompletion(...)` must stop clearing MDC.
- The server API failure filter reads trace id from the request attribute, not
  only from MDC, so completion logging is robust against future MDC changes.

## Failure Capture Decision

Use a post-chain `OncePerRequestFilter` as the first-pass capture point.

- The filter emits once after downstream processing when the final response
  status is an in-scope 4xx/5xx.
- Auth, exception, request-size, and selected controller paths may set request
  attributes for richer classification.
- Direct controller errors are still covered by final-status fallback when no
  attributes are set.
- A request attribute marks that the failure event has already been emitted to
  avoid duplicate logs.

## Safe Message Rules

`safeMessage` is generated by sanitizer, not copied blindly from response or
exception text.

Allowed:

- fixed reason labels such as `authentication failed`, `authorization failed`,
  `bad request`, `method not allowed`, `not found`, `rate limited`
- bounded sanitized controller messages after removing control characters and
  truncating to a fixed length

Forbidden:

- `Authorization` header
- `X-Mass-Api-Key`
- cookies
- CSRF token
- request body
- raw parser details that include payload fragments
- raw query string values

## Endpoint Metrics Decision

- First implementation uses Spring Boot Actuator/Micrometer
  `http.server.requests`.
- Do not add `micrometer-registry-prometheus` in the first pass.
- Do not add custom endpoint counters/timers unless later evidence proves
  `http.server.requests` cannot provide route-template/status/outcome/duration
  signal.

Allowed tags:

- `method`
- route-template `uri`
- `status`
- `outcome`
- framework exception bucket

Forbidden tags:

- `taskId`
- `workerId`
- `commandId`
- `apiKey` / `keyId`
- session id
- `principalId`
- `traceId`
- raw URL
- raw query string
- request body fields

## Actuator Exposure Decision

- Local/dev exposes `health` and `metrics` so
  `/actuator/metrics/http.server.requests` is inspectable.
- Prod-like profiles expose only `health` by default.
- Prometheus is deferred and must be explicitly enabled in a future slice or
  roadmap.

## Logback Decision

- Active config is `xa-mass-server/src/main/resources/logback.xml`.
- `logback-json.xml` was inactive residue/reference and is removed by this
  roadmap execution; `xa-mass-server/src/main/resources/logback.xml` is the
  single active server logging config.
- The implementation adds the `SERVER_API_FAILURE` logger/appender lane to
  active `logback.xml` before emitting failure events.
- AOB-3 owns retention, error-level file, and removal of inactive
  `logback-json.xml`.
