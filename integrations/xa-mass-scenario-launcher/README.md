# xa-mass-scenario-launcher

Status: Java SDK scenario launcher.

This module is the formal Java SDK based launcher for registering dev scenario
worker topology, starting HTTP/polling sample workers, and creating sample tasks
against a running `xa-mass-server`.

It composes `sdk/xa-mass-java-sdk`; it does not redefine server,
engine, worker-pack, or transport ownership.

## Current Scope

- reads the existing scenario JSON files under `integrations/samples/dev/scenario`
- bootstraps dev catalog and runtime rules through the dev-only
  `/sample-api/bootstrap/**` endpoints
- registers WorkerGroups, AdapterNodes, NodeGroupBindings, and Workers through
  public worker APIs via `xa-mass-java-sdk`
- creates tasks, appends items, seals, and approves through public task APIs via
  `xa-mass-java-sdk`
- default launch mode starts Java SDK `PollingWorkerSession` workers for polling
  scenario specs and keeps running until shutdown or until the launcher has been
  idle and its managed polling tasks have reached terminal state
- launch mode auto-approves staged tasks whose `sharedConfig.workerGroupId`
  matches a started polling worker group, so the default command can execute
  the polling scenario
- `--register-only` keeps the old seed-and-exit behavior for control-plane-only
  setup

## Usage

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-launcher.jar \
  --base-url http://127.0.0.1:8088
```

Options:

- `--base-url`: server HTTP base URL. Default: `MASS_BASE_URL` or `http://127.0.0.1:8088`
- `--task-api-key`: default task API key. Default: `MASS_TASK_SUBMITTER_KEY` or `crawler-submitter-key`
- `--task-command-api-key`: task command API key for seal/approve. Default: `MASS_TASK_COMMAND_KEY` or `public-probe-ops-key`
- `--worker-api-key`: optional worker API key override. Default: each worker spec's `workerKey`
- `--bootstrap-key`: dev bootstrap key. Default: `SAMPLE_BOOTSTRAP_KEY` or `dev-bootstrap-key`
- `--scenario-dir`: scenario JSON directory. Default: `integrations/samples/dev/scenario`
- `--idle-timeout-ms`: exit launch mode after continuous idle time once launcher-managed polling tasks are terminal. Default: `60000`; `0` disables idle exit
- `--max-polling-workers`: maximum polling workers to start in launch mode. Default: `25`; `0` disables the cap
- `--register-only`: register catalog/rules/topology/tasks and exit without polling sessions

## Boundary

- `xa-mass-java-sdk` stays a pure external API client.
- dev-only bootstrap helpers stay local to this module.
- `xa-mass-worker-pack` owns worker capabilities, not task creation or scenario
  orchestration.
