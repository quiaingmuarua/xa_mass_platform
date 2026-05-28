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
| `worker-polling/node` | Node.js | `polling` | `polling` | `node integrations/samples/node/worker-polling/worker.mjs` | `NodePollingWorkerBlackBoxIntegrationTest` |
| `worker-polling/java` | Java, via `xa-mass-java-sdk` | `polling` | `polling` | `java -jar integrations/samples/java/worker-polling/target/worker-polling-java-sample.jar` | `JavaPollingWorkerBlackBoxIntegrationTest` |
| `worker-websocket/node` | Node.js | `websocket` | `realtime` | `node integrations/samples/node/worker-websocket/worker.mjs` | `NodeWebSocketWorkerBlackBoxIntegrationTest` |
| `worker-websocket/java` | Java | `websocket` | `realtime` | `java -jar integrations/samples/java/worker-websocket/target/worker-websocket-java-sample.jar` | `JavaWebSocketWorkerBlackBoxIntegrationTest` |
| `worker-socket/node` | Node.js | `socket` | `realtime` | `node integrations/samples/node/worker-socket/worker.mjs` | `NodeSocketWorkerBlackBoxIntegrationTest` |
| `worker-socket/java` | Java | `socket` | `realtime` | `java -jar integrations/samples/java/worker-socket/target/worker-socket-java-sample.jar` | `JavaSocketWorkerBlackBoxIntegrationTest` |

## Dev Launcher

For the dev Spring Boot shell there is now a sample supervisor script at
`integrations/samples/dev/scenario/launch-workers.mjs`.

- it bootstraps sample project/event/submitter catalog through `/sample-api/bootstrap/catalog`
- it replaces runtime default rules through `/sample-api/bootstrap/rules`
- it registers the curated sample worker set through `/worker-api/v1/**`
- it creates curated sample tasks through `POST /api/v1/tasks` plus explicit item append
- it starts the external sample worker processes
- `XaMassServerApplication` can launch it automatically in `dev` profile
  when `sample.worker.auto-start=true`
- worker and task seed definitions live under `integrations/samples/dev/scenario/*.json`

## Acceptance Signals

Every sample should remain provable through an external-process black-box test:

- control-plane registration alone does not mark a realtime worker online
- worker becomes `ONLINE` only after transport presence is established
- polling samples report capability at startup via `:report-capability` and
  bounded worker state via `:report-state`; both go through the public
  `/worker-api/v1` contract
- the Java polling sample uses `integrations/xa-mass-java-sdk` and its managed
  `PollingWorkerSession`; raw HTTP polling code should not be reintroduced
  there
- polling samples can acknowledge operator-issued worker commands via
  `/commands/{commandId}:ack`
- engine scheduling still gates task dispatch; samples do not bypass task mainline
- task result reaches `TERMINAL` through normal result ingest
- output identifies the executing sample through `integrationProbe` and/or `workerProfile`
- worker returns to offline after disconnect or shutdown
- when multiple realtime adapters coexist, routing stays pinned to `adapterId`

## Reading Order

- start with [doc/EXTERNAL_WORKER_QUICKSTART.md](../../doc/EXTERNAL_WORKER_QUICKSTART.md)
- then use the per-sample README under each subdirectory for local commands
- use `xa-mass-server` black-box tests as the executable acceptance truth

## Addition Rule

- add new languages only when they increase cross-language contract value
- keep new samples aligned with `adapterId + transportHint` semantics
- do not add samples that depend on embedded mock clients or deprecated seams
