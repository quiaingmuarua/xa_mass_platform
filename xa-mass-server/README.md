# xa-mass-server

Status: current server owner README.

`xa-mass-server` is the verified runnable entry module and lightweight backend
product skeleton for the current repository mainline.

Use this module for end-to-end validation of:

- Spring Boot HTTP APIs
- the embedded transport adapters, backend-hosted control console, and frontend shell
- SDK-created worker resources, fixture bootstrap inputs, and result write-back

Repository-level startup instructions in [`../xa-mass-testing/VERIFIED_RUNBOOK.md`](../xa-mass-testing/VERIFIED_RUNBOOK.md) are the source of truth.

For current test-layer truth, minimum verification, and CI gate truth, start
with [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md). This README covers
server-owned HTTP/control-console/IAM host behavior plus Boot-shell E2E,
black-box, and host-shell validation assets.

Server-local docs live in [`doc/README.md`](./doc/README.md). Use that index
for the internal HTTP reference and server-owned roadmap notes; verify roadmap
claims against current code before treating them as behavior.

## Current Role

- real Spring Boot entrypoint: `com.xa.mass.server.XaMassServerApplication`
- starts runtime through `xa-mass-embedded-sdk` and directly owns the backend-hosted control console, JSON APIs, IAM/API-key/submitter-viewer host surfaces, and frontend shell under `com.xa.mass.api`
- acts as a reference host and lightweight backend product skeleton; server HTTP/auth/IAM/API-key/project/tenant/user surfaces may evolve for host needs, but they must not redefine engine-kernel semantics or replace SDK contracts as the stable integration boundary
- acts as the HTTP/security host adapter: request headers and routes resolve to `PrincipalContext` plus `AuthorizationRequest`, while authorization truth lives in `xa-mass-embedded-sdk-api` / `xa-mass-embedded-sdk`
- worker, task, and rule resources are created through the embedded SDK runtime
- owner-backed worker control and task-stage evidence HTTP routes adapt to SDK
  operation interfaces; controllers must not call engine handlers or concrete
  owner internals directly

Controller/console ownership now includes:

- REST controller layer
- DTO / request-response boundary
- host-side auth/IAM/API-key/session surfaces
- backend-hosted control console shell
- frontend route serving from built `frontend/dist`

What this module does not own:

- task lifecycle, assignment, retry, terminal, or result-kernel semantics
- the stable integration contract for external workers or embedding callers
- runtime truth defined by transport or sample/demo protocol details

## Task Review Read Model

- task review APIs are server-owned console/read-model surfaces, not engine
  scheduling, result convergence, or terminal-policy truth
- `InternalTaskReviewController` depends on `TaskReviewReadModel`
- the current `memory-local` and `durable-local` profiles wire server-local `TaskReviewStore`,
  `TaskReviewStoreTaskReviewReadModel`, `TaskReviewStoreMaterializer`, and a
  queue-backed writer; it does not use shared `TaskDetailStore` projection
  residue
- `TaskApiController` records accepted append items through
  `TaskReviewReadModelWriter`; server startup listeners record attempt-closed
  and stable-final work notifications into the same read model
- public result reads still use SDK `TaskResultQueryOperations` backed by
  `TaskResultRuntime`; review read-model rows must not source `/results`

## Security Wiring

- operator, submitter credential, and external worker HTTP entrypoints now converge on the shared SDK authorization contract
- `ApiAuthInterceptor` resolves operator principals and forwards route permission checks to `AuthorizationPolicy`
- operator auth mode is explicit through `mass.auth.operator.mode=dev-header|session|disabled`
- `dev-header` keeps explicit local/test `X-Mass-User-Mode` fixtures; `session` and
  `disabled` fail closed for operator headers and missing headers
- each server profile sets `mass.auth.operator.mode` explicitly; blank mode
  resolves to `session` and is not a profile contract
- `dev-header` requires the explicit fixture unlock
  `mass.auth.operator.allow-local-fixture-header=true`
- `GET /api/v1/auth/config` is the public browser contract for operator auth
  mode discovery and exposes only non-sensitive mode flags
- operator password credential lifecycle is server-owned through
  `OperatorCredentialStore` and `xa_operator_credential`; password hashes stay
  out of `UserRecord.attributes`
- control-plane seed import may load operator credentials from
  `mass.control-plane.seed.operator-credentials-location`; seed files must use
  `passwordHash` and plaintext `password` is rejected
- `session` mode fails startup when no active login-capable operator
  credential exists after seed/import
- `POST /api/v1/auth/login` verifies `OperatorCredentialStore` password hashes,
  sets an HttpOnly `XA_MASS_OPERATOR_SESSION` cookie, and returns the
  session-bound CSRF token for mutating cookie-backed operator routes
- `POST /api/v1/auth/logout` revokes the operator session and clears the cookie;
  mutating session-mode operator routes require `X-Mass-Csrf-Token`
- `TaskApiController` keeps the existing HTTP contract but routes submitter credential create checks through the shared policy
- `TaskApiController` now also supports submitter credential task read on `list / detail / messages` through centralized ownership checks derived from the internal task ownership stamp
- `ExternalWorkerApiController` keeps the existing worker HTTP contract but routes worker credential checks through the same policy
- host-side authorization adaptation is centralized in `com.xa.mass.api.auth.ApiAuthorizationService`, including deny-message mapping and structured deny logging
- operator route-to-permission declarations are centralized in `com.xa.mass.api.auth.ApiRouteAuthorizationCatalog`
- named host-side submitter/worker security scenarios are centralized in `com.xa.mass.api.auth.ApiSecurityScenario`
- task ownership read-model derivation is centralized in `com.xa.mass.api.auth.TaskSecurityViewSupport`
- deny diagnostics now consume structured SDK `AuthorizationReasonCode` values instead of parsing string prefixes, while API responses still return explicit human-readable reasons
- task create paths stamp framework-owned ownership metadata into the reserved internal envelope `Task.sharedConfig._massSecurity`
- task read APIs strip `_massSecurity` from HTTP `sharedConfig` and expose the supported ownership read model through `data.security`
- current ownership stamp is intentionally minimal: `createdByPrincipalId` and `createdByPrincipalType`
- `memory-local` may use explicit dev-header trust for local tests, but
  `session` mode is fail-closed and no longer treats missing headers as
  implicit `ops-admin`

Current boundary note:

- identity and API-key policy are server control-plane concerns
- `PrincipalContext + AuthorizationPolicy` is the host authorization bridge
- IAM, API-key usage audit, billing/quota, and console scenario vocabulary do
  not belong in engine scheduling, worker callbacks, or result convergence

Current host security matrix:

| Scenario | Principal surface | Resource/action | Current gate |
| --- | --- | --- | --- |
| `SUBMITTER_TASK_CREATE` | submitter credential | `TASK / CREATE` | `task:create` + project/user scope |
| `SUBMITTER_TASK_VIEW` | submitter credential | `TASK / VIEW` | ownership match against the internal task ownership stamp |
| `SUBMITTER_TASK_APPEND` | submitter credential | `TASK / EDIT` | ownership match + `task:create` + project/event scope |
| `WORKER_REGISTER` | external worker credential | `WORKER / REGISTER` | `worker:poll` + worker binding for worker registration; event/project scope for WorkerGroup declaration; legacy worker event-binding request fields are compatibility input only |
| `WORKER_ONLINE` / `WORKER_HEARTBEAT` / `WORKER_OFFLINE` / `WORKER_POLL` | external worker credential | `WORKER / POLL` | `worker:poll` + worker binding |
| `WORKER_SUBMIT_RESULT` | external worker credential | `WORKER / REPORT_RESULT` | `worker:poll` + worker binding |
| `WORKER_REPORT_CAPABILITY` / `WORKER_REPORT_STATE` / `WORKER_ACK_COMMAND` | external worker credential | `WORKER / POLL` | `worker:poll` + worker binding, plus capability event scope on capability reports |

Current worker-state contract note:

- `report-state(DRAINING)` disables future dispatches to that worker in current
  mainline, but it does not revoke or interrupt already in-flight work
- acknowledging a `DRAIN` worker command to an accepted state converges to the
  same dispatch gate truth
- current re-enable rule is explicit: dispatch stays disabled across failed or
  expired `DRAIN` command outcomes and resumes only after a later
  `report-state(AVAILABLE)`

## Observability

- active logging configuration is `src/main/resources/logback.xml`; there is no
  alternate active JSON logback file
- normal application logs write to `logs/xa-mass-platform.log`
- error-level application logs also write to `logs/xa-mass-platform-error.log`
- API failure events write to `logs/xa-mass-server-api-failure.log` through the
  dedicated `com.xa.mass.api.observability.ServerApiFailureLogger`
- rolling file appenders use size-and-time retention with `maxHistory` and
  `totalSizeCap`; local log directories are runtime output, not repository
  truth
- `SERVER_API_FAILURE` is a server-owned triage lane for final 4xx/5xx
  responses under `/api/v1/**`, `/internal/v1/**`, and `/worker-api/v1/**`
- failure logging is final-status based so direct controller
  `ResponseEntity.status(...).body(ApiResponse.error(...))` paths are captured,
  not only exceptions
- failure events include bounded fields such as method, sanitized path, status,
  failure class, duration, trace id, principal type/id when already resolved,
  origin surface, route authorization class, and required permission
- failure logging must not read or log request bodies, raw query strings,
  `Authorization`, API keys, bearer tokens, cookies, or CSRF tokens; safe
  messages are sanitized independently from response messages
- request trace id lifecycle is owned by `RequestMdcCleanupFilter`, which stores
  the trace id as a request attribute before final failure logging and then
  clears MDC at request exit
- endpoint success rate, status, and latency use Spring Boot Actuator's
  `http.server.requests` metric; the first-pass contract does not add
  hand-rolled endpoint counters, timers, Prometheus registry, or high-cardinality
  tags such as task id, principal id, trace id, raw URL, or query string
- `memory-local` exposes `/actuator/health` and `/actuator/metrics`; inspect endpoint
  aggregates with `/actuator/metrics/http.server.requests`
- `durable-local` exposes `/actuator/health` only by default; broader metrics export is a
  later operator-deployment decision, not enabled implicitly

## Port Model

The default durable-local shell uses one HTTP port plus adapter-specific transport ports:

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `8088` | Spring Boot HTTP port for the backend-hosted control console, `/doc.html`, and JSON APIs |
| `mass.websocket.port` | `18088` | internal WebSocket adapter server port |
| `mass.socket.port` | `18089` | socket adapter server port when `mass.socket.enabled=true` |

Sample clients connect through:

| Property | Default |
| --- | --- |
| `sample.client.websocket-uri` | `ws://localhost:${mass.websocket.port}/ws` |

## Verified Main Entry

Start from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-server/target/classes:integrations/xa-mass-worker-pack/target/classes:sdk/xa-mass-embedded-sdk/target/classes:sdk/xa-mass-embedded-sdk-api/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/socket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.server.XaMassServerApplication
```

After startup:

- HTTP control console: `http://localhost:8088/`
- HTTP tasks view: `http://localhost:8088/tasks`
- HTTP projects view: `http://localhost:8088/resources/projects`
- HTTP workers view: `http://localhost:8088/resources/workers`
- HTTP API docs: `http://localhost:8088/doc.html`
- OpenAPI JSON export: `http://localhost:8088/v3/api-docs`
- WebSocket: `ws://localhost:18088/ws`
- Socket when enabled: `tcp://localhost:18089`

Control-console routing note:

- `/status`, `/status/tasks`, `/status/workers`, and `/config` are redirect aliases only
- the backend-hosted SPA routes above are the primary operator entrypoints
- project now has a dedicated read/control-plane surface under
  `/api/v1/projects/**`, and the console exposes it as a first-class navigation
  entry through `Resources -> Projects`

## Dev Shell Details

Everything below this section is intentionally dev/demo/sample wiring. It is
useful for local validation, Boot-shell E2E, and black-box debugging, but it is
not the stable definition of platform ownership.

### Control-Plane Seed Import

The default server startup path boots a clean platform shell. It does not
create sample catalog, project, submitter, rule, task, WorkerGroup, adapter
node, or worker truth.

New-environment metadata is prepared explicitly through the server-owned
control-plane seed/import runner:

- `mass.control-plane.seed.enabled=false` by default in every profile
- `mass.control-plane.seed.catalog-location` reads event, project, and
  submitter metadata
- `mass.control-plane.seed.rules-location` reads default scheduling rules
- `mass.control-plane.seed.mode=apply` writes metadata; `validate` parses and
  counts without writing

Project/event catalog seed data is validated against event references and
written to the platform-infra `CatalogMetadataStore` before being projected into
the embedded SDK runtime. In JDBC storage modes, later restarts restore
project/event catalog metadata from the durable store without rerunning
seed/import.

Example local seed:

```bash
java -jar xa-mass-server/target/xa-mass-server.jar \
  --mass.control-plane.seed.enabled=true \
  --mass.control-plane.seed.allow-local-fixture-raw-secrets=true \
  --mass.control-plane.seed.catalog-location=file:integrations/samples/dev/scenario/bootstrap.json \
  --mass.control-plane.seed.rules-location=file:integrations/samples/dev/scenario/rules.json
```

WorkerGroups, adapter nodes, workers, task shells, and task items are never
seed/import targets. They must be created through public worker/task APIs or
SDK clients.

To populate the local control console after metadata is seeded, use the
external local scenario launcher:

```bash
node integrations/samples/dev/scenario/launch-workers.mjs
```

That launcher uses public task and worker APIs. It is an integration asset, not
server startup logic.

### Effective Sample Client Startup

For test or explicit fixture paths, embedded sample clients are owned by `xa-mass-worker-pack` and started by:

- `integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/AbstractSampleWorkerClientStarter.java`
- `integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/WebSocketClientStarter.java`

Startup behavior:

- enabled in the default local fixture shell through `sample.client.auto-start=true`
- uses `sample.client.websocket-uri=ws://localhost:${mass.websocket.port}/ws` so the embedded sample clients follow the active WebSocket adapter port
- triggered by `ApplicationReadyEvent`
- shared startup orchestration is adapter-aware; current embedded Java
  worker-pack clients are WebSocket fault/command harness clients only
- discovers sample clients from SDK-registered `Worker` resources
- only opens adapter-matching clients for workers whose concrete `adapterId` matches the starter
- does not read a separate worker JSON client list
- idempotent startup protection through an internal `AtomicBoolean`

### Worker Resource Fixtures

`MockRuntimeDataLoader` is now test-only fixture support for local/E2E startup
data. JSON is only a fixture input format; worker resource creation still goes
through `MassSdkApplication.registerWorker(...)`. Worker scheduling attributes
must be declared directly on worker fixture JSON; the separate WorkerContext
fixture input path has been removed.

Current fixture behavior:

- worker JSON entries are mapped to `WorkerRegistration`
- runtime state fields in JSON such as `Worker.status=ONLINE` are ignored; online state comes from transport liveness
- task JSON fixture bootstrap remains a test-only aggregate fixture input
- rule JSON continues to replace default rules when non-empty
- default `durable-local` profile and `memory-local` profile no longer wire fixture bootstrap at all; those properties are kept in test config only

Default worker fixtures now carry a small executor profile:

- `adapterId=websocket` for WebSocket-backed dev workers
- `onlineStrategy=realtime` for WebSocket-backed dev workers
- worker attributes such as `runtime`, `workerType`, `region`, and `lane`

These labels are dev/E2E routing and observability signals. Production-style
resources should still be created through the SDK resource APIs.

### Sample Worker Execution Behavior

Auto-started sample WebSocket clients behave like lightweight executors:

- task responses use a deterministic small delay with stable jitter, so local runs exercise asynchronous result handling without random flakiness
- `mock.delay.response` remains the explicit override for fault-injection tests
- result payloads include `status` and a sample execution detail field
- result payloads also include `execution` metadata with timing, retry count, task id, message id, project, `adapterId`, and `transportHint`
- result payloads include `workerProfile` metadata with worker id and local sample runtime details

The extra payload fields are server observability data. Lifecycle decisions
still come from the task kernel, attempts, and result status.

### Sample Command Runtime

`xa-mass-server` exposes the worker-pack sample command runtime through normal task execution.

Current command groups:

- `mock.*`: mutate sample worker behavior without changing task-kernel semantics
  - `mock.state.get`
  - `mock.delay.response`
  - `mock.drop.outbound`
  - `mock.task.result.status`
  - `mock.disconnect`
  - `mock.reset`
- `fault.*`: configure worker-fault profile state through the same sample command path
  - `fault.state.get`
  - `fault.execution.profile`
  - `fault.execution.delay`
  - `fault.execution.stall`
  - `fault.result.drop`
  - `fault.result.duplicate`
  - `fault.result.late`
  - `fault.result.malformed`
  - `fault.result.identity`
  - `fault.transport.disconnect`
  - `fault.worker.state.flap`
  - `fault.reset`
- `tool.*`: lightweight utility commands for debugging and demos
  - `tool.time.now`
  - `tool.geo.lookup`
  - `tool.currency.quote`
- `batch`: compose multiple command steps with shared flat context and scalar exports

Transport facts:

- worker debug requests are submitted through `POST /internal/v1/debug/task-invocations:sync` for one-item debug runs, or `POST /api/v1/tasks` + `POST /api/v1/tasks/{taskId}/items` for normal task-backed flows
- fix the selected worker with explicit `sharedConfig.workerGroupId` plus
  `sharedConfig.targetWorkerId`
- command execution stays on normal task lifecycle and does not use a dedicated worker-control side-channel

Example normal task-backed flow:

```json
POST /api/v1/tasks
{
  "project": "demoApp",
  "userId": "itest",
  "sourceRef": "mock-delay-response",
  "sharedConfig": {
    "workerGroupId": "mock-workers",
    "targetWorkerId": "it-worker-0"
  },
  "executionSpec": {
    "batchSize": 1
  }
}
```

```json
POST /api/v1/tasks/{taskId}/items
{
  "eventCode": "mock.delay.response",
  "items": [
    {
      "millis": 500
    }
  ]
}
```

Observability:

- debug submissions return `taskId`
- targeted debug tasks should be inspected through normal task detail plus explicit internal/debug diagnostics when deeper residue inspection is needed
- `mock.disconnect` is designed to close the worker after its task result is sent
- `tool.geo.lookup` and `tool.currency.quote` are simulated helpers and must be treated as fake data sources

## Key Config

| Property | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8088` | HTTP port |
| `mass.websocket.port` | `18088` | WebSocket adapter port |
| `mass.socket.port` | `18089` | Socket adapter port |
| `mass.engine.assignment-retry-delay-millis` | `1000` | delay before the engine retries assignment after a failed or deferred dispatch cycle |
| `mass.engine.runtime-ready-dispatch-idle-backoff-max-millis` | `30000` | max idle backoff for the default runtime-ready dispatch polling fallback policy |
| `mass.engine.lease-watchdog-interval-seconds` | `30` | interval for scanning active task-message leases and expiring stalled in-flight attempts |
| `mass.engine.task-message-lease-seconds` | `300` | lease duration for an in-flight task message before the engine may redispatch it |
| `mass.storage.mode` | `memory` | base server storage mode; normal `memory-local` uses `jdbc-h2`, normal `durable-local` uses `jdbc-sqlite`, and manual property overrides may still select `jdbc-postgres` |
| `mass.storage.jdbc.url` | `jdbc:h2:mem:xa_mass;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false` | JDBC URL used when `mass.storage.mode` is a JDBC mode |
| `mass.storage.jdbc.username` | `sa` | JDBC username |
| `mass.storage.jdbc.password` | empty | JDBC password |
| `mass.runtime.mode` | `memory` | engine runtime backend; normal `memory-local` uses `memory`, while normal `durable-local` uses Redis-backed work/result runtime |
| `mass.runtime.redis.namespace` | `xa:mass:runtime:v1` | Redis namespace prefix when `mass.runtime.mode=redis` |
| `mass.runtime.redis.max-queued-items` | `1000000` | runtime work backpressure cap for the Redis-backed work runtime |
| `mass.transport.node-id` | random UUID | server transport runtime node id; set explicitly when comparing Redis presence across restarts |
| `mass.transport.delivery.store` | `memory` | embedded transport delivery-store backend; `memory` or `redis` |
| `mass.transport.delivery.max-queued-items` | `100000` | total dispatch backlog cap for the resolved transport delivery store |
| `mass.transport.delivery.max-items-per-route` | `10000` | per-route dispatch backlog cap for polling queues and adapter-local route queues |
| `mass.transport.delivery.redis.namespace` | `xa:mass:transport:delivery:v1` | Redis namespace prefix when `mass.transport.delivery.store=redis` |
| `mass.transport.presence.store` | `memory` | embedded transport worker-presence backend; `memory` or `redis` |
| `mass.transport.presence.lease-millis` | `30000` | worker transport presence lease before stale/offline pruning may treat the route as unavailable |
| `mass.transport.presence.redis.namespace` | `xa:mass:transport:presence:v1` | Redis namespace prefix when `mass.transport.presence.store=redis` |
| `mass.control-plane.seed.enabled` | `false` | explicitly apply or validate catalog/rule/credential seed files during startup |
| `mass.control-plane.seed.mode` | `apply` | `apply` writes parsed seed metadata; `validate` parses/counts without writing |
| `mass.control-plane.seed.catalog-location` | empty | resource location for event/project/submitter seed JSON |
| `mass.control-plane.seed.rules-location` | empty | resource location for default rule seed JSON |
| `sample.client.auto-start` | `true` in `memory-local` | auto-start embedded sample clients for the default local fixture shell |
| `sample.client.websocket-uri` | `ws://localhost:${mass.websocket.port}/ws` | target WebSocket adapter address |
| `sample.client.task-result-status` | `SUCCESS` | force sample result frames to `SUCCESS` or `FAILED` |
| `sample.worker.auto-start` | `false` in `memory-local` | keep the external sample supervisor off by default; enable explicitly for the separate cross-process sample shell |

JDBC storage scope:

- `memory-local` is the lightweight local server profile. It uses file-backed H2 for
  control-plane storage and memory runtime/transport so local development is
  resident and inspectable without Redis.
- `durable-local` is the current default no-arg server profile and the
  single-node inspectable durable local baseline. It uses SQLite for
  control-plane storage and Redis for runtime work/result, worker registry,
  transport delivery, and transport presence.
- `durable-local` is fail-closed for infra mode selection: control-plane storage must be
  JDBC-enabled, runtime must be Redis, and transport delivery/presence must be
  Redis. Misconfiguration must fail startup instead of falling back to process
  memory.
- PostgreSQL remains a manual property override path with
  `mass.storage.mode=jdbc-postgres`; it is not one of the normal server
  profiles after profile convergence.
- task-work runtime backend, transport delivery-store backend, and transport
  presence backend are configured separately; `mass.runtime.mode` controls
  engine work/result runtime, `mass.transport.delivery.store` controls dispatch
  queue backend, and `mass.transport.presence.store` controls worker
  reachability evidence
- for restart/recovery diagnosis with local Redis, use Redis for the runtime
  surfaces you want to observe, for example
  `mass.runtime.mode=redis`,
  `mass.transport.delivery.store=redis`, and
  `mass.transport.presence.store=redis`; this preserves runtime queues and
  presence evidence across a server process restart, while expired leases and
  stale presence should still converge through timeout/retry rather than
  requiring every intermediate state to be durable
- root `compose.yaml` is the preferred local distributed-verification shell:
  build the jar first with
  `./mvnw -pl xa-mass-server -am -DskipTests package`, then
  `docker compose up redis server` starts Redis and runs that server jar with
  `durable-local`; it is a validation harness with SQLite control-plane storage and
  Redis runtime, not a production image contract
- backend-parity tests should share one scenario body and vary only
  `mass.runtime.mode` plus backend-specific connection properties; do not copy
  the same runtime semantics into backend-specific duplicate tests
- the non-test Spring Boot entry
  [XaMassServerApplication.java](./src/main/java/com/xa/mass/server/XaMassServerApplication.java)
  defaults to `durable-local` when no active profile is supplied, and respects explicit
  profiles from `-Dspring.profiles.active`, `SPRING_PROFILES_ACTIVE`, command
  line arguments, and normal Spring config
- integration tests should keep using isolated in-memory H2 JDBC URLs so DB
  assertions are repeatable and do not depend on a developer's persisted data
- JDBC storage currently persists task shell truth, rule definitions,
  project/event catalog metadata, the low-frequency principal auth projection
  used by submitter and external worker API-key authentication, server-owned
  API-key application/credential lifecycle rows, operator IAM
  users/roles/bindings, and low-volume API usage ledger rows.
  `SubmitterViewerSessionStore` remains memory-only by design.
- task review DB rows are task opt-in. To write terminal review rows, set
  `sharedConfig.reviewMaterializationMode` to `terminal`; to include
  attempt-level diagnostic rows, set it to `diagnostic`. Leaving the key absent
  uses the current default `OFF`.

Server control-plane store hard rules:

- server-owned API-key lifecycle, IAM/user-role, API usage ledger, and
  submitter-viewer session store decisions stay in `xa-mass-server`
- do not add server API-key, IAM, submitter-viewer session, or usage tables or
  schema concepts to `platform_infra/mass-storage-jdbc`
- project/event catalog metadata schema belongs to
  `platform_infra/mass-storage-jdbc`; `xa-mass-server` owns wiring,
  seed/import, startup restore, and HTTP route exposure, not a parallel
  server-owned catalog migration family
- server-owned schema notes live under
  `src/main/resources/db/schema/server-control-plane`, and executable
  server-owned Flyway SQL lives under
  `src/main/resources/db/migration/server-control-plane`
- server-owned JDBC stores may share the configured JDBC `DataSource`, but
  migration resources and migration execution for server API/IAM/usage schemas
  must be owned by `xa-mass-server`; current execution owner is
  `ServerControlPlaneMigrationRunner` using
  `classpath:db/migration/server-control-plane` and
  `flyway_server_control_plane_schema_history`. Because the generic platform
  control-plane Flyway usually initializes the schema first, the server-owned
  runner baselines its own history table at version `0` before applying server
  migrations.
- submitter-viewer sessions remain memory-only in the current phase; future
  cross-process sharing belongs to runtime/Redis session design, not SQLite or
  JDBC control-plane storage
- durable-local profile must not synthesize memory fallbacks for control-plane storage,
  task work/result runtime, transport delivery, or transport presence. The only
  current explicit memory exception in durable-local-facing server auth/session assembly
  is `SubmitterViewerSessionStore`, because it is volatile viewer session state
  and not durable control-plane truth.
- DB schema changes may require deleting/recreating local DBs in this
  pre-release phase; validation proves clean DB creation and current-schema
  restart behavior, not historical upgrade compatibility
- server memory-local/durable-local catalog reads must not be satisfied by the default/demo
  catalog fallback. The fallback remains explicit local/embedded/test bootstrap
  behavior only.
- server API-key lifecycle storage must project authentication state through
  the embedded SDK's narrow `CredentialAuthProjectionWriter` contract, not
  broad submitter resource operations; API-key lifecycle schema must distinguish
  omitted, wildcard, and bounded project/event scopes with explicit mode fields
  or an equivalent representation
- `TaskMsg`, `TaskMsgAttempt`, runtime queues, leases, worker locks, worker
  registry churn, and heartbeat/presence churn stay in runtime backends, with
  Redis as the current inspectable durable-local runtime backend
- do not use JDBC storage as a cross-task message-status analytics surface;
  large-scale message history, attempt history, heartbeat streams, and failure
  analysis should flow through queues, trace, audit sinks, or downstream
  analytical storage
- trace/audit is a separate infra layer. Future trace data may be queued into a
  trace-owned database writer, but server profile convergence does not add trace
  tables or trace DB ingestion.

Mock-data loading order:

- workers
- rules: non-empty config replaces the current default rules; empty config is treated as no override
- tasks

## Regression Coverage

Mainline stance:

- end-to-end integration coverage is the primary acceptance gate for runtime behavior
- unit tests remain important support coverage, but they are not the main proof for task lifecycle correctness
- Boot-shell E2E is the representative proof surface for host-side mainline
  behavior, including `project`, `submitter`, `worker`, task shell, dispatch
  wiring, and result convergence
- project-level authoritative-vs-representative proof ownership lives in
  [../doc/PROOF_REGISTRY.md](../doc/PROOF_REGISTRY.md); use it before adding
  another server E2E class for a scheduling or lifecycle invariant
- the full scheduling-correctness matrix belongs engine-first; server E2E keeps
  representative assignment, polling, routing, and reuse scenarios
- `ServerSchedulingE2eSuite` is runtime-first and representative; review read-model
  scenarios live in `ServerReviewReadModelResidueSuite`, and generic smoke/support
  cases tagged `secondary-proof` stay out of the mainline suite
- `ServerLifecycleResultConvergenceSuite` asserts task aggregate plus
  `TaskWorkRuntime` stats/lease truth; diagnostic review read-model cases live in
  `ServerReviewReadModelAuditSuite`, while low-standard lifecycle smoke stays tagged
  `secondary-proof`
- `ServerSupportCoverageSuite`, `ServerLifecycleSupportCoverageSuite`, and
  `ServerStorageCompatibilitySuite` are the explicit homes for downgraded
  smoke/support coverage; if a server E2E class lives there, treat it as shell
  confidence or compatibility coverage, not proof ownership
- `ServerMainlineE2eArchitectureGuardTest` is included in the mainline
  scheduling and lifecycle suites to reject projection-first helpers and
  implicit `var` declarations
- `ServerProofOwnershipGuardTest` keeps mainline suite membership registry-backed
  and blocks `secondary-proof` or support-suite coverage from drifting back
  into scheduling, lifecycle, or parity proof suites
- server tests must not treat `com.xa.mass.base.model.*` as a stable host-shell
  API contract
- integration suites are grouped by domain under `src/test/java/com/xa/mass/server/e2e`
- shared HTTP/task polling helpers now live in `src/test/java/com/xa/mass/server/e2e/support/AbstractSampleE2eTest`

What this module proves:

- real Spring Boot host wiring and HTTP contracts
- mainline boundary behavior for `project / submitter / worker / auth/IAM`
- full-chain task shell -> item append -> dispatch -> result ingest ->
  convergence behavior
- public result reads and archive endpoints use SDK `TaskResultQueryOperations`
  backed by `TaskResultRuntime` stable-final rows; controllers must not read
  server-local review rows or retired projection rows for result truth
- representative scheduling scenarios on the real host path, not the full
  competition matrix

What this module should not become:

- a replacement for engine concurrency/acceptance tests
- a place to lock in `base model` as a permanent server-host API surface
- a suite that manufactures mainline scenarios by mutating storage/runtime
  truth directly

Focused verified regression command:

```bash
mvn --% -pl xa-mass-server -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Transport-focused regression command:

```bash
mvn --% -pl xa-mass-server -am -Dtest=WebSocketClientStarterTest,ExternalWorkerPublicContractTraceObservedIntegrationTest,NodeWebSocketWorkerBlackBoxIntegrationTest,NodeSocketWorkerBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Cross-language sample black-box regression:

```bash
./scripts/run-external-worker-samples.sh
```

The script runs `ExternalWorkerParitySuite`, which covers Java and Node workers
across polling, WebSocket, and socket adapters. The suite asserts task
aggregate state, runtime stats, active-lease release, and terminal reason first;
worker output/read-model assertions only support payload parity checks. The
script also verifies that the suite produced real surefire testcase execution,
so suite-wrapper `tests=0` XML cannot silently pass as the black-box gate.

Covered areas:

- `e2e/lifecycle`: create -> approve -> assign -> run -> complete, pause/resume guards, pause-completion, terminate-running, resume-and-complete
- `e2e/results`: failed-result terminal closure, mixed results, callback replay idempotency
- `e2e/assignment`: delayed worker availability and multi-task assignment behavior
- `CrawlerPullWorkerSdkRegistrationIntegrationTest`: SDK-created crawler worker resource, pull connect/poll/result, and terminal read-model verification without sample worker JSON
- `WorkerPackGeoLookupExternalSdkIntegrationTest`: worker-pack `tool.geo.lookup`
  capability registered and executed through the Java SDK/public HTTP path,
  without preseeded worker JSON
- `CatalogMetadataSQLiteRestartIntegrationTest`: SQLite-backed project/event
  catalog metadata is restored without rerunning seed/import or default catalog
  fallback
- `CatalogRestoreWorkerCapabilityViewIntegrationTest`: restored catalog
  metadata enriches worker capability/read views while worker topology remains
  explicitly registered runtime/control-plane truth outside catalog tables
- `e2e/audit`: diagnostic task-state validation and terminal metadata consistency through the real runtime path
- `e2e/assignment`: targeted worker debug, adapter-ambiguity, and storage-compat
  support coverage
- `WebSocketClientStarterTest`: auto-start and idempotent startup behavior
- `SampleWorkerWebSocketClientTest`: task dispatch handling, canonical task-result write-back, delay/drop fault injection, and targeted debug task behavior

## Boot-Shell E2E Map

`xa-mass-server` owns the detailed E2E suite map.

High-signal classes:

- guardrail:
  - `ServerMainlineE2eArchitectureGuardTest`
- lifecycle:
  - `TaskApiLifecycleGuardsIntegrationTest`
  - `TaskApiPauseCompletionIntegrationTest`
  - `TaskApiResumeAndCompleteIntegrationTest`
  - `TaskApiTerminateRunningIntegrationTest`
- assignment, routing, and capacity:
  - `TaskApiMultiTaskAssignmentIntegrationTest`
  - `TaskApiMinimumWorkerGateTraceObservedIntegrationTest`
  - `TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest`
  - `TaskApiRetryRedispatchTraceObservedIntegrationTest`
  - `TaskApiSingleWorkerReuseTraceObservedIntegrationTest`
  - `TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest`
  - `TaskApiWorkerWithoutContextIntegrationTest`
  - `CrawlerPullWorkerSdkRegistrationIntegrationTest`
- results and idempotence:
  - `TaskApiAllMessagesFailedTraceObservedIntegrationTest`
  - `TaskApiCallbackReplayTraceObservedIntegrationTest`
  - `TaskApiFailureResultIntegrationTest`
  - `TaskApiMixedResultsTraceObservedIntegrationTest`
- external worker black-box:
  - `ExternalWorkerParitySuite`
  - `ExternalWorkerPublicContractTraceObservedIntegrationTest`
  - `ExternalWorkerPollingApiIntegrationTest`
  - `NodePollingWorkerBlackBoxIntegrationTest`
  - `NodeWebSocketWorkerBlackBoxIntegrationTest`
  - `NodeSocketWorkerBlackBoxIntegrationTest`
  - `JavaScenarioLauncherBlackBoxIntegrationTest`
- secondary/support only:
  - `SdkTaskApiIntegrationTest`
  - `TaskApiIntegrationTest`
  - `TaskApiTargetedWorkerDebugIntegrationTest`
  - `DevSampleWorkerLauncherIntegrationTest`
  - `ExternalWorkerRealtimeRegistrationIntegrationTest`
  - `H2ExternalWorkerPollingApiIntegrationTest`
  - `PostgresExternalWorkerPollingApiIntegrationTest`
  - `CatalogApiIntegrationTest`
  - `SdkTaskApiIntegrationTest` is retained only for support-level unified
    task API and submitter-credential shell coverage; it is not a lifecycle or
    scheduling mainline proof class
  - `TaskApiIntegrationTest` is retained only for support-level workload-class
    shell coverage; it is not a lifecycle/result mainline proof class
- console and audit:
  - `ControlConsoleRoutingIntegrationTest`
- review read-model support/audit:
  - `TaskApiMultiRoundDispatchIntegrationTest`
  - `TaskApiTerminateReuseIntegrationTest`
  - `TaskApiStateValidationIntegrationTest`

Fixture rules:

- prefer `registerWorker(...)`, `replaceDefaultRules(...)`, `createTaskShell(...)`, `appendTaskItems(...)`, and `executeTaskCommand(..., "SEAL")`
- worker JSON is a fixture input, not runtime truth
- direct `WorkerManager` or broad rule-manager setup writes are not mainline
  E2E setup
- direct `TaskManager`, `TaskShellStore`, or runtime writes stay limited to
  focused white-box assertions, audit-only verification, or deterministic fault
  injection
- new mainline tests should prefer SDK or HTTP surfaces over direct
  `com.xa.mass.base.model.*` manipulation

Scheduling-proof note:

- when a scenario's failure means the wrong worker was selected, excluded, or
  re-selected, strengthen engine acceptance coverage first
- add server E2E when the real risk is that HTTP/SDK/transport wiring changes
  the scheduling outcome
- if the scenario also claims canonical trace visibility, pair it with the
  invariant or scenario entry in [../doc/PROOF_REGISTRY.md](../doc/PROOF_REGISTRY.md)
