# Samples

Status: current sample worker owner README.

This directory holds runnable third-party worker references used by executable
black-box acceptance.

Rules:

- samples stay outside embedded SDK/demo runtime internals
- samples speak public transport/control contracts only
- integration tests may launch samples as external processes
- `examples/` stays for lightweight snippets, not black-box validation assets

## Current Mainline

| Sample path | Language | adapterId | transportHint | Entry | Verified black-box test |
| --- | --- | --- | --- | --- | --- |
| `worker-polling/node` | Node.js | `polling` | `polling` | `node samples/worker-polling/node/worker.mjs` | `NodePollingWorkerBlackBoxIntegrationTest` |
| `worker-polling/java` | Java | `polling` | `polling` | `java -jar samples/worker-polling/java/target/worker-polling-java-sample.jar` | `JavaPollingWorkerBlackBoxIntegrationTest` |
| `worker-websocket/node` | Node.js | `websocket` | `realtime` | `node samples/worker-websocket/node/worker.mjs` | `NodeWebSocketWorkerBlackBoxIntegrationTest` |
| `worker-websocket/java` | Java | `websocket` | `realtime` | `java -jar samples/worker-websocket/java/target/worker-websocket-java-sample.jar` | `JavaWebSocketWorkerBlackBoxIntegrationTest` |
| `worker-socket/node` | Node.js | `socket` | `realtime` | `node samples/worker-socket/node/worker.mjs` | `NodeSocketWorkerBlackBoxIntegrationTest` |
| `worker-socket/java` | Java | `socket` | `realtime` | `java -jar samples/worker-socket/java/target/worker-socket-java-sample.jar` | `JavaSocketWorkerBlackBoxIntegrationTest` |

## Dev Launcher

For the dev Spring Boot shell there is now a sample supervisor script at
`samples/dev/launch-workers.mjs`.

- it bootstraps sample project/event/submitter catalog through `/sample-api/bootstrap/catalog`
- it replaces runtime default rules through `/sample-api/bootstrap/rules`
- it registers the curated sample worker set through `/worker-api/*`
- it creates curated sample tasks through `POST /api/v1/tasks` plus explicit item append
- it starts the external sample worker processes
- `XaMassServerApplication` can launch it automatically in `dev` profile
  when `sample.worker.auto-start=true`
- worker and task seed definitions live under `samples/dev/*.json`

## Acceptance Signals

Every sample should remain provable through an external-process black-box test:

- control-plane registration alone does not mark a realtime worker online
- worker becomes `ONLINE` only after transport presence is established
- engine scheduling still gates task dispatch; samples do not bypass task mainline
- task result reaches `TERMINAL` through normal result ingest
- output identifies the executing sample through `integrationProbe` and/or `workerProfile`
- worker returns to offline after disconnect or shutdown
- when multiple realtime adapters coexist, routing stays pinned to `adapterId`

## Reading Order

- start with [doc/EXTERNAL_WORKER_QUICKSTART.md](../doc/EXTERNAL_WORKER_QUICKSTART.md)
- then use the per-sample README under each subdirectory for local commands
- use `xa-mass-server` black-box tests as the executable acceptance truth

## Addition Rule

- add new languages only when they increase cross-language contract value
- keep new samples aligned with `adapterId + transportHint` semantics
- do not add samples that depend on embedded mock clients or deprecated seams
