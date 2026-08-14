# Worker Capability RPC Acceptance

This Java 21 integration is the external proof client for the Server-owned
Task Batch Lab. It does not own an RPC Process or call WorkerGroup Items
directly.

## Proven path

```text
phone-seed.txt / string-seed.txt
  -> POST /api/v1/task-batches/input-files/{fileName}
  -> six POST /api/v1/task-batches/runs
  -> six GET /api/v1/task-batches/output-files/{fileName}
  -> downloaded JSONL validation
  -> persistent Worker identity validation
```

The client supplies six explicit WorkerGroup/Event/Payload-key cases. Server
owns line-to-Payload conversion, one batch Item append, pending-result polling,
and authoritative output files. This integration verifies six successful runs,
ten results per batch, sixty total results, globally unique Message IDs,
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
--proof-id=worker-capability-demo
--phone-seed-path=phone-seed.txt
--string-seed-path=string-seed.txt
--result-dir=results
--scenario-worker-lab-root=../../data/scenario-workers
--maximum-wait-millis=30000
--request-timeout-millis=120000
```

`proof-id` names this acceptance proof and its uploaded files; each Server
run creates its own monotonic Task Batch run ID. Uploaded inputs and published
Server outputs are permanent local Lab files and are cleaned manually. A run
that exhausts its wait budget is `partial`; this acceptance requires all six runs
to be `succeeded`.

Unit proof:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:test
```
