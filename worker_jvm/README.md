# XA Mass JVM Worker

Status: runnable Java 21 reference Worker.

`worker_jvm` runs one logical Worker slot and supports three transport profiles
over the same serial command execution core:

```text
polling
  -> target Worker point poll
  -> execute one command
  -> point result submit

websocket
  -> send WorkerConnectionBind
  -> receive one command frame
  -> execute one command
  -> send one result frame

socket
  -> send WorkerConnectionBind line
  -> read one command line
  -> execute one command
  -> write one result line
```

Both profiles ultimately execute the shared `WorkerCommandEnvelope`, verify
the nested `DeliverSeed.workerId`, and produce the shared `SeedResult`.
Polling exchanges those DTOs directly through point HTTP. WebSocket and Socket
first send:

```json
{"messageType":"WORKER_BIND","workerId":"worker-1"}
```

They then exchange the flat long-connection union:

```text
TASK_ITEM_COMMAND -> TaskItemCommandMessage(WorkerCommandEnvelope)
TASK_ITEM_RESULT  -> TaskItemResultMessage(SeedResult)
```

The long-lived Workers accept only command messages and emit only result
messages after binding. Transport selection is independent of `TaskType`.

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

A WebSocket or Socket Worker must bind to the non-`system-polling` endpoint
manager owned by one configured Adapter instance. The instance map key is the
`endpointManagerId`, and its `listen-host/listen-port` identifies the
network endpoint.

## Run

Polling:

```text
./gradlew :worker_jvm:run --args="--worker-id worker-1"
```

WebSocket:

```text
./gradlew :worker_jvm:run --args="--transport websocket --worker-id worker-1"
```

Socket:

```text
./gradlew :worker_jvm:run --args="--transport socket --worker-id worker-1"
```

The WebSocket `--server-url` points to the selected Adapter's Netty listener;
the default is `http://127.0.0.1:18083`. It uses the fixed
`/api/v1/worker-delivery/websocket` path; WorkerId is established by Bind, not
the URL. The Socket default is `tcp://127.0.0.1:18084`. Polling continues to
point to the Server API at `http://127.0.0.1:18082`.

Common options:

```text
--server-url <Server URL for polling, Adapter URL for WebSocket/Socket>
--request-timeout-millis 5000
```

Polling-only options:

```text
--endpoint-manager-id system-polling
--poll-interval-millis 500
```

Long-lived transport option:

```text
--reconnect-interval-millis 1000
```

The Worker processes one command at a time. Polling retains one failed result
in memory and retries it before polling again. WebSocket requests the next
message only after result send completion. WebSocket and Socket retain a
failed result across reconnect and resend it immediately after the new Bind.
Process failure may still lose an in-memory pending result.

## Built-in Events

The reference Worker installs three business handlers statically. Their
payloads and results belong to the Worker example, not to the Kernel or Worker
Delivery protocol.

Phone inspection:

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

String transform uses one event code for all supported operations:

```json
{
  "eventCode": "utility.string.transform",
  "payload": {
    "operation": "MD5",
    "value": "hello"
  }
}
```

`operation` is one of `BASE64`, `MD5`, or `SHA1`. All operations use UTF-8.
MD5 and SHA1 are compatibility digests, not encryption or secure signatures.

Domain inspection performs a real A/AAAA lookup through the JDK DNS provider
and the host's configured resolver:

```json
{
  "eventCode": "network.domain.inspect",
  "payload": {"domain": "example.com"}
}
```

It returns the normalized ASCII domain, a `resolves` flag, and the current
IPv4/IPv6 addresses. DNS results, latency, and failures are inherently
environment-dependent. NXDOMAIN is a completed result with `resolves=false`;
timeouts and resolver failures become Worker outcome `1500`. No third-party
HTTP API, WHOIS, or target-site request is used.

Business handlers are allowed to return environment-dependent results or fail.
Worker scheduling and transport integration tests use local or temporary
handlers when they need deterministic, observable evidence; they do not use
public DNS output as a platform acceptance condition.

## Boundary

This module depends only on `worker_delivery_contract_jvm` and the phone tool
library; domain lookup uses only JDK naming/DNS APIs. It does not register
resources and cannot access Server internals, Redis, Kernel owners, scores,
Pacers, or TaskType.

```text
./gradlew :worker_jvm:test
```
