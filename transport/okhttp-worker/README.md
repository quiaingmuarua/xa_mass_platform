# XA Mass OkHttp Worker

Status: Java 11 Worker library with OkHttp Polling/WebSocket and line-oriented
Socket transports.

`:transport:okhttp-worker` provides one serial Worker execution model:

```text
PollingWorkerTransport
  -> target Worker point poll/result HTTP

WebSocketWorkerTransport
  -> bind, receive direct WorkerCommand, send direct WorkerResult

SocketWorkerTransport
  -> bind line, read direct WorkerCommand lines, write WorkerResult lines
```

The module is a library, not a process. It has no CLI, default business
handlers, Android lifecycle, Server, Kernel, Redis, score, Pacer, or TaskType
dependency.

## Command Execution

All transports delegate to `WorkerCommandProcessor`:

```text
WorkerCommand
-> check executeBeforeMillis before starting
-> use messageType as eventCode
-> parse payload as parameter Map
-> WorkerEventDefinition parameter resolver
-> typed WorkerEventHandler
-> opaque String result payload
-> WorkerResult
```

The processor copies:

```text
command.messageId  -> result.messageId
command.src        -> result.dst
command.messageType -> result.messageType
command.forward    -> result.forward
```

`messageId` is correlation only. `forward` remains opaque to the Worker.
The Worker does not receive workerId inside the command; workerId is already
the polling path or bound connection route.

Error mapping remains:

```text
invalid payload/resolver input -> 1400
unknown event                  -> 1404
other handler/encoding failure -> 1500
success                        -> 200
```

A Handler returns an already serialized, non-empty String. `"null"` represents
no business value. The framework does not require JSON and does not decode the
result payload again.

Definitions bind resolver and handler statically:

```java
WorkerEventDefinition<ObserveParameters> observe =
        WorkerEventDefinition.of(
                payload -> new ObserveParameters(
                        (String) payload.get("value")
                ),
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.value()
                ))
        );

WorkerCommandProcessor processor = new WorkerCommandProcessor(
        Map.of("sample.observe", observe)
);
```

There is no dynamic handler registration or reflection-based DTO mapping.

## Transport Semantics

Polling calls only the point Worker API identified by endpointManagerId and
workerId. It never scans an Adapter mailbox. A failed result submission is
retained in memory and retried before polling another command.

WebSocket and Socket first send:

```json
{"workerId":"worker-1"}
```

They then exchange direct protocol JSON:

```text
Adapter -> Worker : WorkerCommand
Worker  -> Adapter: WorkerResult
```

WebSocket uses OkHttp callbacks but executes commands on a Worker-dedicated
serial executor. Socket uses a blocking `readLine` loop. Both retain one
pending result across reconnect and send it after the next bind.

An expired command is silently dropped before execution. Once execution has
started, the deadline is not checked again. Successful transport send is the
network handoff boundary; no application ACK is added.

Pending results are in-memory best-effort state. A process crash can lose them;
Kernel claim/lease expiry and result fences provide convergence.

## Android Consumption

A future Android application may consume the module directly:

```gradle
implementation project(':transport:okhttp-worker')
```

The library targets Java 11 bytecode and exposes no OkHttp type in its public
API. A real Android host owns permissions, components, process lifetime, and
toolchain compatibility proof.

Runtime failures use one `WorkerException` with numeric module-local error
codes. Logs use `System.Logger` and must not include payload or forward
contents.

## Verification

```text
./gradlew :transport:okhttp-worker:test
```
