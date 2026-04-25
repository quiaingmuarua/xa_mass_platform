# External Worker Quickstart

This document is the shortest runnable path for a third-party language worker.

Current mainline:

- external workers are polling workers
- business execution still enters through `POST /status/api/tasks`
- worker capability identity is global `eventCode`
- external workers do not receive direct gateway control/business frames

Use this when you want a Node, Python, or Go process to join the platform without embedding the Java SDK.

## 1. Runtime Shape

One external worker participates through three explicit steps:

1. register worker capability with `eventBindings`
2. mark presence with `online` and `heartbeat`
3. poll `TaskDispatchItem`, execute by `eventCode`, and submit `TaskResultReport`

That means:

- the worker is not a WebSocket protocol client
- the worker does not speak gateway tuple routing
- the worker never bypasses engine scheduling
- targeting and routing still happen through task create plus normal engine matching

## 2. Dev-App Demo Credentials

The verified dev runtime `com.xa.mass.mock.MockApplicationSpringBootApp` seeds a minimal crawler demo pair:

- task submitter credential: `crawler-submitter-key`
- worker polling credential: `node-worker-key`

Bound scope:

- project: `crawlerApp`
- eventCode: `crawler.fetch-page`
- workerId: `node-worker-api-001`

These are development-only fixtures for local verification. They are not a production auth model.

## 3. Start The Dev Runtime

From repo root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-sdk-api/target/classes:xa-mass-web/target/classes:xa-mass-engine/target/classes:transport/websocket-adapter/target/classes:transport/api/target/classes:transport/polling-adapter/target/classes:transport/runtime/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Default local base URL:

```text
http://127.0.0.1:8088
```

## 4. Run The Node Worker

Requirements:

- Node.js 18 or newer
- dev runtime already started

Example:

```bash
node examples/external-worker/node/polling_worker.mjs
```

Default environment used by the script:

```text
MASS_BASE_URL=http://127.0.0.1:8088
MASS_WORKER_ID=node-worker-api-001
MASS_WORKER_KEY=node-worker-key
MASS_PROJECT=crawlerApp
MASS_EVENT_CODE=crawler.fetch-page
```

What the script does:

1. `POST /worker-api/workers/register`
2. `POST /worker-api/worker-contexts/register`
3. `POST /worker-api/workers/{workerId}/online`
4. repeated `POST /worker-api/workers/{workerId}/heartbeat`
5. repeated `POST /worker-api/workers/{workerId}/poll`
6. `POST /worker-api/workers/{workerId}/results`
7. `POST /worker-api/workers/{workerId}/offline` on shutdown

## 5. Submit A Demo Task

The producer side still goes through the normal task API.

Example:

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

- engine matches the task by `eventCode`
- the polling worker receives a `TaskDispatchItem`
- the Node handler fetches the page
- result is written back through `TaskMsg.output`
- task converges to `TERMINAL`

## 6. Minimal Protocol Contract

External worker registration request:

```json
{
  "workerId": "node-worker-api-001",
  "workerGroupId": "node-runtime",
  "attributes": {
    "lang": "node"
  },
  "eventBindings": [
    {
      "eventCode": "crawler.fetch-page",
      "projectCodes": ["crawlerApp"]
    }
  ]
}
```

Polled item shape to care about:

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

Contract note:

- `TaskDispatchItem.input` is the normalized logical payload for the worker handler
- SDK-internal payload wrappers such as `{ "type": "json", "data": ... }` are not part of the worker contract

Result submit shape:

```json
{
  "taskId": "...",
  "messageId": "...",
  "success": true,
  "detail": "crawler-success",
  "output": {
    "url": "https://example.com",
    "statusCode": 200,
    "title": "Example Domain"
  }
}
```

## 7. Handler Model

The worker should dispatch locally by `eventCode`.

That is the key point:

- capability registration is `eventBindings[].eventCode`
- polled work identity is `TaskDispatchItem.eventCode`
- local execution switch is `eventCode`
- worker capability matching in the engine is `supportedEventCodes`

Do not build a second routing model around gateway frame type or transport adapter details.

## 8. Extending Beyond Node

For Python, Go, Rust, or any other language, keep the same shape:

- one process-level worker identity
- declare capability with `eventBindings`
- optional worker context if routing needs it
- local handler map keyed by `eventCode`
- task result callback with `taskId + messageId`

Only the local HTTP client and handler implementation changes.
