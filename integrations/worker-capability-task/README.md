# Worker Capability Task Acceptance

This Java 21 integration is an external proof client for ordinary finite Task
APIs. It owns no Kernel, Server, Adapter, Worker implementation, Redis access,
or Server-side file storage.

## Proven path

```text
phone-seed.txt / string-seed.txt
  -> caller reads ten lines from each file
  -> POST /api/v1/tasks (one finite Task per WorkerGroup)
  -> line x three Event Names = 30 Items per Task
  -> POST /api/v1/tasks/{taskId}/items in chunks of at most 100
  -> POST /api/v1/tasks/{taskId}/approve
  -> POST /api/v1/tasks/{taskId}/results:export
  -> caller correlates exported messageIds with its local manifest
  -> safe Capability Task evidence
```

The two Tasks produce six WorkerGroup/Event combinations and 60 globally
unique message IDs. Acceptance requires 60 non-empty success-result payloads,
but does not parse or persist those opaque payloads and does not infer which
Worker executed an Item. Worker identity and restart behavior remain the
Worker Fleet proof's responsibility.

The integration keeps the input files locally. Server sees only ordinary Task
and TaskItem requests and creates only the temporary HTTP export file used for
the response stream.

## Run

Start Redis and one Java Server with the `scenario-workers` Profile. Server
supervises the configured Python Pacer CLI child and Adapter but starts no
Workers. Start the standalone `scenario_workers_jvm` Host, then run:

```powershell
.\gradlew.bat :scenario_workers_jvm:runScenarioWorkers
```

In a separate terminal:

```powershell
.\gradlew.bat :integrations:worker-capability-task:runCapabilityTaskScenario
```

Optional arguments use `--name=value`:

```text
--server-base-url=http://127.0.0.1:18082
--proof-id=worker-capability-demo
--phone-seed-path=phone-seed.txt
--string-seed-path=string-seed.txt
--result-dir=results
--maximum-wait-millis=30000
--request-timeout-millis=120000
```

`proof-id` names one acceptance artifact directory. The client writes only
`capability-task-evidence.json`: Task coordinates, Group/Event relationships,
counts, message IDs, and failure class names. It never writes seed values,
Worker Result content, Worker Properties, or Redis data.

Unit proof:

```powershell
.\gradlew.bat :integrations:worker-capability-task:test
```
