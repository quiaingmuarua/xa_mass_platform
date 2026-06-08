# Server API Observability Roadmap

Status: implemented mainline.

This roadmap makes product-readiness API behavior easy to diagnose without
adding heavyweight observability infrastructure. It combines two lightweight
server-owned lanes:

- endpoint metrics for success rate, request count, status outcome, and latency
- dedicated failure logs for selected server API 4xx/5xx details

The immediate problem is not trace, DB audit, frontend state management, or a
full APM platform. The server needs enough observable evidence to answer:

- which endpoint is failing
- which endpoint is slow
- whether failures are auth/permission, bad request, unsupported route, or
  backend exception
- how to correlate one failed request with normal logs through `traceId`

Read with:

- [../doc/FRONTEND_BACKEND_CONTRACT.md](../doc/FRONTEND_BACKEND_CONTRACT.md)
- [../doc/INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [../xa-mass-server/doc/INTERNAL_API_REFERENCE.md](../xa-mass-server/doc/INTERNAL_API_REFERENCE.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)
- [../frontend/README.md](../frontend/README.md)
- [SERVER_OPERATOR_AUTH_PROD_TRUST_HARDENING_ROADMAP.md](./SERVER_OPERATOR_AUTH_PROD_TRUST_HARDENING_ROADMAP.md)

## Current Code Observations

- `xa-mass-server/src/main/resources/logback.xml` is the active default
  Logback configuration. It writes JSON logs to stdout, normal application logs
  to `logs/xa-mass-platform.log`, error-level logs to
  `logs/xa-mass-platform-error.log`, and server API failure events to
  `logs/xa-mass-server-api-failure.log`.
- Active rolling file appenders use `SizeAndTimeBasedRollingPolicy` with
  `maxHistory` and `totalSizeCap`.
- The inactive `xa-mass-server/src/main/resources/logback-json.xml` residue was
  removed during this roadmap execution so future sessions do not edit the
  wrong config.
- `xa-mass-server/pom.xml` already includes `spring-boot-starter-actuator`.
  Current resource search did not find explicit Prometheus registry dependency
  or `management.endpoints.web.exposure.include` configuration.
- Spring Boot Actuator/Micrometer is the natural lightweight owner for
  endpoint aggregate metrics. Do not hand-roll request counters in SQLite or
  controller code before proving the built-in metrics are insufficient.
- `RequestMdcCleanupFilter` currently populates `httpMethod`, `httpPath`, and
  `traceId` in MDC for each HTTP request and stores `traceId` as a request
  attribute for final-status failure logging.
- `ApiLogInterceptor.preHandle(...)` logs a normal request-start line for every
  API request. It does not log completion status, duration, authenticated
  principal, route authorization category, or failure class.
- MDC cleanup is owned by `RequestMdcCleanupFilter`; `ApiLogInterceptor` does
  not clear MDC.
- `GlobalExceptionHandler` converts common exceptions into `ApiResponse`
  envelopes. It logs only unhandled 500 exceptions; normal 400/401/403/405/409
  responses are not emitted to a dedicated diagnostics lane.
- `ApiAuthInterceptor` writes 400/401/403 errors directly to the response
  before controller execution. Those auth/permission failures are the exact
  console-readiness failures that need a separate operator-friendly log lane.
- Many controllers also return `ResponseEntity.status(...).body(ApiResponse.error(...))`
  directly. A failure logger wired only to auth and exception handlers will miss
  these direct controller-local error responses.
- Frontend real API calls are centralized under `frontend/src/api/*`, but the
  server should not depend on frontend implementation details or browser-only
  headers to record request failures.

## Owner Review

Server owns HTTP behavior, auth, permission enforcement, and server-side request
logging. Frontend owns presentation and client-side error handling, but it must
not become the owner of backend request-failure truth.

This roadmap creates server operational evidence, not a new runtime truth layer:

```text
endpoint metrics = aggregate operational signal
server HTTP failure log lane = detailed operational diagnosis output
trace/audit = separate lifecycle/audit systems
frontend UI = consumer of API responses, not log owner
```

The first target is server HTTP API failures with an explicit surface label.
Console/operator routes and submitter-viewer routes are the first analysis
consumer, but SDK task-producer and worker API surfaces must be classified
separately instead of being mislabeled as frontend failures.

## Boundary Decision

Use Micrometer/Actuator for endpoint aggregates and a dedicated server logger
for request-failure detail.

Metrics answer aggregate questions:

```text
endpoint request count
endpoint status/outcome distribution
endpoint latency total/count/max and later histogram/percentiles if enabled
```

Failure logs answer diagnostic questions:

```text
exact failed request route
safe reason
auth/permission class
traceId
principal id/type
```

The two lanes should share stable route classification and safe labels, but
they must not duplicate each other's job.

Target event shape:

```text
event=SERVER_API_FAILURE
failureClass=AUTHENTICATION|AUTHORIZATION|BAD_REQUEST|METHOD_NOT_ALLOWED|
             CONFLICT|NOT_FOUND|RATE_LIMIT|UNHANDLED
httpMethod
httpPath
status
responseCode
safeMessage
traceId
durationMs
principalId
principalType
routeAuthMode or routeAuthorizationClass
requiredPermission when available
originSurface=console|submitter-viewer|sdk|worker-api|unknown
requestSource=browser|sdk|unknown when safely inferable
```

Do not log request bodies, raw API keys, bearer tokens, cookies, CSRF tokens,
session ids, full query strings with arbitrary values, or large payloads. If a
request identity is needed, prefer route template, method, traceId, principal
id/type, and bounded sanitized message.

`safeMessage` is produced by a dedicated sanitizer. It is not simply the
response message or exception message copied into the log, because response
messages can contain parser details or user-provided values.

## Target Log Files

Current active config should converge to:

```text
logs/xa-mass-platform.log
  normal application logs, JSON, bounded retention

logs/xa-mass-platform-error.log
  ERROR-level server failures, JSON, bounded retention

logs/xa-mass-server-api-failure.log
  server API 4xx/5xx diagnostics, JSON, bounded retention
```

The server API failure lane should receive 4xx and 5xx request-failure events
emitted by a dedicated server logger. It should not be populated by every WARN
or ERROR from unrelated engine/runtime classes.

The dedicated logger/appender lane must exist before the failure emitter is
turned on. Do not land a slice where `SERVER_API_FAILURE` events are emitted
but can only route through the default root appender unless the slice explicitly
records that as a temporary validation-only state.

Retention baseline:

- every file appender has `maxHistory`
- every file appender has `totalSizeCap`
- every rolling file appender uses the non-deprecated Logback
  `SizeAndTimeBasedRollingPolicy` shape
- retention values are property-driven or at least defined once consistently
  in the active config

## Target Endpoint Metrics

Current active metrics should converge to:

```text
/actuator/metrics/http.server.requests
  memory-local endpoint for request count, status/outcome, and duration

/actuator/prometheus
  optional lightweight scrape endpoint when micrometer-registry-prometheus is
  enabled
```

Required endpoint tags must stay low-cardinality:

```text
method
uri route template
status
outcome
exception class bucket when provided by framework
```

Forbidden metrics tags:

```text
taskId
workerId
commandId
apiKey/keyId
session id
raw query string
principalId
traceId
request body fields
```

Success rate should be derived from status/outcome series, not recorded as a
second counter. Latency should use the built-in timer first; custom timers are
allowed only when the inventory proves `http.server.requests` cannot identify
the route surface safely.

## Hard Rules

1. Do not put frontend/API failure logs in SQLite, Redis, runtime stores, or
   trace tables in this roadmap. This is file-log observability.
2. Do not log secrets: `Authorization`, API keys, cookies, CSRF tokens, raw
   request bodies, or unbounded query strings.
3. Do not rely only on browser headers such as `User-Agent`, `Referer`, or
   `Sec-Fetch-*` to decide ownership. They may enrich a record but are not
   stable truth.
4. Do not classify `/worker-api/v1/**` callback/poll/result/report errors as
   frontend failures. If they enter the server API failure lane, they must use
   `originSurface=worker-api`; otherwise they remain in general logs.
5. Do not treat the dedicated failure log as an audit ledger. It is operational
   diagnosis output and may be cleaned by retention.
6. Do not make frontend pages call a new "log error" endpoint for backend 4xx
   and 5xx responses. The server already sees those requests.
7. Do not make normal successful requests noisy in the dedicated failure file.
8. Keep `traceId` stable from `RequestMdcCleanupFilter` through auth
   interceptor, exception handler, and completion logging. A completion/failure
   filter must either own MDC cleanup or read `traceId` from a request
   attribute set before handler/interceptor completion.
9. If logback config is changed, prove the active Spring runtime uses the
   changed config. Do not edit only `logback-json.xml` unless it becomes the
   active config.
10. If frontend behavior changes are needed, update
    `doc/FRONTEND_BACKEND_CONTRACT.md` and frontend tests in the same slice.
    The first implementation should not require frontend changes.
11. Do not hand-roll endpoint success-rate or latency tables while Spring
    Actuator/Micrometer can provide the aggregate signal.
12. Do not use high-cardinality metric tags such as task id, worker id,
    command id, principal id, trace id, raw URL, or raw query string.
13. Do not expose Prometheus or broad actuator endpoints publicly by default.
    Memory-local can expose more; durable-local config must explicitly choose exposed
    endpoints.
14. Metrics are aggregate operational signal. They are not audit, trace,
    runtime truth, or billing truth.

## Non-Goals

- Do not implement client-side JavaScript error capture in this roadmap.
- Do not build a log viewer UI.
- Do not add Elasticsearch/OpenSearch/Loki/Splunk integration.
- Do not move logs into DB-backed audit tables.
- Do not replace `xa-mass-trace`.
- Do not add a full APM stack.
- Do not require Prometheus/Grafana to make the first local metrics slice
  useful.
- Do not solve every noisy backend logger.
- Do not change API response semantics just to improve logging.

## Do Not Start With

Do not start by adding a frontend reporting endpoint, UI log page, or custom DB
metrics table. Start by proving the built-in Actuator/Micrometer endpoint
metrics, then make the server reliably emit safe structured failure records for
requests it already handles.

## AOB-0 Inventory, Failure Classification, And Metrics Baseline

Goal: inventory current request-failure paths, active logging config, and
available Actuator/Micrometer metrics before implementation.

Scope:

- Inventory current request handling paths:
  - `RequestMdcCleanupFilter`
  - `ApiLogInterceptor`
  - `ApiAuthInterceptor`
  - `GlobalExceptionHandler`
  - `ApiRequestSizeGuardFilter`
  - controller-local `@ExceptionHandler` methods
  - direct controller `ResponseEntity.status(...).body(ApiResponse.error(...))`
    paths
- Classify failures by source:
  - operator console API
  - submitter viewer API
  - SDK task producer API
  - worker API
  - public auth config/login routes
  - unknown/unsupported route
- Decide the first-pass `originSurface` rules without requiring frontend code
  changes.
- Produce a route + auth-context mapping table for `originSurface`. First-pass
  rules should cover at least:
  - `/worker-api/v1/**` -> `worker-api`
  - operator/session principal on console routes -> `console`
  - submitter viewer session -> `submitter-viewer`
  - API-key task-producer calls -> `sdk`
  - missing or ambiguous context -> `unknown`
- Decide the `traceId` lifecycle strategy. Preferred decision:
  `RequestMdcCleanupFilter` creates the `traceId`, stores it as a request
  attribute, owns MDC cleanup, and `ApiLogInterceptor.afterCompletion(...)`
  stops clearing MDC.
- Decide profile exposure for Actuator metrics:
  - memory-local should expose `/actuator/metrics/http.server.requests`
  - durable-local profiles must not expose broad actuator endpoints unless the
    profile explicitly opts in, ideally through a separate management surface
- Decide the AOB-2 capture strategy:
  - preferred: a post-chain `OncePerRequestFilter` logs in-scope final 4xx/5xx
    responses and reads request attributes written by auth/exception/guard
    paths
  - fallback: every direct controller-local error path is added to an explicit
    emission inventory
- Inventory active Logback config and decide whether to converge on
  `logback.xml` or rename to `logback-spring.xml`.
- Inventory current Actuator setup:
  - dependencies
  - exposed endpoints
  - whether `http.server.requests` is available
  - whether Prometheus registry is present or should be deferred
- Decide endpoint tag policy and URI template handling.

Acceptance:

- A sibling inventory records every current failure emitter and target
  classification.
- Inventory explicitly says `logback-json.xml` is active, inactive residue, or
  to be merged into the active config.
- Inventory defines which status families and routes enter
  `SERVER_API_FAILURE`.
- Inventory records whether final-status filter coverage is used. If not, it
  lists every direct controller-local error path that must emit explicitly.
- Inventory records the first-pass `originSurface` route + auth-context mapping
  table.
- Inventory records the chosen `traceId` lifecycle strategy and the owner of
  MDC cleanup.
- Inventory records the memory-local and durable-local Actuator exposure policy.
- Inventory records forbidden fields that must not appear in the failure log.
- Inventory records the `safeMessage` sanitizer rules and examples.
- Inventory records metric tag allowlist/denylist.
- Inventory records whether first implementation uses only
  `/actuator/metrics/http.server.requests` or also adds
  `micrometer-registry-prometheus`.
- No behavior change is required in AOB-0.

Verification:

```powershell
rg -n "GlobalExceptionHandler|ApiAuthInterceptor|ApiLogInterceptor|ApiRequestSizeGuardFilter|ResponseEntity\.status|ResponseEntity\.badRequest|ApiResponse\.error|@ExceptionHandler|logback|actuator|micrometer|management.endpoints" xa-mass-server/pom.xml xa-mass-server/src/main/java xa-mass-server/src/main/resources
git diff --check
```

## AOB-1 Endpoint Metrics Exposure

Goal: make endpoint success/error/latency metrics available through the
lightest built-in path.

Scope:

- Enable the minimum safe Actuator metrics endpoints for memory-local inspection.
- Implement the AOB-0 profile exposure decision. durable-local profiles must either
  keep broad actuator endpoints closed or expose only the explicitly approved
  management endpoint set.
- Confirm `http.server.requests` records route-template `uri`, method, status,
  and outcome tags.
- If Prometheus is chosen in AOB-0, add `micrometer-registry-prometheus` and
  expose `/actuator/prometheus` only through explicit management config.
- Add a short server runbook section showing how to inspect:
  - request count by endpoint
  - 4xx/5xx count by endpoint
  - latency by endpoint
- Add guard or focused test preventing high-cardinality custom metric tags.

Acceptance:

- `/actuator/metrics/http.server.requests` is available in intended memory-local
  mode.
- durable-local Actuator exposure matches the AOB-0 decision; broad actuator
  endpoints are not exposed by default.
- Metric tags do not include task id, worker id, command id, principal id,
  trace id, raw URL, raw query string, or request body fields.
- Prod-like exposure is explicit and does not accidentally publish broad
  actuator endpoints.
- No controller code contains hand-rolled request counters.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ServerEndpointMetricsConfigurationTest,ServerMainSourceArchitectureGuardTest" test
rg -n "http.server.requests|micrometer-registry-prometheus|management.endpoints|MeterRegistry|Timer|Counter" xa-mass-server/src/main/java xa-mass-server/src/main/resources xa-mass-server/src/test/java xa-mass-server/pom.xml
```

`ServerEndpointMetricsConfigurationTest` is a must-add or must-update proof in
this slice. `ServerMainSourceArchitectureGuardTest` is an existing guard
surface that should be updated if metrics tag or actuator exposure rules need a
source-level guard. If exact test names differ after AOB-0, update the command
with real focused tests instead of using `-Dsurefire.failIfNoSpecifiedTests=false`.

## AOB-2 Failure Logger Lane And Event Emitter

Goal: create the dedicated failure logger lane and add a single server-owned
request-failure event emitter that catches in-scope final 4xx/5xx responses
without requiring every controller to remember to log.

Scope:

- Add the minimum active Logback logger/appender routing needed for
  `SERVER_API_FAILURE` events to avoid a slice where the emitter only writes to
  the default appender. Full retention/error appender cleanup remains AOB-3.
- Add a small server API observability component, for example
  `ServerApiFailureLogger`.
- Add a post-chain `OncePerRequestFilter` unless AOB-0 proves another central
  hook is safer. The filter logs exactly once after the downstream chain when
  the final response status is an in-scope 4xx/5xx.
- Auth, exception, request-size, and selected direct controller paths should
  set request attributes such as failure class, required permission, and
  sanitized safe message. They should not each write the final log line.
- Final-status fallback must still log direct controller-local
  `ApiResponse.error(...)` responses even when no detailed request attributes
  were set.
- Implement the AOB-0 `traceId` lifecycle decision. Preferred path:
  `RequestMdcCleanupFilter` stores `traceId` on the request, remains the single
  MDC cleanup owner, and `ApiLogInterceptor.afterCompletion(...)` no longer
  clears MDC.
- Add a dedicated `safeMessage` sanitizer. It may map known exceptions and
  validation failures to bounded messages, but must not copy raw parser output,
  headers, tokens, request body content, or arbitrary query values directly
  into the failure log.
- Include traceId, method, sanitized path or route template, status, failure
  class, origin surface, principal when available, required permission when
  available, and sanitized safe message.
- Avoid duplicate logs when the same failure passes through both interceptor
  and exception handler by marking the request after the final event is emitted.

Acceptance:

- 401/403 permission failures from `ApiAuthInterceptor` produce exactly one
  `SERVER_API_FAILURE` event.
- The `SERVER_API_FAILURE` logger is routed to a dedicated appender in the
  active Logback config before events are emitted.
- Direct controller-local `ResponseEntity.status(...).body(ApiResponse.error(...))`
  responses in in-scope routes produce a `SERVER_API_FAILURE` event through
  final-status fallback.
- 400 bad JSON and 405 method-not-allowed produce a bounded failure event.
- 500 unhandled exceptions produce both normal error evidence and one
  server API failure event when the route is in scope.
- Event payload does not include secrets, request bodies, raw parser output, or
  raw response/exception messages that fail sanitizer rules.
- Completion/failure logging still has the same `traceId` created by
  `RequestMdcCleanupFilter`; no interceptor clears it before the final event.
- Existing `ApiResponse` error shapes stay unchanged.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ServerApiFailureLoggingFilterTest,ServerLoggingConfigurationTest,ApiAuthInterceptorTest,GlobalExceptionHandlerTest,ApiRequestSizeGuardFilterTest" test
```

`ServerApiFailureLoggingFilterTest` is a must-add or must-update proof in this
slice. `ApiAuthInterceptorTest` is an existing proof surface to update.
`GlobalExceptionHandlerTest` and `ApiRequestSizeGuardFilterTest` are must-add
proofs unless AOB-0 identifies existing equivalent focused tests.
`ServerLoggingConfigurationTest` starts in this slice as the proof that the
dedicated logger lane is active; AOB-3 extends it for retention and error-file
coverage. If exact existing test names differ after AOB-0, update the command
with real focused tests instead of using `-Dsurefire.failIfNoSpecifiedTests=false`.

## AOB-3 Active Logback Retention And Error File Split

Goal: finish the active server logging config so normal, error, and server API
failure files are bounded and proven.

Scope:

- Update the active Logback config, not an inactive reference file.
- Replace deprecated `SizeAndTimeBasedFNATP` usage with
  `SizeAndTimeBasedRollingPolicy`.
- Add `totalSizeCap` to all rolling file appenders.
- Add `ERROR_FILE` appender for `ERROR` level server logs.
- Verify or complete the `SERVER_API_FAILURE_FILE` appender introduced in
  AOB-2 so it is routed only from the dedicated failure logger.
- Remove inactive `logback-json.xml` residue after the active config owns the
  behavior.

Acceptance:

- `logs/xa-mass-platform.log` still receives normal JSON application logs.
- `logs/xa-mass-platform-error.log` receives ERROR-level logs.
- `logs/xa-mass-server-api-failure.log` receives only
  `SERVER_API_FAILURE` events.
- All rolling file appenders have max file size, max history, and total size
  cap.
- Active config has no deprecated `SizeAndTimeBasedFNATP`.
- Inactive `logback-json.xml` is removed so there is one editable server
  logging config.
- Startup/runtime proof confirms the active config is loaded by inspecting the
  running Logback `LoggerContext` and the actual logger/appender wiring. XML
  string checks alone are not sufficient.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ServerLoggingConfigurationTest" test
rg -n "SizeAndTimeBasedFNATP|totalSizeCap|xa-mass-server-api-failure|SERVER_API_FAILURE" xa-mass-server/src/main/resources xa-mass-server/src/test/java
```

`ServerLoggingConfigurationTest` is a must-add or must-update proof in this
slice. It must verify the active Logback config by reading the runtime Logback
`LoggerContext`, checking that the `SERVER_API_FAILURE` logger has the expected
dedicated appender, and confirming the retention/error-file appender wiring. It
must not rely only on XML string checks or file existence checks.

## AOB-4 End-To-End Metrics And Log Proof

Goal: prove realistic frontend/operator API behavior is visible in aggregate
metrics and detailed failure logs.

Scope:

- Add a lightweight Spring/MockMvc or boot-shell proof that triggers:
  - unauthenticated operator route
  - forbidden operator route
  - bad JSON request
  - unsupported method
- Capture failure output by attaching a Logback `ListAppender` to the
  `SERVER_API_FAILURE` logger. File routing is proven by AOB-3; AOB-4 should
  prove event emission and fields without stdout noise.
- Assert JSON fields exist and forbidden fields do not exist.
- Assert endpoint metrics include route-template/count/status evidence for the
  same request class, without high-cardinality labels.

Acceptance:

- Test proves successful and failed requests are visible in endpoint metrics.
- Test proves representative 401/403/400/405 cases produce
  `SERVER_API_FAILURE`.
- Test proves a successful API request does not write to the dedicated failure
  lane.
- Test proves the dedicated `ListAppender` receives only the expected
  `SERVER_API_FAILURE` events for the selected failure scenarios.
- Test proves `traceId`, method, path, status, and failure class are present.
- Test proves headers/body secrets are absent.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ServerApiFailureLoggingIntegrationTest,ServerEndpointMetricsConfigurationTest,ServerEndpointMetricsIntegrationTest,ServerLoggingConfigurationTest" test
```

`ServerApiFailureLoggingIntegrationTest` is a must-add or must-update proof in
this slice. `ServerEndpointMetricsIntegrationTest` is a must-add proof that
successful and failed API requests appear in `http.server.requests`.
`ServerEndpointMetricsConfigurationTest` and
`ServerLoggingConfigurationTest` should already exist from AOB-1 and AOB-3 by
the time this slice runs.

## AOB-5 Docs, Guards, And Residue Scan

Goal: make future sessions preserve the logging lane and avoid reintroducing
dead config or unsafe payload logging.

Scope:

- Update server README or owner doc with:
  - active log files
  - retention behavior
  - what the server API failure log is for
  - how to inspect endpoint metrics locally
  - whether Prometheus is enabled or deferred
  - what must never be logged
- Update `doc/FRONTEND_BACKEND_CONTRACT.md` only if frontend/backend behavior
  changes. If no frontend change is required, record that the server owns this
  lane.
- Add architecture/source guard for:
  - no deprecated Logback rolling policy in active config
  - failure logger does not log request body/header secrets
  - `SERVER_API_FAILURE` logger name remains routed to dedicated appender
  - endpoint metrics do not introduce high-cardinality tags
- Remove or clearly classify inactive `logback-json.xml` so agents do not edit
  the wrong file.

Acceptance:

- Owner docs describe current active behavior, not target behavior.
- Residue scan finds no active docs saying `logback-json.xml` is the active
  config unless it truly is.
- Guards fail on unsafe/deprecated logging config regression.
- Guards fail on custom endpoint metrics with forbidden high-cardinality tags.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ServerMainSourceArchitectureGuardTest,ServerLoggingConfigurationTest,ServerEndpointMetricsConfigurationTest,ServerEndpointMetricsIntegrationTest,ServerApiFailureLoggingIntegrationTest" test
rg -n "logback-json|SizeAndTimeBasedFNATP|Authorization|Cookie|CSRF|request body|SERVER_API_FAILURE|MeterRegistry|Timer|Counter|http.server.requests" xa-mass-server/src/main/resources xa-mass-server/src/main/java xa-mass-server/src/test/java xa-mass-server/README.md doc/FRONTEND_BACKEND_CONTRACT.md
git diff --check
```

`ServerMainSourceArchitectureGuardTest` is the existing guard surface for this
slice. The logging and metrics proof tests named here must have been added or
updated by earlier slices; do not run this command with
`-Dsurefire.failIfNoSpecifiedTests=false`.

## Suggested Implementation Order

1. AOB-0 inventory, failure classification, and metrics baseline.
2. AOB-1 endpoint metrics exposure.
3. AOB-2 failure logger lane and request failure event emitter.
4. AOB-3 active Logback retention and error file split.
5. AOB-4 end-to-end metrics and log proof.
6. AOB-5 docs, guards, and residue scan.

## Completion Criteria

This roadmap can be marked complete only when:

- server exposes endpoint aggregate metrics for request count, success/error
  outcome, and latency through Actuator/Micrometer
- metric tags stay low-cardinality and do not include task id, worker id,
  principal id, trace id, raw URL, or request body fields
- server emits structured `SERVER_API_FAILURE` events for in-scope
  frontend/server API failures
- auth interceptor, exception handler, and request-size guard paths are covered
  or explicitly classified out
- direct controller-local error responses are covered by final-status logging
  or explicitly inventoried as out of scope
- active Logback config produces normal, error, and server API failure files
  with bounded retention
- unsafe fields are not logged
- `logback-json.xml` residue is removed, merged, or explicitly classified
- owner docs describe current behavior
- focused tests and guards prove metrics, the failure lane, and retention
  config

## Risks

| Risk | Mitigation |
| --- | --- |
| Failure log leaks credentials or payload | Log only method/path/status/failure class/principal/trace id and safe bounded message; add tests/guards for forbidden headers/body |
| Metrics cardinality explodes | Use route-template URI tags and forbid ids, principals, trace ids, raw query strings, and payload fields as tags |
| Prometheus exposure becomes an accidental public surface | Keep actuator exposure explicit by profile/config and verify durable-local behavior |
| SDK or worker API failures are mislabeled as frontend failures | AOB-0 classifies route/auth/caller surfaces before implementation |
| Duplicate failure events make analysis noisy | Central emitter marks/logs once per request or call site owns exactly one emission |
| Agents edit inactive `logback-json.xml` | AOB-0/AOB-3 must identify active config and AOB-5 removes or classifies residue |
| Failure events are emitted before a dedicated appender exists | AOB-2 includes the minimum active logger/appender lane; AOB-3 only completes retention and error-file cleanup |
| File logs grow unbounded | Every rolling file appender gets `maxHistory` and `totalSizeCap` |
| Logging becomes trace/audit substitute | Hard rules keep this as operational diagnosis output only |
