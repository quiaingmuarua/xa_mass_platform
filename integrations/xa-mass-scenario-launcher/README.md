# xa-mass-scenario-launcher

Status: Java SDK scenario launcher.

This module is the formal Java SDK based launcher for the two external
scenario roles against a running `xa-mass-server`:

- task producer: create scenario tasks and append items
- worker process: register worker topology and run Java SDK worker sessions

It composes `sdk/xa-mass-java-sdk`; it does not redefine server,
engine, worker-pack, or transport ownership.

## Current Scope

- reads the existing scenario JSON files under `integrations/samples/dev/scenario`
- assumes catalog, rules, and submitter credentials already exist through
  explicit server seed/import or test fixtures
- worker launcher registers WorkerGroups, AdapterNodes, NodeGroupBindings, and Workers through
  public worker APIs via `xa-mass-java-sdk`
- task launcher creates tasks and appends items through public task APIs via
  `xa-mass-java-sdk`
- worker launcher starts Java SDK `PollingWorkerSession` workers for polling
  scenario specs and WebSocket sessions when `--websocket-url` is provided
- worker launcher keeps running until the process is interrupted
- task launcher does not register workers or start worker sessions
- worker launcher does not create tasks

## Usage

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar \
  --base-url http://127.0.0.1:8088

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \
  --base-url http://127.0.0.1:8088
```

Options:

- `--base-url`: server HTTP base URL. Default: `MASS_BASE_URL` or `http://127.0.0.1:8088`
- `--websocket-url`: optional server WebSocket URL for realtime launcher workers. Default: `MASS_WEBSOCKET_URL`
- `--task-api-key`: default task API key. Default: `MASS_TASK_SUBMITTER_KEY` or `crawler-submitter-key`
- `--worker-api-key`: optional worker API key override. Default: each worker spec's `workerKey`
- `--scenario-dir`: scenario JSON directory. Default: `integrations/samples/dev/scenario`
- `--max-polling-workers`: maximum polling workers to start in worker launcher. Default: `25`; `0` disables the cap

## Boundary

- `xa-mass-java-sdk` stays a pure external API client.
- catalog/rule/credential preparation is not owned by this module; use
  server-owned seed/import or real control-plane setup before running it.
- `xa-mass-worker-pack` owns worker capabilities, not task creation or scenario
  orchestration.
- task and worker launchers are separate process roles. Do not reintroduce a
  single main that registers workers, starts sessions, and creates tasks in one
  flow.
- task lifecycle commands such as seal/approve are operator/server-console
  behavior. The task launcher deliberately does not send `/commands` requests
  with submitter API-key credentials.
