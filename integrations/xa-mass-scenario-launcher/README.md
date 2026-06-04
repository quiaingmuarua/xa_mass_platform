# xa-mass-scenario-launcher

Status: Java SDK scenario launcher.

This module is the formal Java SDK based launcher for registering dev scenario
worker topology, starting HTTP/polling sample workers, and creating scenario
tasks against a running `xa-mass-server`.

It composes `sdk/xa-mass-java-sdk`; it does not redefine server,
engine, worker-pack, or transport ownership.

## Current Scope

- reads the existing scenario JSON files under `integrations/samples/dev/scenario`
- can bootstrap dev catalog and runtime rules through server-owned sample-only
  `/sample-api/bootstrap/**` endpoints for local development; these endpoints
  are enabled by default only in the server `dev` profile
- can skip dev bootstrap with `--skip-dev-bootstrap` so real-proof runs use
  pre-created catalog/rules/credentials and still exercise the same SDK-backed
  worker topology and task paths
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
- `--skip-dev-bootstrap`: do not call sample-only `/sample-api/bootstrap/**`; use pre-created catalog/rules
- `--dev-bootstrap[=true|false]`: explicitly enable or disable dev bootstrap. Default: `MASS_SCENARIO_DEV_BOOTSTRAP` or `true`
- `--scenario-dir`: scenario JSON directory. Default: `integrations/samples/dev/scenario`
- `--idle-timeout-ms`: exit launch mode after continuous idle time once launcher-managed polling tasks are terminal. Default: `60000`; `0` disables idle exit
- `--max-polling-workers`: maximum polling workers to start in launch mode. Default: `25`; `0` disables the cap
- `--register-only`: register catalog/rules/topology/tasks and exit without polling sessions

## Boundary

- `xa-mass-java-sdk` stays a pure external API client.
- dev-only bootstrap helpers stay local to this module and are optional; they
  are not a production prerequisite for WorkerGroup, AdapterNode, Worker, or
  task registration.
- `xa-mass-worker-pack` owns worker capabilities, not task creation or scenario
  orchestration.
