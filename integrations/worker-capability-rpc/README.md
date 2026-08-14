# Worker Capability RPC Acceptance

This Java 21 integration is the external proof client for the Server-owned
Scenario RPC Lab. It does not own an RPC Process or call WorkerGroup Items
directly.

## Proven path

```text
phone-seed.txt / string-seed.txt
  -> POST /api/v1/scenario-rpc/input-files/{fileName}
  -> six POST /api/v1/scenario-rpc/scenarios
  -> six POST /api/v1/scenario-rpc/scenarios/{scenarioId}:run
  -> six GET /api/v1/scenario-rpc/output-files/{fileName}
  -> downloaded JSONL validation
  -> persistent Worker identity validation
```

The Server owns the six finite Scenario types, one-shot instances, line
parsing, one batch Item append, pending-result polling, result validation, and
authoritative output files. This integration verifies six successful runs,
ten results per Scenario, sixty total results, globally unique Message IDs,
and twenty persistent canonical Worker IDs across the two JVM Lab Groups.

## Run

Start Redis, the Python Kernel, and Server with the `scenario-workers` Profile,
then run:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:runRpcScenario
```

Optional arguments use `--name=value`:

```text
--server-base-url=http://127.0.0.1:18082
--scenario-id=worker-capability-demo
--phone-seed-path=phone-seed.txt
--string-seed-path=string-seed.txt
--result-dir=results
--scenario-worker-lab-root=../../data/scenario-workers
--load-interval-millis=100
--maximum-load-rounds=300
--request-timeout-millis=120000
```

`scenario-id` names this acceptance proof and its uploaded files; the Server
creates its own one-shot Scenario IDs. Uploaded inputs and published Server
outputs are permanent local Lab files and are cleaned manually. A run that
exhausts its polling rounds is `partial`; this acceptance requires all six runs
to be `succeeded`.

Unit proof:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:test
```
