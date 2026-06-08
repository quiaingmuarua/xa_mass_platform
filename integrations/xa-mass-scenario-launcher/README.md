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
- credential bootstrap env mode can sync the checked-in local scenario
  catalog/rules through server control-plane APIs, then validate or create
  the local task API-key cache and workerId-bound worker credentials through
  operator login and API-key lifecycle APIs
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
initializer syncs the checked-in scenario catalog and rules through server
control-plane APIs, then prepares the task API-key cache file and registers
workerId-bound worker credentials for every expanded `workers.json` worker
through operator login and the real API-key lifecycle route:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-credential-bootstrap.jar \
  --config integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \
  --config integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json
```

The default worker launcher uses each `workers.json` entry's `workerKey`.
Those keys are registered by the initializer with matching `workerId`
bindings:

```bash
java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar \
  --base-url http://127.0.0.1:8088
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

Credential bootstrap options:

- `--config`: scenario task config. Reads `server.baseUrl` and
  `credentials.taskApiKeyFile`.
- `--kind`: `env`, `task`, or `worker`. Default: `env`. Env mode verifies
  the checked-in scenario catalog, prepares the task key cache, and registers
  workerId-bound worker credentials from `workers.json`. Worker credentials use
  `worker:poll` and wildcard project/event scopes for the checked-in local
  worker scenario.
- `--api-key-file`: cache file to validate/write for task mode. Standalone
  worker mode is legacy; prefer env mode for workerId-bound credentials.
- `--operator-user`: operator login user. Default: `MASS_OPERATOR_USER` or
  `ops-admin`.
- `--operator-password`: operator password. Default: `MASS_OPERATOR_PASSWORD`
  or `ops-admin`.
- `--principal-id`: task API-key principal id. Default:
  `crawler-task-producer-local`.
- `--project`: comma-separated project scopes. Default:
  `crawlerApp,deviceProbe` for the checked-in local task scenario.
- `--event-code`: comma-separated event scopes. Default:
  `crawler.fetch-page,stock.quote.fetch,probe.phone.metadata` for the
  checked-in local task scenario.
- `--no-create`: fail when the cache file is missing.
- `--no-refresh-stale-cache`: fail when the cache file exists but
  `/api/v1/api-keys:current` rejects it.

## Boundary

- `xa-mass-java-sdk` stays a pure external API client.
- catalog/rule/credential storage and authorization are server-owned. This
  module only provides a local initializer client that calls those server APIs
  before the task or worker launchers run.
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
- credential bootstrap is local/integration-test tooling. It may call server
  operator login, control-plane catalog/rule sync APIs, and API-key lifecycle
  APIs, but it is not an SDK public contract and must not print raw secrets
  after writing the cache file.
- worker config, `workers[]`, and worker credential files are deferred; use the
  existing worker launcher with `--scenario-dir` until that path is designed.
