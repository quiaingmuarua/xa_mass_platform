# Samples

Status: current sample worker owner README.

This directory holds runnable third-party worker references used by executable
black-box acceptance.

Samples are protocol/dev fixtures. They are not the long-term public SDK product
surface. Java SDK-backed external execution is owned by
[`../xa-mass-scenario-launcher`](../xa-mass-scenario-launcher/README.md), and
directory-level integration ownership is summarized in
[`../README.md`](../README.md).

Rules:

- samples stay outside embedded SDK/demo runtime internals
- samples speak public transport/control contracts only
- integration tests may launch samples as external processes
- `examples/` stays for lightweight snippets, not black-box validation assets

## Current Mainline

| Sample path | Language | adapterId | transportHint | Entry | Verified black-box test |
| --- | --- | --- | --- | --- | --- |
| `worker-polling/node` | Node.js | `polling` | `polling` | `node integrations/samples/node/worker-polling/worker.mjs` | `NodePollingWorkerBlackBoxIntegrationTest` |
| `worker-websocket/node` | Node.js | `websocket` | `realtime` | `node integrations/samples/node/worker-websocket/worker.mjs` | `NodeWebSocketWorkerBlackBoxIntegrationTest` |
| `worker-socket/node` | Node.js | `socket` | `realtime` | `node integrations/samples/node/worker-socket/worker.mjs` | `NodeSocketWorkerBlackBoxIntegrationTest` |

## Dev Launcher

For the dev Spring Boot shell there is now a sample supervisor script at
`integrations/samples/dev/scenario/launch-workers.mjs`.

- it assumes project/event/API-key catalog and runtime default rules were
  prepared through server-owned seed/import or an equivalent test fixture
- it registers the curated sample worker set through `/worker-api/v1/**`
- it creates curated sample tasks through `POST /api/v1/tasks` plus explicit item append
- it starts the external sample worker processes
- `XaMassServerApplication` can launch it automatically in `dev` profile
  when `sample.worker.auto-start=true`
- worker and task seed definitions live under `integrations/samples/dev/scenario/*.json`

Manual registration against an already running dev server:

```bash
node integrations/samples/dev/scenario/launch-workers.mjs --register-only
```

Use this when you want the console populated without starting managed realtime
sample worker processes. It registers WorkerGroups, adapter nodes, workers,
online API-polling workers, and sample tasks through public HTTP APIs, then
exits. Catalog metadata and rules are explicit seed/import inputs, not launcher
writes.

Full external sample launch:

```bash
node integrations/samples/dev/scenario/launch-workers.mjs
```

The full launch keeps the process alive because it also owns the managed
realtime sample worker child processes.

## Java SDK Scenario Launcher

Formal SDK-based scenario registration lives in
`integrations/xa-mass-scenario-launcher`.

Use it when the goal is to run task/worker registration plus managed Java SDK
worker sessions:

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-launcher.jar
```

The Node launcher remains the demo/dev process supervisor for launching sample
realtime worker child processes. The Java launcher is the formal SDK-composition
path for WorkerGroup, AdapterNode, NodeGroupBinding, polling/WebSocket worker
sessions, and task seeding. Use `--register-only` when you only want
control-plane data registered and do not want worker sessions started.
Task shell/item writes use API-key credentials; task commands such as seal and
approve use the launcher command credential because task commands are
operator-style lifecycle actions.
In launch mode it starts a bounded set of polling workers, starts WebSocket
workers when `--websocket-url` is supplied, and auto-approves staged tasks that
target those started worker groups.

Current dev scenario shape:

- 2 managed realtime Node workers are launched as external processes.
- 100 polling phone-device workers are registered and marked online through the
  public worker API. They model a larger device fleet for matching review
  without starting 100 local processes.
- `bootstrap.json` is the explicit seed/import catalog source for dev-only
  API-key credentials, including per-worker `worker:poll` credentials for the
  100 generated polling workers.
- `tasks.json` creates normal approved realtime sample tasks plus a sealed but
  unapproved `deviceProbe/probe.phone.metadata` task with 1000 generated items.
- The phone-device task uses
  `targetWorkerAttributes.fingerprintProfile=fp-sg-alpha`; only 25 of the 100
  generated workers carry that fingerprint, so the console can prove
  group-first plus attribute-based matching.
- Large item batches are appended in chunks of 500 to stay inside the public
  task ingest limit.

## Acceptance Signals

Every sample should remain provable through an external-process black-box test:

- control-plane registration alone does not mark a realtime worker online
- worker becomes `ONLINE` only after transport presence is established
- polling samples report capability at startup via `:report-capability` and
  bounded worker state via `:report-state`; both go through the public
  `/worker-api/v1` contract
- Java executable SDK proof lives in `integrations/xa-mass-scenario-launcher`;
  standalone Java sample apps are not a current product surface
- polling samples can acknowledge operator-issued worker commands via
  `/commands/{commandId}:ack`
- engine scheduling still gates task dispatch; samples do not bypass task mainline
- task result reaches `TERMINAL` through normal result ingest
- output identifies the executing sample through `integrationProbe` and/or `workerProfile`
- worker returns to offline after disconnect or shutdown
- when multiple realtime adapters coexist, routing stays pinned to `adapterId`

## Reading Order

- start with [sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md](../../sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md)
- use [integrations/README.md](../README.md) for module ownership
- then use the Node per-sample README or Java scenario-launcher README for
  local commands
- use `xa-mass-server` black-box tests as the executable acceptance truth

## Addition Rule

- add new languages only when they increase cross-language contract value
- keep new samples aligned with `adapterId + transportHint` semantics
- do not add samples that depend on embedded mock clients or deprecated seams
