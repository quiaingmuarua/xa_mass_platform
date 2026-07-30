# XA Mass OkHttp Worker

Status: Java 11 Worker library with OkHttp Polling/WebSocket and line-oriented
Socket transports.

The Worker path is organized into three explicit layers:

```text
Transport Core
  WorkerCommandExecutor / WorkerCommandDispatcher / Event Definitions

Worker transport profile
  PollingWorkerTransport / WebSocketWorkerTransport / SocketWorkerTransport

Network client
  OkHttpWorkerPointClient
  OkHttpTextWebSocketClient
  JdkLineSocketClient
```

The execution layer and network Client interfaces live in
[`transport:core`](../core/README.md). This module contains Worker transport
state machines and their default JVM network clients. It is a library, not a
process, and has no CLI, default business handlers, Android lifecycle, Server,
Kernel, Redis, score, Pacer, or TaskType dependency.

## Command Execution

All transports delegate encoded commands to one transport-neutral execution
seam:

```text
encoded WorkerCommand
-> WorkerCommandExecutor
-> WorkerCommandDispatcher
-> strict WorkerCommand decode
-> check executeBeforeMillis before starting
-> lookup WorkerEventDefinition by src + messageType
-> resolve payload String into handler parameter type
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

Definitions are immutable structures that only bind source, event identity,
resolver, and handler. They do not execute or dispatch messages:

```java
WorkerEventDefinition<ObserveParameters> observe =
        WorkerEventDefinition.of(
                "TASK",
                "sample.observe",
                payload -> {
                    Map<String, Object> values =
                            Jsons.parseObject(payload);
                    return new ObserveParameters(
                            (String) values.get("value")
                    );
                },
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.value()
                ))
        );

WebSocketWorkerTransport worker =
        new WebSocketWorkerTransport(
                serverUrl,
                workerId,
                requestTimeout,
                reconnectInterval,
                List.of(observe)
        );
```

Common String and JSON-object parameters can use
`WorkerEventParameterResolvers.string()` and
`WorkerEventParameterResolvers.jsonMap()`. A caller with custom execution
policy can still construct `WorkerCommandDispatcher` and pass it through the
network-client constructor.

`WorkerEventDefinitionManager` internally indexes definitions by a private
minted `src:eventCode` key. Registration and lookup both receive the two
original strings; it does not receive payloads or execute handlers. The minted
key is not a wire or public contract. The same eventCode may be registered
independently for TASK, SYSTEM, and ADAPTER.

There is no dynamic handler registration, public composite-key type, or
reflection-based DTO mapping.

## Network Boundary

The network clients expose only strings and connection events:

```text
WorkerPointClient
  pollCommand / submitResult

TextWebSocketClient
  open / text / binary / disconnect / failure

LineSocketClient
  open / line / disconnect / failure
```

They own URL construction, HTTP status handling, active calls, sockets,
connection replacement, stale callback suppression, fixed-interval reconnect,
and underlying network resources. They do not know WorkerCommand,
WorkerResult, WorkerConnectionBind, event definitions, or business payloads.
They do not retain offline business messages.

Each Worker transport owns one client and closes it. Public client-injection
constructors accept a `WorkerCommandExecutor` for custom composition and
focused testing. URI-based standard constructors accept Definitions, construct
the dispatcher, and create the default network implementation. No OkHttp or
JDK Socket type appears in the public Worker API.

## Transport Semantics

Polling calls only the point Worker API identified by endpointManagerId and
workerId. It never scans an Adapter mailbox. A failed result submission is
retained by `PollingWorkerTransport` and retried before polling another
command. `OkHttpWorkerPointClient` only performs the individual HTTP calls.

WebSocket and Socket first send:

```json
{"workerId":"worker-1"}
```

They then exchange direct protocol JSON:

```text
Adapter -> Worker : WorkerCommand
Worker  -> Adapter: WorkerResult
```

WebSocket executes commands on a Worker-dedicated serial executor.
`OkHttpTextWebSocketClient` owns OkHttp callbacks and reconnect. Socket command
callbacks remain serial because `JdkLineSocketClient` owns one blocking
`readLine` connection loop. Both transport profiles retain one pending result
across reconnect and send it after the next bind.

Polling, WebSocket, and Socket profiles own Bind, encoded command delegation,
pending-result retention, WorkerResult encoding, and protocol-error handling.
They do not decode command semantics, parse business payloads, select
definitions, map outcomes, or construct WorkerResult. Their clients own
network receipt, sends, reconnect, and resource teardown.

An expired command is silently dropped before execution. Once execution has
started, the deadline is not checked again. Successful transport send is the
network handoff boundary; no application ACK is added.

Pending results are in-memory best-effort state. A process crash can lose them;
Kernel claim/lease expiry and result fences provide convergence.

## Android WebSocket Composition

An Android host uses the Android production composition, which combines this
module's Worker state machine with the Android-specific network client:

```gradle
implementation project(':transport:okhttp-worker')
implementation project(':transport:android-client')
```

```text
AndroidOkHttpTextWebSocketClient
-> WebSocketWorkerTransport
-> WorkerCommandDispatcher
-> static WorkerEventDefinitions
```

`AndroidWebSocketWorker` constructs that chain and exposes `start/close`.
The Android network client still owns only HandlerThread/Looper connection
mechanics. The Worker transport owns Bind, command execution, and pending
result state. The host owns process lifetime and business-handler assembly.

Runtime failures use one `WorkerException` with numeric module-local error
codes. Logs use `System.Logger` and must not include payload or forward
contents.

## Verification

```text
./gradlew :transport:okhttp-worker:test
```
