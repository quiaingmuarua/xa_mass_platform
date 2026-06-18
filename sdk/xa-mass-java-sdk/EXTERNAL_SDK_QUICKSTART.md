# External Java SDK Quickstart

Status: current cross-module external Java SDK quickstart.

Use this file for the shortest current truth about repo-external Java clients:
task producers, worker topology registration, and worker runtimes. Keep Node
sample startup details in `integrations/samples/node/*/README.md`. Java
executable SDK usage is owned by `integrations/xa-mass-scenario-launcher`. SDK
product module ownership is summarized in `../README.md`; integration module
ownership is summarized in `../../integrations/README.md`.

## 1. Current Contract Split

- Public task producer contract: versioned HTTP under `/api/v1/tasks/**`,
  exposed to Java callers through `mass.tasks()`.
- Public repo-external worker data-plane contract: versioned HTTP under `/worker-api/v1/**`,
  exposed to Java callers through `mass.workers()` and
  `mass.workerRuntimes()`.
- Shared repo-external control-plane registration: declare WorkerGroup through
  `POST /worker-api/v1/worker-groups`, then register Worker identity through
  `POST /worker-api/v1/workers`.
- Realtime adapters (`websocket`, `socket`) are active cross-language validation paths, but their wire shapes are adapter-local compatibility seams, not the stable public worker protocol commitment.
- External worker runtime callers declare `workerGroupId + transportHint`.
  Concrete transport runtime ids such as `adapterId`, `routeKey`,
  `connectionId`, endpoint lease ids, and `deliveryQueueKey` are transport
  internals, not stable Java SDK worker contracts. Worker runtimes declare
  `workerGroupId`; explicit adapter-node and node-group binding APIs are
  topology/admin bootstrap only, not the worker registration or runtime
  identity.
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
  poll, result, handler-evidence, runtime-evidence, and offline call must use
  that worker id
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
- `POST /worker-api/v1/workers/{workerId}:report-handler-evidence`
- `POST /worker-api/v1/workers/{workerId}:report-runtime-evidence`
- `POST /worker-api/v1/workers/{workerId}/commands/{commandId}:ack`
- `POST /worker-api/v1/workers/{workerId}:offline`

Polling workers receive worker invocation items, execute by `eventCode`, and
submit `WorkerResultSubmission` with the opaque `resultCorrelationRef` from
that item. The pulled item does not carry worker identity, task lifecycle
identity, or route metadata; those come from the worker runtime/path context
or stay inside runtime correlation. Workers may also report current
worker-local handler availability/scheduling attributes
through `:report-handler-evidence` and bounded runtime evidence through
`:report-runtime-evidence`; WorkerGroup declaration remains the capability truth and
these reports are not `WorkerRuntime` lifecycle requirements. Workers may also
acknowledge owner-issued worker commands. In current mainline,
`report-runtime-evidence(DRAINING)` stops new dispatches to that worker but does not
revoke or interrupt already in-flight work. Acknowledging a `DRAIN` command to
an accepted state converges to the same dispatch-gate behavior.
Presence calls (`online`, `heartbeat`, and `offline`) require a stable
per-session `sessionToken`; stale tokens must not revoke a newer active worker
session.

Example dispatch payload:

```json
{
  "resultCorrelationRef": "...",
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

Example result submission:

```json
{
  "resultCorrelationRef": "...",
  "success": true,
  "resultCode": null,
  "result": "{\"status\":\"SUCCESS\",\"title\":\"Example\"}"
}
```

## 5. Realtime Validation Paths

Realtime worker samples are still important because the runtime supports them
today and Boot-shell E2E proves them:

| Path | adapter node / fixture | transportHint | Role |
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
- Java SDK session listeners report runtime failures through one
  `WorkerRuntimeFailureEvent` model; its `kind` distinguishes heartbeat, poll,
  frame/protocol, connection, submit, queued-result terminal outcomes,
  startup, and shutdown failures
- heartbeat failures report as `WorkerRuntimeFailureEvent.Kind.HEARTBEAT`, not
  as `POLL`
- `WorkerRuntimeFailureEvent.Kind.SUBMIT` is an attempt-level signal;
  queued-result abandonment is the terminal signal for results that cannot be
  retained or delivered
- send-failure requeue failure is a queued-result terminal outcome with reason
  `REQUEUE_FAILED`
- frame/protocol failures expose bounded `framePreview` plus `frameLength` in
  event context, not the complete raw frame; previews can still contain payload
  fragments
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
For human task-producer runs, the scenario task launcher supports
`--config scenario.local.json` with local item files and API-key file
references. Worker launcher config is deferred; worker process proof continues
through the existing `--scenario-dir` / `workers.json` path. See the launcher
README for the concrete config shape and example files.

For a CLI-only public-contract smoke against an already running dev server, use:

```bash
scripts/proof/external-worker-http-contract.sh
```

The script uses only `curl` and `jq`. It drives `/worker-api/v1/**` through the
stable polling contract and uses the dev-only `external.proof.echo` event plus
`external-proof-task-api-key` / `external-proof-worker-key` credentials so it
does not depend on optional sample workers or server-owned demo workload state.

Producer traffic uses shell create plus explicit ingest. These calls require a
real task API key to already exist through normal host credential setup. For
local scenario work, prefer `tools/xa-mass-admin-cli env init`: it checks the
scenario catalog/rules, validates local task/worker key cache files through
`/api/v1/api-keys:current`, and creates missing credentials through
server-owned operator/API-key routes. The checked-in raw-secret seed remains an
explicit local fixture fallback only.

```bash
curl -X POST http://127.0.0.1:8088/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-Mass-Api-Key: <task-api-key>' \
  -d '{
    "project": "crawlerApp",
    "userId": "ops-admin",
    "sharedConfig": {
      "workerGroupId": "sample-websocket-crawler",
      "routingCode": "us"
    },
    "executionSpec": {
      "batchSize": 1
    }
  }'

curl -X POST http://127.0.0.1:8088/api/v1/tasks/{taskId}/items \
  -H 'Content-Type: application/json' \
  -H 'X-Mass-Api-Key: <task-api-key>' \
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
- realtime adapters do not cross selected-worker delivery boundaries when
  transport internals such as adapter runtime id or endpoint address differ
- sample output identifies the actual worker process that handled the task
