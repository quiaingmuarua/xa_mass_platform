# External Java SDK Quickstart

Status: current cross-module external Java SDK quickstart.

Use this file for the shortest current truth about repo-external Java clients:
task producers, worker topology registration, and worker sessions. Keep Node
sample startup details in `integrations/samples/node/*/README.md`. Java
executable SDK usage is owned by `integrations/xa-mass-scenario-launcher`. SDK
product module ownership is summarized in `../README.md`; integration module
ownership is summarized in `../../integrations/README.md`.

## 1. Current Contract Split

- Public task producer contract: versioned HTTP under `/api/v1/tasks/**`,
  exposed to Java callers through `mass.tasks()`.
- Public repo-external worker data-plane contract: versioned HTTP under `/worker-api/v1/**`,
  exposed to Java callers through `mass.workers()` and
  `mass.workerSessions()`.
- Shared repo-external control-plane registration: declare WorkerGroup through
  `POST /worker-api/v1/worker-groups`, then register Worker identity through
  `POST /worker-api/v1/workers`.
- Realtime adapters (`websocket`, `socket`) are active cross-language validation paths, but their wire shapes are adapter-local compatibility seams, not the stable public worker protocol commitment.
- `adapterId` is concrete routing truth. `transportHint` is only the coarse family hint.
- `eventCode` is the global capability identity.
- Task shell creation enters through `POST /api/v1/tasks`, then work items are appended through `POST /api/v1/tasks/{taskId}/items`.
- Task list/detail reads may return composite task rows. Java `TaskView`
  exposes `fieldSources` so callers can tell shell, runtime/current,
  execution, timestamp, and compatibility fields apart instead of treating the
  row as one database entity.

## 2. Task Producer Path

Task producers create one task shell, append work items, and read stable-final
results. They do not register workers, issue operator lifecycle commands, or own
scheduling decisions.

Java SDK entry:

```java
MassPlatform mass = MassPlatform.builder()
        .baseUrl("http://localhost:8088")
        .apiKey("mass_sk_xxx")
        .build();

var task = mass.tasks().create(TaskCreateRequest.builder()
        .project("crawlerApp")
        .userId("crawler-agent")
        .contract(TaskContract.BATCH)
        .workerGroupId("crawler-workers")
        .routingCode("us")
        .build());

TaskHandle handle = mass.tasks().forTask(task.taskId());

handle.appendItems(TaskItemBatch.builder()
        .eventCode("crawler.fetch-page")
        .item(Map.of("url", "https://example.com"))
        .build());

TaskResultWindow results = handle.results(TaskResultReadRequest.builder()
        .limit(100)
        .build());
```

Interactive task-scoped invocation is also task-producer behavior:

```java
TaskHandle handle = mass.tasks().forTask("existing-ready-session-task-id");

TaskSyncAppendResult result = handle.appendItemSync(TaskItemSyncRequest.builder()
        .eventCode("crawler.fetch-page")
        .item(Map.of("url", "https://example.com"))
        .timeoutMs(5000L)
        .build());
```

Keep these rules:

- `SESSION` + `items:sync` requires the task to be `READY` or `RUNNING` with
  intake `OPEN`
- task lifecycle commands such as seal/approve are operator/server-control
  behavior and are outside the scenario task-producer path
- sealed `BATCH` tasks are not valid sync-append targets
- WorkerGroup selectors belong on task create; `eventCode` belongs on item
  append
- task producers read stable-final result rows through task result APIs, not
  server-local review rows
- do not add or depend on a bulk append helper until append receipts expose
  per-item message identity or equivalent append identity; task-scoped SDK
  usage should keep using `TaskHandle`

## 3. Worker Path

Every external worker path must preserve the same kernel behavior:

1. declare WorkerGroup capability through `eventBindings`
2. register Worker identity with `workerGroupId`
3. establish online presence through the concrete transport path
4. receive task work, execute locally by `eventCode`, and submit result

Keep these rules:

- server credentials may bind an API key to one worker through
  `attributes.workerId`; when present, every worker registration, presence,
  poll, result, report, command ack, and offline call must use that worker id
- registration is not equivalent to being online
- transport presence is not equivalent to matching eligibility
- engine scheduling remains the only path that gives task work
- transport adapters deliver work; they do not redefine task lifecycle semantics
- task convergence still happens through normal result ingest into `TERMINAL`

## 4. Worker Protocol Surface

The stable third-party worker protocol today is the polling surface:

- `POST /worker-api/v1/worker-groups`
- `POST /worker-api/v1/workers`
- `POST /worker-api/v1/workers/{workerId}:online`
- `POST /worker-api/v1/workers/{workerId}:heartbeat`
- `POST /worker-api/v1/workers/{workerId}:poll`
- `POST /worker-api/v1/workers/{workerId}:submit-result`
- `POST /worker-api/v1/workers/{workerId}:report-capability`
- `POST /worker-api/v1/workers/{workerId}:report-state`
- `POST /worker-api/v1/workers/{workerId}/commands/{commandId}:ack`
- `POST /worker-api/v1/workers/{workerId}:offline`

Polling workers receive `TaskDispatchItem`, execute by `eventCode`, and submit
`TaskResultReport`. They may also proactively report bounded worker capability
and state snapshots, and acknowledge owner-issued worker commands. In current
mainline, `report-state(DRAINING)` stops new dispatches to that worker but does
not revoke or interrupt already in-flight work. Acknowledging a `DRAIN` command
to an accepted state converges to the same dispatch-gate behavior.

Example dispatch payload:

```json
{
  "taskId": "...",
  "messageId": "...",
  "workerId": "node-worker-api-001",
  "eventCode": "crawler.fetch-page",
  "input": {
    "url": "https://example.com"
  },
  "sharedConfig": {
    "routingCode": "us",
    "routeAttributes": {
      "region": "us"
    }
  }
}
```

## 5. Realtime Validation Paths

Realtime worker samples are still important because the runtime supports them
today and Boot-shell E2E proves them:

| Path | adapterId | transportHint | Role |
| --- | --- | --- | --- |
| `integrations/samples/node/worker-polling` | `polling` | `polling` | polling protocol validation fixture |
| `integrations/samples/node/worker-websocket` | `websocket` | `realtime` | adapter validation fixture |
| `integrations/samples/node/worker-socket` | `socket` | `realtime` | adapter validation fixture |
| `integrations/xa-mass-scenario-launcher` | `polling`, `websocket` | `polling`, `realtime` | Java SDK proof split into task-producer and worker-process launchers |

For realtime paths:

- use `/worker-api/v1/worker-groups` for group capability declaration, then
  `/worker-api/v1/workers` for worker identity registration
- online presence comes from the transport connection, not the register call
- keep local handler resolution keyed by `eventCode`
- do not treat adapter frame fields as a second business capability model
- keep polling as the stable third-party worker protocol; Java SDK WebSocket is
  an implemented JVM session and internal staging validation path while its
  wire shape continues hardening
- Java SDK session listeners distinguish heartbeat, poll, frame/protocol,
  connection, recovery, submit, and queued-result terminal outcomes
- heartbeat failures report only through `onHeartbeatFailure(...)`, not through
  `onPollFailure(...)`
- `onSubmitFailure(...)` is an attempt-level signal; queued-result abandonment
  is the terminal signal for results that cannot be retained or delivered
- send-failure requeue failure is a queued-result terminal outcome reported as
  `REQUEUE_FAILED` through `onQueuedResultAbandoned(...)`
- frame/protocol failures expose bounded `framePreview` plus `frameLength`, not
  the complete raw frame; previews can still contain payload fragments
- WebSocket result idempotency under reconnect, malformed frame flood ceilings,
  socket session ownership, and Android host support remain open hardening
  topics rather than stable public protocol promises

## 6. Local Validation Entry

Use the real Boot shell plus the sample/launcher READMEs:

- Boot runtime and verified commands: [VERIFIED_RUNBOOK.md](../../xa-mass-testing/VERIFIED_RUNBOOK.md)
- integrations module map: [../../integrations/README.md](../../integrations/README.md)
- sample matrix and black-box role: [../../integrations/samples/README.md](../../integrations/samples/README.md)
- per-sample commands: `integrations/samples/node/*/README.md`
- external Java SDK: [README.md](./README.md)
- Java SDK task/worker launchers: [../../integrations/xa-mass-scenario-launcher/README.md](../../integrations/xa-mass-scenario-launcher/README.md)

For real external-registration proof, prepare catalog, rules, and API keys
through server-owned seed/import or the normal host setup, then run the Java SDK
launcher. The launcher does not own server metadata preparation; it proves
SDK-backed WorkerGroup, AdapterNode, Worker, task, and worker-session paths.

For a CLI-only public-contract smoke against an already running dev server, use:

```bash
scripts/proof/external-worker-http-contract.sh
```

The script uses only `curl` and `jq`. It drives `/worker-api/v1/**` through the
stable polling contract and uses the dev-only `external.proof.echo` event plus
`external-proof-task-api-key` / `external-proof-worker-key` credentials so it
does not depend on optional sample workers or server-owned demo workload state.

Producer traffic uses shell create plus explicit ingest. These calls require
`crawler-task-api-key` to already exist through explicit seed/import or normal
host credential setup; the checked-in local scenario seed must be imported with
`mass.control-plane.seed.allow-local-fixture-raw-secrets=true` because it
contains local fixture raw secrets.

```bash
curl -X POST http://127.0.0.1:8088/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-Mass-Api-Key: crawler-task-api-key' \
  -d '{
    "project": "crawlerApp",
    "userId": "crawler-agent",
    "sharedConfig": {
      "routingCode": "us"
    },
    "executionSpec": {
      "batchSize": 1
    }
  }'

curl -X POST http://127.0.0.1:8088/api/v1/tasks/{taskId}/items \
  -H 'Content-Type: application/json' \
  -H 'X-Mass-Api-Key: crawler-task-api-key' \
  -d '{
    "eventCode": "crawler.fetch-page",
    "items": [
      {
        "url": "https://example.com"
      }
    ]
  }'
```

## 7. Acceptance Truth

Current executable external-process coverage:

- `ExternalWorkerParitySuite`
  - `NodePollingWorkerBlackBoxIntegrationTest`
  - `NodeWebSocketWorkerBlackBoxIntegrationTest`
  - `NodeSocketWorkerBlackBoxIntegrationTest`
  - `JavaScenarioLauncherBlackBoxIntegrationTest`

These prove:

- registration and online presence are separate
- external workers execute by `eventCode`
- engine mainline still owns dispatch and result convergence
- realtime adapters do not cross-route when `adapterId` differs
- sample output identifies the actual worker process that handled the task
