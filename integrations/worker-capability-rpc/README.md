# Worker Capability RPC Integration

This module proves one external use of the reusable `scenario-workers`
assembly. The profile is not RPC-specific and does not create Tasks:

```text
Server scenario-workers profile
  -> scenario-websocket Adapter
  -> scenario-phone-number-workers / 10 Workers / 3 events
  -> scenario-string-utils-workers / 10 Workers / 3 events

WorkerCapabilityRpcMain
  -> create and approve two ITEM_DRIVEN Tasks
  -> target every Worker once for every event
  -> execute 60 single-TaskItem RPC calls
  -> write one JSON result per line
  -> close both Tasks
```

The integration depends only on the shared JSON facade and the external
Runtime API. The Server profile owns deployment composition, while
`scenario_workers_jvm` owns business handlers, transport construction, and
public-HTTP Worker registration. Server initializes the advisory WorkerGroup
directory before starting Adapters and Scenario Workers. The caller does not depend on
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
profile initializes two WorkerGroup catalog entries, starts one real WebSocket
Adapter, starts and connects 20 real WebSocket Worker transports, then registers
those Workers and refreshes their Properties through the public Runtime API.
There is no separate Worker launcher or in-process delivery shortcut.

Run the external RPC proof:

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:runRpcScenario
```

The runner creates `<scenarioId>-phone` and `<scenarioId>-string`, performs
each of the six events against all 10 Workers in its group, and writes exactly
60 JSON lines to `result.txt`. Every line includes `taskId`,
`workerGroupId`, `workerId`, `eventCode`, the original input, and the parsed
result. A `202 pending`, invalid domain result, missing event-specific field,
or failed call fails the scenario. The outer report
records the explicitly targeted Worker ID; business result payloads do not
repeat Worker identity.

Options:

```text
--server-base-url=http://127.0.0.1:18082
--scenario-id=worker-capability-demo
--phone-seed-path=phone-seed.txt
--string-seed-path=string-seed.txt
--result-path=result.txt
--wait-timeout-millis=30000
--request-timeout-millis=35000
--task-close-after-millis=3600000
```

The scenario is intentionally sequential because it proves the synchronous
single-TaskItem wait path. The same profile can support later non-RPC,
TASK_DRIVEN, or PRECOMPUTED integrations without changing these Worker
identities or capabilities.

## Verification

```powershell
.\gradlew.bat :integrations:worker-capability-rpc:test
.\gradlew.bat :server_jvm:test
.\gradlew.bat build
git diff --check
```
