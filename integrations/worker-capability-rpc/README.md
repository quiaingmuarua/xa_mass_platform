# Worker Capability RPC Acceptance

This Java 21 integration is the external proof client for the Server-owned
Scenario RPC Lab. It does not own an RPC Process or call WorkerGroup Items
directly.

## Proven path

```text
phone-seed.txt / string-seed.txt
  -> POST /api/v1/scenario-rpc/input-files/{fileName}
  -> six POST /api/v1/scenario-rpc/runs
  -> six GET /api/v1/scenario-rpc/output-files/{fileName}
  -> downloaded JSONL validation
  -> persistent Worker identity validation
```

The Server owns the six finite Scenarios, line parsing, concurrency, public
WorkerGroup RPC calls, result validation, and authoritative output files. This
integration verifies ten results per Scenario, sixty total results, globally
unique Message IDs, and twenty persistent canonical Worker IDs across the two
JVM Lab Groups.

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
--concurrency=10
--request-timeout-millis=120000
```

`scenario-id` names this acceptance proof and its uploaded files; the Server
still generates each execution's timestamped Message ID prefix. Uploaded inputs
and Server outputs are permanent local Lab files and are cleaned manually.

Unit proof:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:test
```
