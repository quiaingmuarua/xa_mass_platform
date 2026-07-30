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

All transports delegate encoded commands to one transport-neutral execution
seam:

```text
encoded WorkerCommand
-> WorkerCommandExecutor
-> WorkerCommandDispatcher
-> strict WorkerCommand decode
-> check executeBeforeMillis before starting
-> parse payload as parameter Map
-> lookup WorkerEventDefinition by src + messageType
-> parameter resolver
-> typed WorkerEventHandler
-> opaque String result payload
-> WorkerResult
```

The dispatcher copies:

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

Definitions bind their source, event identity, resolver, and handler
statically:

```java
WorkerEventDefinition<ObserveParameters> observe =
        WorkerEventDefinition.of(
                "TASK",
                "sample.observe",
                payload -> new ObserveParameters(
                        (String) payload.get("value")
                ),
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.value()
                ))
        );

WorkerCommandDispatcher dispatcher = new WorkerCommandDispatcher(
        List.of(observe)
);
```

`WorkerEventDefinitionManager` internally indexes definitions by a private
minted `src:eventCode` key. Registration and lookup both receive the two
original strings; the minted key is not a wire or public contract. The same
eventCode may be registered independently for TASK, SYSTEM, and ADAPTER.

There is no dynamic handler registration, public composite-key type, or
reflection-based DTO mapping.

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

Polling, WebSocket, and Socket own only network receipt, pending-result
retention, result sending, and reconnect behavior. They do not decode command
semantics, parse business payloads, select definitions, map outcomes, or
construct WorkerResult.

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
