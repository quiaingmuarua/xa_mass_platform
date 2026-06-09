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
- scenario environment initialization is owned by
  `tools/xa-mass-admin-cli env init`
- worker launcher still reads existing worker specs under
  `integrations/samples/dev/scenario` through `--scenario-dir`
- assumes only operator authentication exists on a clean server; scenario
  catalog, rules, and API-key credentials are prepared by the explicit
  initializer step
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

Start the server in a local profile first. `durable-local` may start clean:
only the minimal operator credential is expected at startup. Scenario catalog,
rules, and task/worker API keys are initialized after the server is running
through operator control-plane APIs.

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
```

For local scenario runs, initialize the scenario environment first. The
preferred initializer is the server-owned admin CLI. It syncs the configured
catalog/rules manifests through server control-plane APIs, verifies/creates
task and worker API keys from typed desired credential state, and writes only
configured gitignored cache/marker files:

```bash
java -jar tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar \
  env init --config tools/xa-mass-admin-cli/examples/admin-env.local.json

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \
  --config integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json
```

For packaged confidence runs, prefer the testing script because it keeps actor
roles separated: the task launcher creates and appends work, `xa-mass-admin
task command` performs the operator-only `APPROVE`, and the Java SDK verifier
waits for result readback:

```bash
MASS_OPERATOR_PASSWORD=ops-admin xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
```

The task launcher still supports `--wait-visible-success` for already-approved
or externally controlled tasks, but it deliberately does not send task
`/commands` requests with task API-key credentials.

The default worker launcher uses each `workers.json` entry's `workerKey`.
Those keys are registered by the initializer with matching `workerId`
bindings:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar \
  --base-url http://127.0.0.1:8088
```

For worker read-model health fixtures, the worker launcher can register worker
topology and mark `api-online` workers online without starting long-running
polling sessions:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar \
  --base-url http://127.0.0.1:8088 \
  --scenario-dir xa-mass-testing/target/worker-read-health/<run>/scenario \
  --register-api-online-only
```

This mode is for packaged worker read health only. It proves worker read-model
scale through supported worker APIs; it does not prove task execution or live
worker-session scale.

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

Raw-secret fixture seed remains available only as an explicit local fallback:
`integrations/samples/dev/scenario/bootstrap.json` contains devOnly API-key raw
secrets and requires
`--mass.control-plane.seed.allow-local-fixture-raw-secrets=true`. Do not use it
as the preferred scenario credential path.

Startup catalog/rule seed remains a fixture/support path only. The preferred
local path is clean server startup followed by the scenario environment
initializer.

Agent proof and legacy fixture commands can still use `--scenario-dir`:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \
  --base-url http://127.0.0.1:8088
```

Options:

- `--base-url`: server HTTP base URL. Default: `MASS_BASE_URL` or `http://127.0.0.1:8088`
- `--config`: task launcher config file. Worker launcher config is deferred.
- `--websocket-url`: optional server WebSocket URL for realtime launcher workers. Default: `MASS_WEBSOCKET_URL`
- `--task-api-key`: default task API key. Default: `MASS_TASK_API_KEY`,
  generated `examples/secrets/task-api-key.txt`, or `crawler-task-api-key`
- `--worker-api-key`: optional worker API key override. Default: each worker spec's `workerKey`
- `--worker-api-key-file`: optional explicit worker API key override file.
  Do not use it for the checked-in local scenario because worker credentials
  are workerId-bound.
- `--scenario-dir`: scenario JSON directory. Default: `integrations/samples/dev/scenario`
- `--max-polling-workers`: maximum polling workers to start in worker launcher. Default: `25`; `0` disables the cap
- `--register-api-online-only`: worker launcher only; register topology and
  mark `api-online` workers online without starting sessions

## Boundary

- `xa-mass-java-sdk` stays a pure external API client.
- catalog/rule/credential storage and authorization are server-owned. This
  module consumes the environment initialized by the admin CLI before the task
  or worker launchers run.
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
- operator login, control-plane catalog/rule sync APIs, and API-key lifecycle
  APIs stay in `tools/xa-mass-admin-cli`; do not add them back to this module.
- worker config, `workers[]`, and worker credential files are deferred; use the
  existing worker launcher with `--scenario-dir` until that path is designed.
