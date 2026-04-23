# xa-mass-dev-app

`xa-mass-dev-app` is the verified runnable entry module for the current repository mainline.

Use this module for end-to-end validation of:

- Spring Boot HTTP APIs
- the internal gateway WebSocket server
- SDK-created worker resources, fixture bootstrap inputs, and result write-back

Repository-level startup instructions in [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md) are the source of truth.

## Current Role

- real Spring Boot entrypoint: `com.xa.mass.mock.MockApplicationSpringBootApp`
- starts runtime through `xa-mass-sdk` and exposes the current backend-hosted control console and JSON APIs through `xa-mass-web`
- obtains task, worker, and rule management capability from the embedded SDK runtime instead of constructing a parallel engine assembly path in the app module
- treats worker resource creation as SDK-first; mock JSON is a local/E2E fixture path, not the product resource entry
- runtime still composes gateway and engine internally
- default `dev` startup auto-starts mock WebSocket clients

## Port Model

Two ports are used on purpose:

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `8088` | Spring Boot HTTP port for the backend-hosted control console, `/doc.html`, and JSON APIs |
| `mass.websocket.port` | `18088` | internal gateway WebSocket server port |

Mock clients connect through:

| Property | Default |
| --- | --- |
| `mock.client.uri` | `ws://localhost:${mass.websocket.port}/ws` |

## Verified Main Entry

Start from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-web/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-transport-api/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

After startup:

- HTTP control console: `http://localhost:8088/`
- HTTP tasks view: `http://localhost:8088/tasks`
- HTTP workers view: `http://localhost:8088/resources/workers`
- HTTP API docs: `http://localhost:8088/doc.html`
- WebSocket: `ws://localhost:18088/ws`

Control-console routing note:

- `/status`, `/status/tasks`, `/status/workers`, and `/status/rules` are redirect aliases only
- the backend-hosted SPA routes above are the primary operator entrypoints

## Effective Mock Client Startup

For the verified default `dev` path, mock clients are started by:

- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/starter/WebSocketClientStarter.java`

Startup behavior:

- gated by `mock.client.auto-start=true`
- triggered by `ApplicationReadyEvent`
- discovers mock clients from SDK-registered `Worker` resources
- only opens WebSocket clients for workers with `onlineStrategy` compatible with `realtime` / `websocket`
- does not read a separate worker JSON client list
- idempotent startup protection through an internal `AtomicBoolean`
- there is no longer a separate client-only Spring Boot application or `/mock/status` monitor surface

## Worker Resource Fixtures

`MockRuntimeDataLoader` is a fixture loader for local and E2E startup data. JSON is only a fixture input format; resource creation still goes through `MassSdkApplication.registerWorker(...)` and `registerWorkerContext(...)`.

Current fixture behavior:

- worker JSON entries are mapped to `WorkerRegistration`
- worker-context JSON entries are mapped to `WorkerContextRegistration`
- runtime state fields in historical JSON, such as `Worker.status=ONLINE`, are ignored; worker online state comes from transport connect/heartbeat
- task JSON continues to map to `MassTaskCreateRequest`
- rule JSON continues to replace default rules when non-empty

Default worker fixtures now carry a small executor profile:

- `onlineStrategy=realtime` for WebSocket-backed dev workers
- worker attributes such as `runtime`, `workerType`, `region`, and `lane`
- worker-context attributes such as `country` and `network`

These labels are only dev/E2E signals for routing and observability. They must not become kernel truth; production-style resources should still be created through the SDK resource APIs.

## Mock Worker Execution Behavior

Auto-started mock WebSocket clients intentionally behave like lightweight executors rather than instant echo clients:

- task responses use a deterministic small delay with stable jitter, so local runs exercise asynchronous result handling without random flakiness
- `mock.delay.response` remains the explicit override for fault-injection tests
- result payloads keep the legacy `status` and `mockData` fields for compatibility
- result payloads also include `execution` metadata with timing, retry count, task id, message id, project, and transport
- result payloads include `workerProfile` metadata with worker id and local mock runtime details

The extra payload fields are observability data for dev-app realism. Existing lifecycle decisions still come from the task kernel, attempts, and result status.

## Mock Command Runtime

`xa-mass-dev-app` now exposes a lightweight in-process command runtime for mock workers through the existing manual debug chat channel.

Current command groups:

- `mock.*`: mutate mock worker behavior without changing task-kernel semantics
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

- request path: `POST /status/workers/send-event`
- transport frame: `CONTROL/event`
- acknowledgement frame: `EVENT/event`
- command execution is only a debug/control side-channel and must not mutate task lifecycle state

Example request body:

```json
{
  "workerId": "it-worker-0",
  "project": "demoApp",
  "msgType": "CONTROL",
  "subMsgType": "manual-chat",
  "payload": {
    "event": "mock.delay.response",
    "millis": 500
  }
}
```

Observability:

- command acknowledgements are recorded in `GET /status/workers/message-history?workerId=...`
- `mock.disconnect` is designed to close the worker only after the acknowledgement is sent
- `tool.geo.lookup` and `tool.currency.quote` are simulated helpers and must be treated as fake data sources

## Key Config

| Property | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8088` | HTTP port |
| `mass.websocket.port` | `18088` | gateway WebSocket port |
| `mock.client.auto-start` | `true` | auto-start mock clients in default `dev` path |
| `mock.client.uri` | `ws://localhost:${mass.websocket.port}/ws` | target gateway address |
| `mock.client.task-result-status` | `SUCCESS` | force mock result frames to `SUCCESS` or `FAILED` |
| `mass.mock.data.workers` | `mock/mock_workers.json` | mock worker data |
| `mass.mock.data.worker-contexts` | `mock/mock_worker_contexts.json` | explicit mock worker-context data |
| `mass.mock.data.tasks` | `mock/mock_tasks.json` | mock task data |
| `mass.mock.data.rules` | `mock/mock_rules.json` | explicit mock worker-match rules; non-empty config overrides the current defaults |

Mock-data loading order:

- workers
- explicit worker contexts
- rules: non-empty config replaces the current default rules; empty config is treated as no override
- tasks

## Regression Coverage

Mainline stance:

- end-to-end integration coverage is the primary acceptance gate for runtime behavior
- unit tests remain important support coverage, but they are not the main proof for task lifecycle correctness
- integration suites are grouped by domain under `src/test/java/com/xa/mass/mock/e2e`
- shared HTTP/task polling helpers now live in `src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest`

Focused verified regression command:

```bash
mvn --% -pl xa-mass-dev-app -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Covered areas:

- `e2e/lifecycle`: create -> approve -> assign -> run -> complete, pause/resume guards, pause-completion, terminate-running, resume-and-complete
- `e2e/results`: failed-result terminal closure, mixed results, callback replay idempotency
- `e2e/assignment`: delayed worker availability and multi-task assignment behavior
- `CrawlerPullWorkerSdkRegistrationIntegrationTest`: SDK-created crawler worker resource, pull connect/poll/result, and terminal read-model verification without mock worker JSON
- `e2e/audit`: `stateValidation` exposure and terminal metadata consistency through the real HTTP path
- `e2e/support`: manual debug chat, command acknowledgements, and disconnect-after-ack behavior
- `WebSocketClientStarterTest`: auto-start and idempotent startup behavior
- `MassWebSocketClientImplTest`: ignore `response=true` task frames, avoid echo loops, support delay/drop fault injection, and support disconnect-after-ack command behavior
