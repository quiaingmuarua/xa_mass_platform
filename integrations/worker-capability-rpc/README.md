# Worker Capability RPC Integration

This module proves one external use of the reusable `scenario-workers`
assembly. The profile is not RPC-specific and does not create Tasks:

```text
Server scenario-workers profile
  -> scenario-websocket Adapter
  -> scenario-phone-number-workers / 10 Workers / 3 events
  -> scenario-string-utils-workers / 10 Workers / 3 events

WorkerCapabilityRpcMain
  -> resolve platform Worker IDs through idempotent Register calls
  -> run the Phone WorkerGroup Task and write phone-number.jsonl
  -> close the Phone Task
  -> run the String WorkerGroup Task and write string-utils.jsonl
  -> close the String Task
```

The integration depends only on the shared JSON facade and the external
Runtime API. It recovers each platform-issued Worker ID through the public,
idempotent Register API before constructing explicit Worker allocation rules.
The Server profile owns deployment composition, while
`scenario_workers_jvm` owns business handlers, transport construction, and
public-HTTP Register/Bind plus Adapter-directed Identify Report construction.
Server initializes the advisory WorkerGroup directory before starting Adapters
and Scenario Workers. The caller does not depend on
`server_jvm`, `scenario_workers_jvm`, `kernel_jvm`, the Adapter Runtime, Worker
Core, a network client, or Redis.

## Scenario capabilities

The profile's phone-number Scenario Workers implement:

```text
phonenumber.e164
phonenumber.country
phonenumber.original-carrier
```

All three accept:

```json
{"rawNumber":"+41798765432","defaultRegion":"CH"}
```

Every result contains `input`, `possible`, and `valid`. A valid
result adds the event-specific field:

- `e164`;
- `countryCallingCode`, `regionCode`, and `country`;
- `originalCarrier`.

Invalid input is a successful domain result with `valid=false` and `error`;
it is not converted into Worker failure. `originalCarrier` is the carrier
originally assigned to the number range, not necessarily its current carrier.
The checked-in `phone-seed.txt` contains example data, not user data.

The profile's string-utils Scenario Workers implement:

```text
string.md5
string.sha1
string.base64.encode
```

All three accept `{"value":"hello"}` and return `input`, `valid`,
plus `md5`, `sha1`, or `base64`. Operations use UTF-8; digests are lowercase
hex and Base64 is standard padded encoding. Empty strings are valid. Missing
or non-string values return a successful domain error.

MD5 and SHA-1 exist only to demonstrate capability routing. Do not use them
for passwords or security signatures.

## Run the full path

The checked-in Kernel configuration uses Redis database 15 and prefix
`default`.

Start the Python Kernel:

```powershell
python -m kernel_design.runtime_server `
  --config integrations/worker-capability-rpc/kernel-config.json
```

Start the Runtime API Server with the reusable Worker scenario:

```powershell
.\gradlew.bat :server_jvm:bootRun `
  --args="--spring.profiles.active=scenario-workers"
```

The default Server profile has no Adapter and no built-in Worker. The explicit
profile initializes two advisory WorkerGroup catalog entries and starts one
real WebSocket Adapter. Scenario assembly then registers each client Worker
key, binds the returned platform Worker ID and complete Properties snapshot,
starts and connects 20 real WebSocket Worker transports, and finally attempts
the explicit Property Index updates through the public Runtime API.
There is no separate Worker launcher or in-process delivery shortcut.

Run the external RPC proof:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:runRpcScenario
```

The runner resolves the 20 configured client Worker keys to their stable
platform UUIDs. It then executes the two WorkerGroups independently. Results
are written to:

```text
results/<scenarioId>/phone-number.jsonl
results/<scenarioId>/string-utils.jsonl
```

Each file contains 30 JSON lines for only its own WorkerGroup. Every line
includes `taskId`, `workerGroupId`, `clientWorkerKey`, `workerId`, `eventCode`,
the original input, and the parsed result. A `202 pending`, invalid domain
result, missing event-specific field, or failed call fails that Group. If the
Phone Group completes before the String Group fails, the completed Phone file
remains available. The report records the explicitly targeted Worker ID;
business result payloads do not repeat Worker identity.

Before returning success, the runner verifies both files as one acceptance
proof: 20 canonical and globally unique Worker IDs, stable Worker-key mapping,
and exactly one result for every configured Worker/event combination. CI uses
the same runner and does not duplicate these assertions in workflow code.

Options:

```text
--server-base-url=http://127.0.0.1:18082
--scenario-id=worker-capability-demo
--phone-seed-path=phone-seed.txt
--string-seed-path=string-seed.txt
--result-dir=results
--wait-timeout-millis=30000
--request-timeout-millis=35000
--task-close-after-millis=3600000
```

The scenario is intentionally sequential because it proves the synchronous
single-TaskItem wait path. A scenario result directory must not already exist,
which prevents stale output from being mistaken for a new run. The same
profile can support later non-RPC, TASK_DRIVEN, or PRECOMPUTED integrations
without changing these Worker identities or capabilities.

## Verification

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:test
.\gradlew.bat :server_jvm:test
git diff --check
```

The repository Scenario RPC lane also runs this complete scenario against an
ephemeral Redis service, a real Python Kernel process, and a real Server using
the `scenario-workers` profile. Changes confined to `scenario_workers_jvm/` or
this integration therefore still select the 60-call
cross-process proof.
