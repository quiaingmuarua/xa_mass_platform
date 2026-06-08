# xa-mass-scenario-launcher

Status: Java SDK scenario launcher.

This module is the formal Java SDK based launcher for the two external
scenario roles against a running `xa-mass-server`:

- task producer: create scenario tasks and append items
- worker process: register worker topology and run Java SDK worker sessions

It composes `sdk/xa-mass-java-sdk`; it does not redefine server,
engine, worker-pack, or transport ownership.

## Current Scope

- task launcher can read a human task config file with `--config`
- worker launcher still reads existing worker specs under
  `integrations/samples/dev/scenario` through `--scenario-dir`
- assumes catalog, rules, and API-key credentials already exist through
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

Prepare the checked-in local scenario on the server first. The sample
`bootstrap.json` includes local fixture API-key raw secrets, so the import must
be marked explicitly as a local fixture:

```bash
java -jar xa-mass-server/target/xa-mass-server.jar \
  --mass.control-plane.seed.enabled=true \
  --mass.control-plane.seed.allow-local-fixture-raw-secrets=true \
  --mass.control-plane.seed.catalog-location=file:integrations/samples/dev/scenario/bootstrap.json \
  --mass.control-plane.seed.rules-location=file:integrations/samples/dev/scenario/rules.json
```

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
```

For human task-producer runs, start from the checked-in example config:

```bash
mkdir -p integrations/xa-mass-scenario-launcher/examples/secrets
printf 'crawler-task-api-key' > integrations/xa-mass-scenario-launcher/examples/secrets/task-api-key.txt

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \
  --config integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json
```

Config-internal relative paths, such as `credentials.taskApiKeyFile` and
`tasks[].items.path`, resolve relative to the config file directory. The task
config file reads `tasks[]` directly; it does not use `scenarioDir`, and a
top-level `workers` field is rejected because worker config is deferred.

The task config supports JSON array, JSONL, and text item files. Action aliases
are caller-side conveniences only: they resolve to item append `eventCode` and
optional payload mapping. Worker selection stays explicit in `sharedConfig`,
for example `sharedConfig.workerGroupId`.

Legacy `--scenario-dir` fixtures may still use `body.items` and
`generatedItems` in `tasks.json` for agent proof coverage. Human task config
should prefer real item source files instead of generated fixture items.

The worker launcher is intentionally unchanged for now:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar \
  --base-url http://127.0.0.1:8088
```

Agent proof and legacy fixture commands can still use `--scenario-dir`:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \
  --base-url http://127.0.0.1:8088
```

Options:

- `--base-url`: server HTTP base URL. Default: `MASS_BASE_URL` or `http://127.0.0.1:8088`
- `--config`: task launcher config file. Worker launcher config is deferred.
- `--websocket-url`: optional server WebSocket URL for realtime launcher workers. Default: `MASS_WEBSOCKET_URL`
- `--task-api-key`: default task API key. Default: `MASS_TASK_API_KEY` or `crawler-task-api-key`
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
  with task API-key credentials.
- task config is not a credential/bootstrap mechanism. API keys referenced by
  config files must already exist in the target server.
- worker config, `workers[]`, and worker credential files are deferred; use the
  existing worker launcher with `--scenario-dir` until that path is designed.
