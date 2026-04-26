# External Worker Quickstart

This document is the shortest current runbook for third-party workers that stay
outside the Java SDK process.

Current mainline:

- polling, websocket, and socket are all active external worker validation paths
- `transportHint` is the coarse family, `adapterId` is the concrete runtime route
- realtime workers must always register with explicit `adapterId + transportHint`
- task creation still enters through `POST /status/api/tasks`
- capability identity is global `eventCode`
- transport adapters deliver work; they do not redefine kernel task semantics

Use this when you want to validate a real external worker process in Node, Java,
or another language without depending on in-JVM helpers.

## 1. Common Runtime Rules

Every external worker path is expected to follow the same scheduling truth:

1. register worker capability through control-plane registration
2. establish presence through the adapter-specific transport path
3. receive canonical task dispatch, execute by `eventCode`, and submit result

What must stay true:

- control-plane registration is not equivalent to being online
- engine scheduling remains the only path that gives work to the worker
- the worker should route locally by `eventCode`, not by transport-specific frame names
- `adapterId` decides the concrete adapter route; `transportHint` remains the family hint
- task completion still converges through normal result ingest into `TERMINAL`

## 2. Start The Dev Runtime

From repo root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-web/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/transport_api/target/classes:transport/polling-adapter/target/classes:transport/transport_runtime/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Default local endpoints:

```text
HTTP base URL:   http://127.0.0.1:8088
WebSocket URL:   ws://127.0.0.1:18088/ws
Socket host:     127.0.0.1
Socket port:     18089
```

If you need a stable local walkthrough instead of the test harness, prefer the
sample-specific README under `samples/`.

## 3. Sample Matrix

| Path | Language | adapterId | transportHint | Startup |
| --- | --- | --- | --- | --- |
| `samples/worker-polling/node` | Node.js | `polling` | `polling` | `node samples/worker-polling/node/worker.mjs` |
| `samples/worker-polling/java` | Java | `polling` | `polling` | `java -jar samples/worker-polling/java/target/worker-polling-java-sample.jar` |
| `samples/worker-websocket/node` | Node.js | `websocket` | `realtime` | `node samples/worker-websocket/node/worker.mjs` |
| `samples/worker-websocket/java` | Java | `websocket` | `realtime` | `java -jar samples/worker-websocket/java/target/worker-websocket-java-sample.jar` |
| `samples/worker-socket/node` | Node.js | `socket` | `realtime` | `node samples/worker-socket/node/worker.mjs` |
| `samples/worker-socket/java` | Java | `socket` | `realtime` | `java -jar samples/worker-socket/java/target/worker-socket-java-sample.jar` |

## 4. Polling Path

Polling workers are the fully external HTTP contract under `/worker-api/*`.

Verified demo fixture defaults:

```text
submitter credential: crawler-submitter-key
worker credential:    node-worker-key
project:              crawlerApp
eventCode:            crawler.fetch-page
workerId:             node-worker-api-001
adapterId:            polling
transportHint:        polling
```

Default Node environment:

```text
MASS_BASE_URL=http://127.0.0.1:8088
MASS_WORKER_ID=node-worker-api-001
MASS_WORKER_KEY=node-worker-key
MASS_PROJECT=crawlerApp
MASS_EVENT_CODE=crawler.fetch-page
```

Default Java environment:

```text
MASS_BASE_URL=http://127.0.0.1:8088
MASS_WORKER_ID=java-worker-api-001
MASS_WORKER_KEY=java-worker-key
MASS_PROJECT=crawlerApp
MASS_EVENT_CODE=crawler.fetch-page
```

Polling control/data flow:

1. `POST /worker-api/workers/register`
2. optional `POST /worker-api/worker-contexts/register`
3. `POST /worker-api/workers/{workerId}/online`
4. repeated `POST /worker-api/workers/{workerId}/heartbeat`
5. repeated `POST /worker-api/workers/{workerId}/poll`
6. `POST /worker-api/workers/{workerId}/results`
7. `POST /worker-api/workers/{workerId}/offline` on shutdown

Polling dispatch contract:

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

## 5. Realtime Paths

Realtime workers still rely on control-plane registration, but online presence
comes from the concrete adapter connection.

### WebSocket

- `adapterId=websocket`
- `transportHint=realtime`
- worker startup sends no HTTP polling calls
- the connection URL carries `workerId`
- the worker accepts canonical task-dispatch frames and returns canonical task-result frames

Default environment:

```text
WORKER_ID=node-worker-realtime-001
WS_URL=ws://127.0.0.1:18088/ws
```

### Socket

- `adapterId=socket`
- `transportHint=realtime`
- worker opens a plain socket connection, sends a hello frame, then waits for task dispatch
- no websocket compatibility fallback is part of this path

Default environment:

```text
WORKER_ID=node-worker-socket-001
SOCKET_HOST=127.0.0.1
SOCKET_PORT=18089
```

## 6. Task Submission

Producer traffic still goes through the normal task API:

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

Then approve it:

```bash
curl -X POST "http://127.0.0.1:8088/status/api/tasks/<taskId>/audit?approved=true&comment=external-worker-quickstart"
```

Expected behavior:

- task remains `READY` while no matching external worker is online
- once the right adapter-backed worker is online, dispatch reaches that worker
- output carries the sample identity through `integrationProbe` and/or `workerProfile`
- task converges to `TERMINAL`
- worker returns offline after process shutdown or transport disconnect

## 7. Black-Box Acceptance

Current executable acceptance coverage:

- `NodePollingWorkerBlackBoxIntegrationTest`
- `JavaPollingWorkerBlackBoxIntegrationTest`
- `NodeWebSocketWorkerBlackBoxIntegrationTest`
- `JavaWebSocketWorkerBlackBoxIntegrationTest`
- `NodeSocketWorkerBlackBoxIntegrationTest`
- `JavaSocketWorkerBlackBoxIntegrationTest`

These tests verify:

- registration and transport presence are distinct
- worker online/offline state is observable by runtime
- task dispatch and result ingest stay on the engine mainline
- `websocket` and `socket` can coexist under the same `realtime` family without cross-routing
- sample outputs identify which external worker actually handled the task

## 8. Extending To Another Language

For Python, Go, Rust, or another language, keep the same contract shape:

- one process-level worker identity
- explicit `adapterId + transportHint` at registration time
- capability registration through `eventBindings`
- local handler map keyed by `eventCode`
- canonical task-result callback keyed by `taskId + messageId`

Do not build a second routing model around websocket tuple history, deprecated
frame fields, or adapter-specific business semantics.
