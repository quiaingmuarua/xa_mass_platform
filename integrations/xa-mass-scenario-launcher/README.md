# xa-mass-scenario-launcher

Status: first-slice Java SDK scenario launcher.

This module is the formal Java SDK based launcher for registering dev scenario
worker topology and sample tasks against a running `xa-mass-server`.

It composes `integrations/xa-mass-java-sdk`; it does not redefine server,
engine, worker-pack, or transport ownership.

## Current Scope

- reads the existing scenario JSON files under `integrations/samples/dev/scenario`
- bootstraps dev catalog and runtime rules through the dev-only
  `/sample-api/bootstrap/**` endpoints
- registers WorkerGroups, AdapterNodes, NodeGroupBindings, and Workers through
  public worker APIs via `xa-mass-java-sdk`
- creates tasks, appends items, seals, and approves through public task APIs via
  `xa-mass-java-sdk`
- marks `startMode=api-online` workers online and reports capability/state

First slice supports `--register-only` only. It does not spawn external worker
processes.

## Usage

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package

java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-launcher.jar \
  --register-only \
  --base-url http://127.0.0.1:8088
```

Options:

- `--base-url`: server HTTP base URL. Default: `MASS_BASE_URL` or `http://127.0.0.1:8088`
- `--task-api-key`: default task API key. Default: `MASS_TASK_SUBMITTER_KEY` or `crawler-submitter-key`
- `--task-command-api-key`: task command API key for seal/approve. Default: `MASS_TASK_COMMAND_KEY` or `public-probe-ops-key`
- `--worker-api-key`: optional worker API key override. Default: each worker spec's `workerKey`
- `--bootstrap-key`: dev bootstrap key. Default: `SAMPLE_BOOTSTRAP_KEY` or `dev-bootstrap-key`
- `--scenario-dir`: scenario JSON directory. Default: `integrations/samples/dev/scenario`

## Boundary

- `xa-mass-java-sdk` stays a pure external API client.
- dev-only bootstrap helpers stay local to this module.
- `xa-mass-worker-pack` may provide worker capability descriptors later, but it
  must not own task creation or scenario orchestration.
