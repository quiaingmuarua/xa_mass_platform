# Worker Fleet Acceptance

This Java 21 module is the external, black-box acceptance client for the
Scenario Worker Fleet. It imports only the transport-neutral Worker Delivery
Contract and calls public Runtime APIs with the JDK HTTP client. It does not
construct or control Scenario Workers, `JavaWorkerManager`, Adapter, Server, or
Kernel implementations.

## Proof boundary

The checked [`fleet-spec.json`](fleet-spec.json) names the
`scenario-websocket` Endpoint Manager and the exact ten configured
`labWorkerKey` values in each of the two Scenario WorkerGroups. It does not
freeze Worker IDs, Worker Properties, timestamps, or business Result payloads.

The `initial` phase proves these relationships:

```text
checked labWorkerKeys
  <-> exact schema-v2 Scenario Lab line records without Worker IDs
  <-> Runtime Worker Preview Lab-coordinate mapping
  <-> 20 globally unique Server-owned Worker IDs
  <-> public Network Runtime View reports connected
  <-> one probe Direct Call returns observed/200 for every target
  <-> same-round Worker Properties snapshots match Adapter-local observations
```

The `restart` phase repeats the same checks after only the standalone Scenario
Worker Host process is restarted and additionally requires the complete
`labWorkerKey -> workerId` mapping to equal the successful initial evidence.
Only Network observation is retried. Probe and Properties Direct Calls are each
issued once after all routes are connected, so a missing Command or Result is
not hidden by an integration retry.

## Evidence

Each phase writes schema-versioned JSON containing only the proof ID, phase,
Endpoint Manager ID, expected counts and Lab Worker keys, Worker identity mapping,
matched Worker ID sets, and safe differences. It never writes opaque Direct
Call payloads, full Results, complete Properties, or concrete observation
timestamps. Failure attempts to write partial evidence before exiting nonzero.

## Run

Start Redis and one Java Server with the `scenario-workers` profile. Server
owns the four Java Kernel Pacer applications and its configured Adapter, but
starts no Workers. Start the standalone Host against a Scenario Lab root ending in
`data/scenario-workers`, then run:

```powershell
.\gradlew.bat :scenario_workers_jvm:runScenarioWorkers `
  --args="--runtime-api-base-url=http://127.0.0.1:18082 `
  --sandbox-root=C:\proof\data\scenario-workers"
```

```powershell
.\gradlew.bat :integrations:worker-fleet-acceptance:runFleetAcceptance `
  --args="--phase=initial --proof-id=local-fleet `
  --server-base-url=http://127.0.0.1:18082 `
  --fleet-spec=C:\path\to\fleet-spec.json `
  --scenario-worker-lab-root=C:\proof\data\scenario-workers `
  --evidence-file=C:\proof\initial.json"
```

After stopping and restarting only the same Worker Host while retaining Server,
Pacer, Redis and Lab state:

```powershell
.\gradlew.bat :integrations:worker-fleet-acceptance:runFleetAcceptance `
  --args="--phase=restart --proof-id=local-fleet `
  --server-base-url=http://127.0.0.1:18082 `
  --fleet-spec=C:\path\to\fleet-spec.json `
  --scenario-worker-lab-root=C:\proof\data\scenario-workers `
  --evidence-file=C:\proof\restart.json `
  --baseline-file=C:\proof\initial.json"
```

Both phases accept `--maximum-wait-millis` (default `30000`) and
`--request-timeout-millis` (default `120000`). Unit and fixture proof:

```powershell
.\gradlew.bat :integrations:worker-fleet-acceptance:test
```
