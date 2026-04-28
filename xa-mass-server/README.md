# xa-mass-server

`xa-mass-server` is the verified runnable entry module for the current repository mainline.

Use this module for end-to-end validation of:

- Spring Boot HTTP APIs
- the embedded transport adapters, backend-hosted control console, and frontend shell
- SDK-created worker resources, fixture bootstrap inputs, and result write-back

Repository-level startup instructions in [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md) are the source of truth.

## Current Role

- real Spring Boot entrypoint: `com.xa.mass.server.XaMassServerApplication`
- starts runtime through `xa-mass-sdk` and directly owns the backend-hosted control console, JSON APIs, and frontend shell under `com.xa.mass.api`
- worker, task, and rule resources are created through the embedded SDK runtime
- default `dev` startup now externalizes project/event/submitter/rule bootstrap plus seed worker/task creation through `samples/dev/launch-workers.mjs`
- JSON fixture bootstrap remains a test-only input path, but default `dev` no longer bootstraps catalog resources, rules, workers, or tasks from packaged fixture files
- default `dev` startup does not auto-start embedded sample clients; worker presence is expected to come from external sample or real worker processes
- default `dev` sample bootstrap exposes a sample-only write surface at `/sample-api/bootstrap/*`
  protected by `X-Sample-Bootstrap-Key`

Controller/console ownership now includes:

- REST controller layer
- DTO / request-response boundary
- backend-hosted control console shell
- frontend route serving from built `frontend/dist`

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
java -cp "xa-mass-server/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.server.XaMassServerApplication
```

After startup:

- HTTP control console: `http://localhost:8088/`
- HTTP tasks view: `http://localhost:8088/tasks`
- HTTP workers view: `http://localhost:8088/resources/workers`
- HTTP API docs: `http://localhost:8088/doc.html`
- WebSocket: `ws://localhost:18088/ws`
- Socket when enabled: `tcp://localhost:18089`

Control-console routing note:

- `/status`, `/status/tasks`, `/status/workers`, and `/status/rules` are redirect aliases only
- the backend-hosted SPA routes above are the primary operator entrypoints

## Effective Sample Client Startup

For test or explicit fixture paths, embedded sample clients are owned by `xa-mass-worker-pack` and started by:

- `xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/AbstractSampleWorkerClientStarter.java`
- `xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/WebSocketClientStarter.java`
- `xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/SocketClientStarter.java`

Startup behavior:

- disabled in default `dev`
- gated by `sample.client.auto-start=true` only for tests or explicit local fixture runs
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
- task JSON continues to map to `MassTaskCreateRequest`
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

- worker debug requests are submitted through `POST /status/api/tasks`
- fix the selected worker with `sharedConfig.targetWorkerId`
- command execution stays on normal task lifecycle and does not use a dedicated worker-control side-channel

Example request body:

```json
{
  "project": "demoApp",
  "taskName": "targeted-delay-response",
  "eventCode": "mock.delay.response",
  "mode": "SINGLE_RUN",
  "payloadType": "JSON",
  "userId": "itest",
  "sharedConfig": {
    "targetWorkerId": "it-worker-0"
  },
  "inputs": [
    {
      "millis": 500
    }
  ],
  "batchSize": 1
}
```

Observability:

- debug submissions return `taskId`
- targeted debug tasks can be inspected through normal task detail and message views
- `mock.disconnect` is designed to close the worker after its task result is sent
- `tool.geo.lookup` and `tool.currency.quote` are simulated helpers and must be treated as fake data sources

## Key Config

| Property | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8088` | HTTP port |
| `mass.websocket.port` | `18088` | WebSocket adapter port |
| `mass.socket.port` | `18089` | Socket adapter port |
| `sample.client.auto-start` | `false` | auto-start embedded sample clients only for explicit fixture/test runs |
| `sample.client.websocket-uri` | `ws://localhost:${mass.websocket.port}/ws` | target WebSocket adapter address |
| `sample.client.socket-host` | `127.0.0.1` | target socket adapter host |
| `sample.client.socket-port` | `18089` | fallback socket adapter port when no bound-port override is published |
| `sample.client.task-result-status` | `SUCCESS` | force sample result frames to `SUCCESS` or `FAILED` |
| `sample.bootstrap.api-key` | `dev-bootstrap-key` | sample-only bootstrap credential for `/sample-api/bootstrap/*` |
| `sample.worker.auto-start` | `true` in `dev` | launch external sample supervisor under `samples/dev/` |

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
- `e2e/audit`: `stateValidation` exposure and terminal metadata consistency through the real HTTP path
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

- prefer `registerWorker(...)`, `registerWorkerContext(...)`, `replaceDefaultRules(...)`, and `createTask(...)`
- worker JSON and worker-context JSON are fixture inputs, not runtime truth
- direct `WorkerManager` and `RuleManager` setup writes are not mainline E2E setup
- direct `TaskManager` writes stay limited to focused white-box assertions or fault injection

Current gaps:

- cancel from `RUNNING` via HTTP API
- cancel from `READY` via HTTP API
- worker disconnect during in-flight execution
- stronger real-runtime `EXPIRED` message coverage
- broader `batchSize > 1` multi-worker coverage
- resume short-circuit where a paused task is already complete underneath

