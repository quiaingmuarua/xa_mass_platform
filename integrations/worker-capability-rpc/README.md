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
  -> read current example files into List<String>
  -> RpcProcess: parse each line once and combine it with configured events
  -> bounded concurrent WorkerGroup calls
  -> POST /api/v1/worker-groups/{workerGroupId}/items:call
  -> 30 Phone calls and 30 String calls with allocationRule={}
  -> optional validation and JSONL middleware
  -> verify in-memory RPC results independently from persistent Worker identities
```

The Process caller supplies only string lines and a Payload parser. The
integration's HTTP boundary knows the Runtime API and WorkerGroup ID and builds
the standard `TaskItemRequest`; it does not create, approve, close, or receive
the internal Task, register identities, or select Worker IDs. The Server
profile owns the deterministic long-lived Task coordinate.
`scenario_workers_jvm` owns the handlers, persistent Lab Worker files, public
Register/Bind flow, and real WebSocket Worker construction.

## RPC process model

`RpcProcess` is a finite in-memory batch processor. It accepts a `List<String>`,
one `line -> payload` parser, configured event codes, and optional batch result
middleware. It parses every line once, produces the line/event cross product,
and executes Group RPC calls with a caller-bounded concurrency limit. Results
are returned in seed order even when calls finish in another order.

The Process has no file, Task, Worker, retry, checkpoint, or chained-request
contract. The current Main happens to read two files and installs validation
plus atomic JSONL middleware. Another integration scenario may instead pass an
inline or generated string list and consume only the returned results:

```java
List<RpcResult> results = RpcProcess.builder(rpcClient)
        .scenarioId("integration-run")
        .processName("example")
        .workerGroupId("example-workers")
        .lines(List.of("first", "second"))
        .eventCodes(List.of("example.transform"))
        .parseLine(line -> Map.of("value", line))
        .maxWorkers(10)
        .build()
        .start();
```

Adding a finite scenario therefore requires explicit Java assembly of the
Group, event list, input strings, payload parser, and only the middleware that
scenario needs. There is no reflection, `ServiceLoader`, or configuration DSL.

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

The Phone and String Processes run sequentially; calls within each Process are
bounded and concurrent. Each request carries a normal Item with
`allocationRule: {}`; the Kernel selects an available Worker in that Task's
WorkerGroup. No result claims which Worker executed it.

Results are written to:

```text
results/<scenarioId>/phone-number.jsonl
results/<scenarioId>/string-utils.jsonl
```

Each file contains exactly 30 JSON lines for its own Group. A row contains only
`workerGroupId`, `messageId`, `eventCode`, `input`, and `result`. A `202
pending`, invalid domain result, missing event-specific field, or failed call
fails that Group. Completed partial output is preserved for diagnosis.

JSONL is diagnostic middleware for this checked-in run, not an output contract
of `RpcProcess`. All calls must complete before that Process runs validation or
publishes its file.

Before success, the runner proves two independent facts:

- the returned in-memory batches contain all 60 configured Group/event calls;
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
