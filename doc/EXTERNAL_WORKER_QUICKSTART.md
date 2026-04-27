# External Worker Quickstart

Use this file for the shortest current truth about repo-external workers.
Keep per-language startup and env details in `samples/*/README.md`.

## 1. Current Contract Split

- Public repo-external worker data-plane contract: polling HTTP under `/worker-api/*`.
- Shared repo-external control-plane registration: `POST /worker-api/workers/register`.
- Realtime adapters (`websocket`, `socket`) are active cross-language validation paths, but their wire shapes are adapter-local compatibility seams, not the stable public worker protocol commitment.
- `adapterId` is concrete routing truth. `transportHint` is only the coarse family hint.
- `eventCode` is the global capability identity.
- Task creation still enters through `POST /status/api/tasks`.

## 2. Scheduling Truth

Every external worker path must preserve the same kernel behavior:

1. register capability through `eventBindings`
2. establish online presence through the concrete transport path
3. receive task work, execute locally by `eventCode`, and submit result

Keep these rules:

- registration is not equivalent to being online
- transport presence is not equivalent to matching eligibility
- engine scheduling remains the only path that gives task work
- transport adapters deliver work; they do not redefine task lifecycle semantics
- task convergence still happens through normal result ingest into `TERMINAL`

## 3. What Is Public Today

The stable third-party worker protocol today is the polling surface:

- `POST /worker-api/workers/register`
- optional `POST /worker-api/worker-contexts/register`
- `POST /worker-api/workers/{workerId}/online`
- `POST /worker-api/workers/{workerId}/heartbeat`
- `POST /worker-api/workers/{workerId}/poll`
- `POST /worker-api/workers/{workerId}/results`
- `POST /worker-api/workers/{workerId}/offline`

Polling workers receive `TaskDispatchItem`, execute by `eventCode`, and submit
`TaskResultReport`.

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
    "routingCode": "us"
  }
}
```

## 4. Realtime Validation Paths

Realtime worker samples are still important because the runtime supports them
today and Boot-shell E2E proves them:

| Path | adapterId | transportHint | Role |
| --- | --- | --- | --- |
| `samples/worker-polling/*` | `polling` | `polling` | stable public external worker contract |
| `samples/worker-websocket/*` | `websocket` | `realtime` | cross-language adapter validation |
| `samples/worker-socket/*` | `socket` | `realtime` | cross-language adapter validation |

For realtime paths:

- use `/worker-api/workers/register` for worker capability registration
- online presence comes from the transport connection, not the register call
- keep local handler resolution keyed by `eventCode`
- do not treat adapter frame fields as a second business capability model

## 5. Local Validation Entry

Use the real Boot shell plus the sample READMEs:

- Boot runtime and verified commands: [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- sample matrix and black-box role: [../samples/README.md](../samples/README.md)
- per-sample commands: `samples/*/README.md`

Producer traffic still uses the normal task API:

```bash
curl -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-Mass-Api-Key: crawler-submitter-key' \
  -d '{
    "taskName": "crawler-fetch-page",
    "project": "crawlerApp",
    "userId": "crawler-agent",
    "eventCode": "crawler.fetch-page",
    "sharedConfig": {
      "routingCode": "us"
    },
    "inputs": [
      {
        "url": "https://example.com"
      }
    ],
    "batchSize": 1
  }'
```

## 6. Acceptance Truth

Current executable external-process coverage:

- `NodePollingWorkerBlackBoxIntegrationTest`
- `JavaPollingWorkerBlackBoxIntegrationTest`
- `NodeWebSocketWorkerBlackBoxIntegrationTest`
- `JavaWebSocketWorkerBlackBoxIntegrationTest`
- `NodeSocketWorkerBlackBoxIntegrationTest`
- `JavaSocketWorkerBlackBoxIntegrationTest`

These prove:

- registration and online presence are separate
- external workers execute by `eventCode`
- engine mainline still owns dispatch and result convergence
- realtime adapters do not cross-route when `adapterId` differs
- sample output identifies the actual worker process that handled the task
