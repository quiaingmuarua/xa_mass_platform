# XA Mass JVM Worker

Status: runnable Java 21 reference Worker.

`worker_jvm` runs one logical Worker slot and supports two transport profiles
over the same serial command execution core:

```text
polling
  -> target Worker point poll
  -> execute one command
  -> point result submit

websocket
  -> receive one command frame
  -> execute one command
  -> send one result frame
```

Both profiles decode the shared `WorkerCommandEnvelope`, verify the nested
`DeliverSeed.workerId`, execute `telecom.phone.inspect`, and return the shared
`SeedResult`. Transport selection is independent of `TaskType`.

## Prerequisites

Start the Python Kernel Runtime Server and Java Runtime API Server:

```text
python -m kernel_design.runtime_server
./gradlew :server_jvm:bootRun
```

Upsert the Worker before starting it. A polling Worker must bind to:

```text
endpointManagerId = system-polling
```

A WebSocket Worker must bind to the non-`system-polling` endpoint manager owned
by the local Adapter Runtime hosted by the Server. Enable
`xa.mass.worker-delivery.adapter.enabled`, select `type: WEBSOCKET`, and set the
same `runtime.endpoint-manager-id` before starting the Worker.

## Run

Polling:

```text
./gradlew :worker_jvm:run --args="--worker-id worker-1"
```

WebSocket:

```text
./gradlew :worker_jvm:run --args="--transport websocket --worker-id worker-1"
```

The WebSocket `--server-url` points to the Server hosting the Adapter endpoint;
the repository default is `http://127.0.0.1:18082`.

Common options:

```text
--server-url http://127.0.0.1:18082
--request-timeout-millis 5000
```

Polling-only options:

```text
--endpoint-manager-id system-polling
--poll-interval-millis 500
```

WebSocket-only option:

```text
--reconnect-interval-millis 1000
```

The Worker processes one command at a time. Polling retains one failed result
in memory and retries it before polling again. WebSocket requests the next
message only after result send completion and retains a failed send across
reconnect. Process failure may still lose an in-memory pending result.

## Phone Tool

Input:

```json
{
  "eventCode": "telecom.phone.inspect",
  "payload": {"phoneNumber": "+14155552671"}
}
```

Successful result payload:

```json
{
  "countryCallingCode": 1,
  "e164": "+14155552671",
  "isPossible": true,
  "isValid": true,
  "regionCode": "US"
}
```

Worker outcomes are `200`, `1400`, `1404`, or `1500`. Invalid phone numbers
are completed tool results, not Worker failures.

## Boundary

This module depends only on `worker_delivery_contract_jvm` and the phone tool
library. It does not register resources and cannot access Server internals,
Redis, Kernel owners, scores, Pacers, or TaskType.

```text
./gradlew :worker_jvm:test
```
