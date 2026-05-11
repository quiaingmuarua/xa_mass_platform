# xa-mass-server

Status: current server owner README.

`xa-mass-server` is the verified runnable entry module for the current repository mainline.

Use this module for end-to-end validation of:

- Spring Boot HTTP APIs
- the embedded transport adapters, backend-hosted control console, and frontend shell
- SDK-created worker resources, fixture bootstrap inputs, and result write-back

Repository-level startup instructions in [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md) are the source of truth.

## Current Role

- real Spring Boot entrypoint: `com.xa.mass.server.XaMassServerApplication`
- starts runtime through `xa-mass-sdk` and directly owns the backend-hosted control console, JSON APIs, and frontend shell under `com.xa.mass.api`
- acts as a reference host and validation shell; server HTTP/auth/project/tenant/user surfaces may evolve for host needs, but they must not redefine engine-kernel semantics or replace SDK contracts as the stable integration boundary
- acts as the HTTP/security host adapter: request headers and routes resolve to `PrincipalContext` plus `AuthorizationRequest`, while authorization truth lives in `xa-mass-sdk-api` / `xa-mass-sdk`
- worker, task, and rule resources are created through the embedded SDK runtime
- default `dev` startup can seed a mainline in-process demo shell through `DevDemoBootstrapConfiguration` when `mass.demo.bootstrap.enabled=true`
- the default dev demo shell registers demo projects, events, submitters, workers, contexts, and seeded task shells strictly through SDK-native APIs
- the same default dev path now auto-starts embedded sample WebSocket clients against the local adapter so those SDK-registered demo workers actually go `ONLINE` and process the seeded demo tasks
- JSON fixture bootstrap remains a test-only input path; packaged fixture files are not the default dev startup source anymore
- default `dev` sample bootstrap exposes a sample-only write surface at `/sample-api/bootstrap/*`
  protected by `X-Sample-Bootstrap-Key`

Controller/console ownership now includes:

- REST controller layer
- DTO / request-response boundary
- backend-hosted control console shell
- frontend route serving from built `frontend/dist`

## Security Wiring

- operator, SDK submitter, and external worker HTTP entrypoints now converge on the shared SDK authorization contract
- `ApiAuthInterceptor` resolves operator principals and forwards route permission checks to `AuthorizationPolicy`
- `TaskApiController` keeps the existing HTTP contract but routes SDK submitter create checks through the shared policy
- `TaskApiController` now also supports SDK submitter task read on `list / detail / messages` through centralized ownership checks derived from the internal task ownership stamp
- `ExternalWorkerApiController` keeps the existing worker HTTP contract but routes worker credential checks through the same policy
- host-side authorization adaptation is centralized in `com.xa.mass.api.auth.ApiAuthorizationService`, including deny-message mapping and structured deny logging
- operator route-to-permission declarations are centralized in `com.xa.mass.api.auth.ApiRouteAuthorizationCatalog`
- named host-side submitter/worker security scenarios are centralized in `com.xa.mass.api.auth.ApiSecurityScenario`
- task ownership read-model derivation is centralized in `com.xa.mass.api.auth.TaskSecurityViewSupport`
- deny diagnostics now consume structured SDK `AuthorizationReasonCode` values instead of parsing string prefixes, while API responses still return explicit human-readable reasons
- task create paths stamp framework-owned ownership metadata into the reserved internal envelope `Task.sharedConfig._massSecurity`
- task read APIs strip `_massSecurity` from HTTP `sharedConfig` and expose the supported ownership read model through `data.security`
- current ownership stamp is intentionally minimal: `createdByPrincipalId` and `createdByPrincipalType`
- default dev trust remains intentionally permissive in this phase; this change is framework convergence, not production trust tightening

Current host security matrix:

| Scenario | Principal surface | Resource/action | Current gate |
| --- | --- | --- | --- |
| `SUBMITTER_TASK_CREATE` | SDK credential | `TASK / CREATE` | `task:create` + project/user scope |
| `SUBMITTER_TASK_VIEW` | SDK credential | `TASK / VIEW` | ownership match against the internal task ownership stamp |
| `SUBMITTER_TASK_APPEND` | SDK credential | `TASK / EDIT` | ownership match + `task:create` + project/event scope |
| `WORKER_REGISTER` | external worker credential | `WORKER / REGISTER` | `worker:poll` + worker binding + event/project scope |
| `WORKER_CONTEXT_REGISTER` | external worker credential | `WORKER_CONTEXT / REGISTER` | `worker:poll` + worker binding + project scope |
| `WORKER_ONLINE` / `WORKER_HEARTBEAT` / `WORKER_OFFLINE` / `WORKER_POLL` | external worker credential | `WORKER / POLL` | `worker:poll` + worker binding |
| `WORKER_SUBMIT_RESULT` | external worker credential | `WORKER / REPORT_RESULT` | `worker:poll` + worker binding |

## Port Model

The default dev shell uses one HTTP port plus adapter-specific transport ports:

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `8088` | Spring Boot HTTP port for the backend-hosted control console, `/doc.html`, and JSON APIs |
| `mass.websocket.port` | `18088` | internal WebSocket adapter server port |
| `mass.socket.port` | `18089` | socket adapter server port when `mass.socket.enabled=true` |

Sample clients connect through:

| Property | Default |
| --- | --- |
| `sample.client.websocket-uri` | `ws://localhost:${mass.websocket.port}/ws` |
| `sample.client.socket-host` | `127.0.0.1` |
| `sample.client.socket-port` | `18089` |

## Verified Main Entry

Start from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-server/target/classes:xa-mass-worker-pack/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/socket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.server.XaMassServerApplication
```

After startup:

- HTTP control console: `http://localhost:8088/`
- HTTP tasks view: `http://localhost:8088/tasks`
- HTTP projects view: `http://localhost:8088/resources/projects`
- HTTP workers view: `http://localhost:8088/resources/workers`
- HTTP API docs: `http://localhost:8088/doc.html`
- WebSocket: `ws://localhost:18088/ws`
- Socket when enabled: `tcp://localhost:18089`

Control-console routing note:

- `/status`, `/status/tasks`, `/status/workers`, and `/status/rules` are redirect aliases only
- the backend-hosted SPA routes above are the primary operator entrypoints
- project now has a dedicated read/control-plane surface under
  `/api/v1/projects/**`, and the console exposes it as a first-class navigation
  entry through `Resources -> Projects`

## Dev Demo Bootstrap

When `spring.profiles.active=dev` and `mass.demo.bootstrap.enabled=true`, the server starts with a mainline demo shell instead of an external fixture bootstrap.

Current default demo shape:

- projects: `demoApp`, `demoOps`
- events: `demo.dispatch`, `demo.dispatch.gb`
- submitter credentials:
  - `demo-app-key`
  - `demo-ops-key`
  - `demo-admin-key`
- workers: `36` SDK-registered demo workers with lane-tagged contexts
- tasks: `12` seeded tasks with `1500` items each by default
- task mix per project: active/running backlog, pending approval, paused, and blocked states

The demo bootstrap intentionally stays inside server-owned dev wiring. It does not add new SDK product semantics and it does not rely on test-only JSON aggregate fixtures.

## Effective Sample Client Startup

For test or explicit fixture paths, embedded sample clients are owned by `xa-mass-worker-pack` and started by:

- `xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/AbstractSampleWorkerClientStarter.java`
- `xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/WebSocketClientStarter.java`
- `xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/SocketClientStarter.java`

Startup behavior:

- enabled in the default dev demo shell through `sample.client.auto-start=true`
- uses `sample.client.websocket-uri=ws://localhost:${mass.websocket.port}/ws` so the embedded sample clients follow the active WebSocket adapter port
- triggered by `ApplicationReadyEvent`
- shared startup orchestration is adapter-aware; websocket and socket specifics stay in their own starters
- discovers sample clients from SDK-registered `Worker` resources
- only opens adapter-matching clients for workers whose concrete `adapterId` matches the starter
- does not read a separate worker JSON client list
- idempotent startup protection through an internal `AtomicBoolean`

## Worker Resource Fixtures

`MockRuntimeDataLoader` is now test-only fixture support for local/E2E startup data. JSON is only a fixture input format; resource creation still goes through `MassSdkApplication.registerWorker(...)` and `registerWorkerContext(...)`.

Current fixture behavior:

- worker JSON entries are mapped to `WorkerRegistration`
- worker-context JSON entries are mapped to `WorkerContextRegistration`
- runtime state fields in JSON such as `Worker.status=ONLINE` are ignored; online state comes from transport liveness
- task JSON fixture bootstrap currently still carries aggregate test input and is pending migration to shell-create plus item-batch fixture shape
- rule JSON continues to replace default rules when non-empty
- default `dev` profile no longer wires fixture bootstrap at all; those properties are kept in test config only

Default worker fixtures now carry a small executor profile:

- `adapterId=websocket` for WebSocket-backed dev workers
- `onlineStrategy=realtime` for WebSocket-backed dev workers
- worker attributes such as `runtime`, `workerType`, `region`, and `lane`
- worker-context attributes such as `country` and `network`

These labels are dev/E2E routing and observability signals. Production-style
resources should still be created through the SDK resource APIs.

## Sample Worker Execution Behavior

Auto-started sample WebSocket clients behave like lightweight executors:

- task responses use a deterministic small delay with stable jitter, so local runs exercise asynchronous result handling without random flakiness
- `mock.delay.response` remains the explicit override for fault-injection tests
- result payloads include `status` and a sample execution detail field
- result payloads also include `execution` metadata with timing, retry count, task id, message id, project, `adapterId`, and `transportHint`
- result payloads include `workerProfile` metadata with worker id and local sample runtime details

The extra payload fields are server observability data. Lifecycle decisions
still come from the task kernel, attempts, and result status.

## Sample Command Runtime

`xa-mass-server` exposes the worker-pack sample command runtime through normal task execution.

Current command groups:

- `mock.*`: mutate sample worker behavior without changing task-kernel semantics
  - `mock.state.get`
  - `mock.delay.response`
  - `mock.drop.outbound`
  - `mock.task.result.status`
  - `mock.disconnect`
  - `mock.reset`
- `tool.*`: lightweight utility commands for debugging and demos
  - `tool.time.now`
  - `tool.geo.lookup`
  - `tool.currency.quote`
- `batch`: compose multiple command steps with shared flat context and scalar exports

Transport facts:

- worker debug requests are submitted through `POST /internal/v1/debug/task-invocations:sync` for one-item debug runs, or `POST /api/v1/tasks` + `POST /api/v1/tasks/{taskId}/items` for normal task-backed flows
- fix the selected worker with `sharedConfig.targetWorkerId`
- command execution stays on normal task lifecycle and does not use a dedicated worker-control side-channel

Example normal task-backed flow:

```json
POST /api/v1/tasks
{
  "project": "demoApp",
  "userId": "itest",
  "sourceRef": "mock-delay-response",
  "sharedConfig": {
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
| `mass.engine.lease-watchdog-interval-seconds` | `30` | interval for scanning active task-message leases and expiring stalled in-flight attempts |
| `mass.engine.task-message-lease-seconds` | `300` | lease duration for an in-flight task message before the engine may redispatch it |
| `mass.storage.mode` | `memory` | server storage mode; use `jdbc-h2` for local/CI verification or `jdbc-postgres` through `mass-storage-jdbc` for durable control-plane storage |
| `mass.storage.jdbc.url` | `jdbc:h2:mem:xa_mass;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false` | JDBC URL used when `mass.storage.mode` is a JDBC mode |
| `mass.storage.jdbc.username` | `sa` | JDBC username |
| `mass.storage.jdbc.password` | empty | JDBC password |
| `mass.transport.delivery.store` | `memory` | embedded transport delivery-store backend; `memory` or `redis` |
| `mass.transport.delivery.max-queued-items` | `100000` | total dispatch backlog cap for the resolved transport delivery store |
| `mass.transport.delivery.max-items-per-route` | `10000` | per-route dispatch backlog cap for polling queues and adapter-local route queues |
| `mass.transport.delivery.redis.namespace` | `xa:mass:transport:delivery:v1` | Redis namespace prefix when `mass.transport.delivery.store=redis` |
| `sample.client.auto-start` | `true` in `dev` | auto-start embedded sample clients for the default dev demo shell |
| `sample.client.websocket-uri` | `ws://localhost:${mass.websocket.port}/ws` | target WebSocket adapter address |
| `sample.client.socket-host` | `127.0.0.1` | target socket adapter host |
| `sample.client.socket-port` | `18089` | fallback socket adapter port when no bound-port override is published |
| `sample.client.task-result-status` | `SUCCESS` | force sample result frames to `SUCCESS` or `FAILED` |
| `sample.bootstrap.api-key` | `dev-bootstrap-key` | sample-only bootstrap credential for `/sample-api/bootstrap/*` |
| `sample.worker.auto-start` | `false` in `dev` | keep the external sample supervisor off by default; enable explicitly for the separate cross-process sample shell |

JDBC storage scope:

- PostgreSQL is the intended control-plane truth store; H2 remains a local/CI
  verification dialect for the same JDBC storage path
- default runtime stays `memory`; opt into H2 explicitly with
  `mass.storage.mode=jdbc-h2`
- opt into PostgreSQL with `mass.storage.mode=jdbc-postgres`
- task-work runtime backend and transport delivery-store backend are configured
  separately; `mass.runtime.mode` controls engine work runtime, while
  `mass.transport.delivery.store` controls dispatch queue backend
- server-local persistence can use the `h2` profile together with the runnable
  server profile, for example `-Dspring.profiles.active=dev,h2`; this writes to
  `./data/xa-mass-h2/xa_mass` by default through `application-h2.yml`
- the non-test Spring Boot entry
  [XaMassServerApplication.java](/D:/code_project/geekrun/xa_mass_platform/xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java)
  already supports this profile directly; local persistent H2 verification does
  not require a separate test-only bootstrap path
- local PostgreSQL verification can use the `postgres` profile together with the
  runnable server profile, for example `-Dspring.profiles.active=dev,postgres`
- integration tests should keep using isolated in-memory H2 JDBC URLs so DB
  assertions are repeatable and do not depend on a developer's persisted data
- JDBC storage persists task truth, worker/context registration truth, and rule
  definitions
- JDBC storage also persists low-frequency principal credential truth used by
  SDK submitter and external worker API-key authentication
- `TaskMsg`, `TaskMsgAttempt`, worker locks, heartbeat churn, and context
  occupancy churn stay process-local runtime projection state
- do not use JDBC storage as a cross-task message-status analytics surface;
  large-scale message history, attempt history, heartbeat streams, and failure
  analysis should flow through queues, trace, audit sinks, or downstream
  analytical storage

Mock-data loading order:

- workers
- explicit worker contexts
- rules: non-empty config replaces the current default rules; empty config is treated as no override
- tasks

## Regression Coverage

Mainline stance:

- end-to-end integration coverage is the primary acceptance gate for runtime behavior
- unit tests remain important support coverage, but they are not the main proof for task lifecycle correctness
- integration suites are grouped by domain under `src/test/java/com/xa/mass/server/e2e`
- shared HTTP/task polling helpers now live in `src/test/java/com/xa/mass/server/e2e/support/AbstractSampleE2eTest`

Focused verified regression command:

```bash
mvn --% -pl xa-mass-server -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Transport-focused regression command:

```bash
mvn --% -pl xa-mass-server -am -Dtest=SampleWorkerSocketClientTest,SocketClientStarterTest,SocketTaskApiIntegrationTest,WebSocketClientStarterTest,TransportChannelWiringIntegrationTest,NodeWebSocketWorkerBlackBoxIntegrationTest,NodeSocketWorkerBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Cross-language sample black-box regression:

```bash
./scripts/run-external-worker-samples.sh
```

Covered areas:

- `e2e/lifecycle`: create -> approve -> assign -> run -> complete, pause/resume guards, pause-completion, terminate-running, resume-and-complete
- `e2e/results`: failed-result terminal closure, mixed results, callback replay idempotency
- `e2e/assignment`: delayed worker availability and multi-task assignment behavior
- `CrawlerPullWorkerSdkRegistrationIntegrationTest`: SDK-created crawler worker resource, pull connect/poll/result, and terminal read-model verification without sample worker JSON
- `e2e/audit`: diagnostic task-state validation and terminal metadata consistency through the real runtime path
- `e2e/assignment`: targeted worker debug task behavior and disconnect-after-result behavior
- `WebSocketClientStarterTest`: auto-start and idempotent startup behavior
- `SocketClientStarterTest`: adapter-aware socket starter wiring and bound-port resolution
- `SocketTaskApiIntegrationTest`: auto-started socket sample workers go online, receive tasks, and return canonical results
- `SampleWorkerSocketClientTest`: canonical socket dispatch handling, task-result write-back, and disconnect-after-result behavior
- `SampleWorkerWebSocketClientTest`: task dispatch handling, canonical task-result write-back, delay/drop fault injection, and targeted debug task behavior

## Boot-Shell E2E Map

`xa-mass-server` owns the detailed E2E suite map.

High-signal classes:

- lifecycle:
  - `TaskApiIntegrationTest`
  - `TaskApiLifecycleGuardsIntegrationTest`
  - `TaskApiPauseCompletionIntegrationTest`
  - `TaskApiResumeAndCompleteIntegrationTest`
  - `TaskApiTerminateRunningIntegrationTest`
- assignment, routing, and capacity:
  - `TaskApiDelayedWorkerAvailabilityIntegrationTest`
  - `TaskApiMinimumWorkerGateIntegrationTest`
  - `TaskApiMultiRoundDispatchIntegrationTest`
  - `TaskApiWorkerContextAttributeRoutingIntegrationTest`
  - `TaskApiWorkerWithoutContextIntegrationTest`
  - `TaskApiSingleWorkerReuseIntegrationTest`
  - `TaskApiTerminateReuseIntegrationTest`
  - `TransportChannelWiringIntegrationTest`
  - `PollingWorkerTaskFlowIntegrationTest`
  - `CrawlerPullWorkerSdkRegistrationIntegrationTest`
- results and idempotence:
  - `TaskApiFailureResultIntegrationTest`
  - `TaskApiMixedResultsIntegrationTest`
  - `TaskApiCallbackReplayIntegrationTest`
- external worker black-box:
  - `NodePollingWorkerBlackBoxIntegrationTest`
  - `NodeWebSocketWorkerBlackBoxIntegrationTest`
  - `NodeSocketWorkerBlackBoxIntegrationTest`
  - `JavaPollingWorkerBlackBoxIntegrationTest`
  - `JavaWebSocketWorkerBlackBoxIntegrationTest`
  - `JavaSocketWorkerBlackBoxIntegrationTest`
- console and audit:
  - `ControlConsoleRoutingIntegrationTest`
  - `TaskApiStateValidationIntegrationTest`
- targeted worker debug:
  - `TaskApiTargetedWorkerDebugIntegrationTest`

Fixture rules:

- prefer `registerWorker(...)`, `registerWorkerContext(...)`, `replaceDefaultRules(...)`, `createTaskShell(...)`, `appendTaskItems(...)`, and `sealTask(...)`
- worker JSON and worker-context JSON are fixture inputs, not runtime truth
- direct `WorkerManager` and `RuleManager` setup writes are not mainline E2E setup
- direct `TaskManager` writes stay limited to focused white-box assertions or fault injection

Current gaps:

Project-level gap index: [`../doc/CURRENT_GAPS.md`](../doc/CURRENT_GAPS.md).

- cancel from `RUNNING` via HTTP API
- cancel from `READY` via HTTP API
- worker disconnect during in-flight execution
- stronger real-runtime `EXPIRED` message coverage
- broader `batchSize > 1` multi-worker coverage
- resume short-circuit where a paused task is already complete underneath
