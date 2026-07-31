# Phone Number RPC Integration

This module proves the external single-TaskItem RPC path:

```text
Server phone-number-rpc profile
  -> configure WebSocket Adapter websocket-1
  -> upsert one WorkerGroup and ten Workers through Kernel owner contracts
  -> inject the built-in libphonenumber Worker capability
  -> connect ten WebSocketWorkerTransport instances

PhoneNumberTaskMain
  -> create and approve one ITEM_DRIVEN Task
  -> read seed.txt
  -> call one TaskItem RPC per number
  -> target Workers in deterministic round-robin order
  -> write one JSON result per line to result.txt
  -> close the Task
```

The integration module owns only Task creation and invocation. Worker resource
assembly, business capability, transport construction, and process lifecycle
belong to the Server's explicitly configured built-in Worker bundle. The Task
caller does not depend on `server_jvm`, `kernel_jvm`, the Netty Adapter, Worker
Core, or a concrete Worker network client.

## Capability

The Server-owned `PHONE_NUMBER` bundle registers:

```text
src       = TASK
eventCode = phonenumber.lookup
```

Input:

```json
{
  "rawNumber": "+41798765432",
  "defaultRegion": "CH"
}
```

`defaultRegion` is optional for the E.164 values in `seed.txt`.

Successful domain output:

```json
{
  "input": "+41798765432",
  "workerId": "phonenumber-worker-001",
  "possible": true,
  "valid": true,
  "e164": "+41798765432",
  "countryCallingCode": 41,
  "regionCode": "CH",
  "country": "Switzerland",
  "numberType": "MOBILE",
  "originalCarrier": "Swisscom"
}
```

Invalid phone numbers are successful domain results with `valid=false` and an
`error`. They do not throw from the Worker handler and therefore do not turn
the RPC into a Worker-failure timeout.

`originalCarrier` is the carrier originally assigned to the number range.
Google libphonenumber does not claim that it is the current carrier after
number portability:

<https://github.com/google/libphonenumber#mapping-phone-numbers-to-original-carriers>

The checked-in `seed.txt` contains 100 distinct valid mobile examples generated
from libphonenumber metadata. They are example data, not real user phone
numbers.

## Run The Full Path

The example assumes Redis on `localhost:6379`, database 15. The supplied
Kernel configuration and Server use the Redis prefix `default`.

### 1. Start the Python Kernel

```powershell
python -m kernel_design.runtime_server --config integrations/phone-number-rpc/kernel-config.json
```

### 2. Start the Server with the built-in Worker bundle

```powershell
.\gradlew.bat :server_jvm:bootRun `
  --args="--spring.profiles.active=phone-number-rpc"
```

The default Server starts no Adapter. This opt-in profile declares only the
WebSocket Adapter required by the example and one `PHONE_NUMBER` bundle. The
bundle derives its local Worker connection URI from that Adapter declaration;
the same port is not configured twice. Before reporting startup success, the
Server:

```text
starts the Adapters
→ upserts phonenumber-workers
→ upserts phonenumber-worker-001 through -010
→ starts ten real WebSocket Workers
→ waits until all ten Workers bind
```

Any rejected owner operation or initial connection timeout fails Server
startup. There is no separate Worker launcher.

### 3. Run the Task scenario

```powershell
.\gradlew.bat :integrations:phone-number-rpc:runTaskScenario
```

The Task caller is intentionally sequential because this example proves the
single-TaskItem synchronous wait path. A `202 pending` response fails the
scenario instead of being treated as a completed result. On success,
`result.txt` contains 100 JSON lines and is ignored by Git.

## Task Options

The Task entrypoint accepts `--name=value` arguments:

```text
--server-base-url=http://127.0.0.1:18082
--worker-group-id=phonenumber-workers
--worker-id-prefix=phonenumber-worker-
--worker-count=10
--seed-path=seed.txt
--result-path=result.txt
--wait-timeout-millis=30000
--task-close-after-millis=3600000
```

For example:

```powershell
.\gradlew.bat :integrations:phone-number-rpc:runTaskScenario `
  --args="--task-id=phone-demo-1 --wait-timeout-millis=10000"
```

## Verification

```powershell
.\gradlew.bat :integrations:phone-number-rpc:test
.\gradlew.bat :server_jvm:test
.\gradlew.bat build
git diff --check
```
