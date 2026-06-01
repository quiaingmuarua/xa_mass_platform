# External Worker Quickstart

Status: current cross-module external-worker quickstart.

Use this file for the shortest current truth about repo-external workers.
Keep Node sample startup details in `integrations/samples/node/*/README.md`.
Java executable SDK usage is owned by `integrations/xa-mass-scenario-launcher`.

## 1. Current Contract Split

- Public repo-external worker data-plane contract: versioned HTTP under `/worker-api/v1/**`.
- Shared repo-external control-plane registration: declare WorkerGroup through
  `POST /worker-api/v1/worker-groups`, then register Worker identity through
  `POST /worker-api/v1/workers`.
- Realtime adapters (`websocket`, `socket`) are active cross-language validation paths, but their wire shapes are adapter-local compatibility seams, not the stable public worker protocol commitment.
- `adapterId` is concrete routing truth. `transportHint` is only the coarse family hint.
- `eventCode` is the global capability identity.
- Task shell creation enters through `POST /api/v1/tasks`, then work items are appended through `POST /api/v1/tasks/{taskId}/items`.

## 2. Scheduling Truth

Every external worker path must preserve the same kernel behavior:

1. declare WorkerGroup capability through `eventBindings`
2. register Worker identity with `workerGroupId`
3. establish online presence through the concrete transport path
4. receive task work, execute locally by `eventCode`, and submit result

Keep these rules:

- registration is not equivalent to being online
- transport presence is not equivalent to matching eligibility
- engine scheduling remains the only path that gives task work
- transport adapters deliver work; they do not redefine task lifecycle semantics
- task convergence still happens through normal result ingest into `TERMINAL`

## 3. What Is Public Today

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

## 4. Realtime Validation Paths

Realtime worker samples are still important because the runtime supports them
today and Boot-shell E2E proves them:

| Path | adapterId | transportHint | Role |
| --- | --- | --- | --- |
| `integrations/samples/node/worker-polling` | `polling` | `polling` | stable public external worker contract fixture |
| `integrations/samples/node/worker-websocket` | `websocket` | `realtime` | adapter validation fixture |
| `integrations/samples/node/worker-socket` | `socket` | `realtime` | adapter validation fixture |
| `integrations/xa-mass-scenario-launcher` | `polling`, `websocket` | `polling`, `realtime` | Java SDK registration and worker-session proof |

For realtime paths:

- use `/worker-api/v1/worker-groups` for group capability declaration, then
  `/worker-api/v1/workers` for worker identity registration
- online presence comes from the transport connection, not the register call
- keep local handler resolution keyed by `eventCode`
- do not treat adapter frame fields as a second business capability model

## 5. Local Validation Entry

Use the real Boot shell plus the sample/launcher READMEs:

- Boot runtime and verified commands: [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- sample matrix and black-box role: [../integrations/samples/README.md](../integrations/samples/README.md)
- per-sample commands: `integrations/samples/node/*/README.md`
- Java SDK launcher: `integrations/xa-mass-scenario-launcher`

For a CLI-only public-contract smoke against an already running dev server, use:

```bash
scripts/proof/external-worker-http-contract.sh
```

The script uses only `curl` and `jq`. It drives `/worker-api/v1/**` through the
stable polling contract and uses the dev-only `external.proof.echo` event plus
`external-proof-submitter-key` / `external-proof-worker-key` credentials so it
does not depend on optional sample workers or server-owned demo workload state.

Producer traffic uses shell create plus explicit ingest:

```bash
curl -X POST http://127.0.0.1:8088/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-Mass-Api-Key: crawler-submitter-key' \
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
  -H 'X-Mass-Api-Key: crawler-submitter-key' \
  -d '{
    "eventCode": "crawler.fetch-page",
    "items": [
      {
        "url": "https://example.com"
      }
    ]
  }'
```

## 6. Acceptance Truth

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
