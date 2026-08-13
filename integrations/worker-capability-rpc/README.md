# Worker Capability RPC Integration

This module proves the WorkerGroup-scoped RPC surface supplied by the local
`scenario-workers` Lab profile:

```text
Server scenario-workers profile
  -> three configured WorkerGroup catalog entries
  -> one persistent ITEM_DRIVEN Task per configured Group
  -> scenario-websocket Adapter
  -> data/scenario-workers persistent Lab
     -> scenario-phone-number-workers / 10 Workers / 3 events
     -> scenario-string-utils-workers / 10 Workers / 3 events

WorkerCapabilityRpcMain
  -> POST /api/v1/worker-groups/{workerGroupId}/items:call
  -> 30 Phone calls and 30 String calls with allocationRule={}
  -> verify RPC evidence independently from persistent Worker identities
```

The caller knows only the Runtime API, WorkerGroup ID, and standard
`TaskItemRequest`. It does not create, approve, close, or receive the internal
Task; it does not register identities or select Worker IDs. The Server profile
owns the deterministic long-lived Task coordinate. `scenario_workers_jvm` owns
the handlers, persistent Lab Worker files, public Register/Bind flow, and real
WebSocket Worker construction.

## Scenario capabilities

The phone-number Group implements:

```text
phonenumber.e164
phonenumber.country
phonenumber.original-carrier
```

All three accept:

```json
{"rawNumber":"+41798765432","defaultRegion":"CH"}
```

Every result contains `input`, `possible`, and `valid`. A valid result adds the
event-specific field. Invalid input is a successful domain result with
`valid=false` and `error`. `originalCarrier` is the carrier originally assigned
to the number range, not necessarily its current carrier. The checked-in
`phone-seed.txt` contains example data, not user data.

The string-utils Group implements:

```text
string.md5
string.sha1
string.base64.encode
```

All three accept `{"value":"hello"}` and return `input`, `valid`, plus the
event-specific `md5`, `sha1`, or `base64` value. Operations use UTF-8; digests
are lowercase hex and Base64 is standard padded encoding. Empty strings are
valid. Missing or non-string values return a successful domain error.

MD5 and SHA-1 exist only to demonstrate capability routing. Do not use them for
passwords or security signatures.

## Run the full path

The checked-in Kernel configuration uses Redis database 15 and prefix
`default`.

Start the Python Kernel:

```powershell
python -m kernel_design.runtime_server `
  --config integrations/worker-capability-rpc/kernel-config.json
```

Start the Runtime API Server with the Lab profile:

```powershell
.\gradlew.bat :server_jvm:bootRun `
  --args="--spring.profiles.active=scenario-workers"
```

Startup initializes the three configured WorkerGroup catalog entries, creates
or validates and approves their persistent Tasks, starts the Adapter, and then
starts the two JVM Scenario Groups. The Android Group receives a Task but its
Worker remains externally hosted. Missing JVM Group directories receive their
checked-in ten-Worker defaults; existing directories are never supplemented or
repaired.

Run the acceptance proof:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:runRpcScenario
```

The two Group batches are internally concurrent. Each request carries a normal
Item with `allocationRule: {}`; the Kernel selects an available Worker in that
Task's WorkerGroup. No result claims which Worker executed it.

Results are written to:

```text
results/<scenarioId>/phone-number.jsonl
results/<scenarioId>/string-utils.jsonl
```

Each file contains exactly 30 JSON lines for its own Group. A row contains only
`workerGroupId`, `messageId`, `eventCode`, `input`, and `result`. A `202
pending`, invalid domain result, missing event-specific field, or failed call
fails that Group. Completed partial output is preserved for diagnosis.

Before success, the runner proves two independent facts:

- the two JSONL files contain all 60 distinct configured Group/event calls;
- the two Lab directories contain 20 canonical, persistent, globally unique
  Worker IDs.

It intentionally does not correlate RPC rows with Worker files.

Options:

```text
--server-base-url=http://127.0.0.1:18082
--scenario-id=worker-capability-demo
--phone-seed-path=phone-seed.txt
--string-seed-path=string-seed.txt
--result-dir=results
--scenario-worker-lab-root=../../data/scenario-workers
--wait-timeout-millis=30000
--request-timeout-millis=35000
```

A scenario result directory must not already exist, preventing stale output
from being mistaken for a new run.

## Verification

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:test
.\gradlew.bat :server_jvm:test
git diff --check
```

The repository Scenario RPC lane runs the same command against real Redis, a
real Python Kernel, the Java Server, Adapter, and Scenario Workers. CI relies on
the runner's self-verification rather than duplicating its assertions in shell
code.
