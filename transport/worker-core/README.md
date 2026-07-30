# Worker Core

`transport:worker-core` is the Java 11 Worker mechanism shared by JVM and
Android network clients.

It owns:

- Worker event definitions, parameter resolution, dispatch, and error
  classification.
- The transport-neutral `WorkerCommandExecutor`.
- Polling, WebSocket, and line Socket Worker Transport state machines.
- String-only point, text WebSocket, and line socket client interfaces.

It does not own network implementations, Adapter behavior, host process
lifecycle, Redis access, or Kernel scheduling.

## Execution Contract

`WorkerCommandDispatcher` is the default `WorkerCommandExecutor`. It:

```text
decode one direct WorkerCommand
  -> reject malformed protocol input
  -> drop an already-expired command before handler invocation
  -> resolve one statically supplied (src, messageType) definition
  -> invoke its parameter resolver and handler
  -> return one direct WorkerResult with correlation/forward unchanged
```

The default outcome mapping is:

| Outcome | Meaning |
| --- | --- |
| `200` | Handler returned a non-empty result payload |
| `1400` | Command payload or resolved handler input is invalid |
| `1404` | No definition exists for the `(src, messageType)` pair |
| `1500` | Handler failed or returned an empty result payload |

A malformed encoded command is a protocol failure and does not produce a
`WorkerResult`. A command at or beyond `executeBeforeMillis` is dropped before
the handler starts. Once a handler starts, the deadline is not checked again.
Callers may supply a custom `WorkerCommandExecutor`; doing so replaces only
command execution policy, not Transport protocol state.

## Transport State Machines

The three Worker Transports accept either caller definitions or a custom
executor together with the matching string-only Client interface:

```java
WebSocketWorkerTransport worker =
        new WebSocketWorkerTransport(
                textWebSocketClient,
                workerId,
                eventDefinitions
        );
```

Their stable behavior is:

| Transport | Host operation | Protocol behavior |
| --- | --- | --- |
| `PollingWorkerTransport` | `runOnce()` or `runForever(interval)` | Submits a pending result before polling another command |
| `WebSocketWorkerTransport` | `start()` or `runForever()` | Sends bind first, serializes command execution, and replays a pending result after reconnect |
| `SocketWorkerTransport` | `start()` or `runForever()` | Sends bind first on the line connection and replays a pending result after reconnect |

WebSocket and Socket exchange direct `WorkerConnectionBind`, `WorkerCommand`,
and `WorkerResult` JSON values. There is no generic connection-message
envelope. A long-lived Transport accepts a command only after bind and while
no command is processing and no result is pending.

Each Transport retains at most one pending result. Polling retains it until the
point result submission succeeds. WebSocket and Socket retain it until the
active Client accepts the encoded result; after reconnect they bind first and
then resend it. Client acceptance is not an application ACK, and Worker Core
does not add a pending/ack ledger.

The Transport owns and closes its Client. Polling `close()`, and long-lived
`start()`/`close()`, are idempotent at their lifecycle boundaries. A closed
Transport cannot be restarted. WebSocket rejects binary input and invalid
message sequencing through the connection boundary; Socket surfaces invalid
message sequencing as a coded protocol failure.

## Client Boundary

Core exposes only:

```text
WorkerPointClient
TextWebSocketClient
LineSocketClient
```

These interfaces carry strings plus connection/lifecycle signals. They do not
expose Worker DTOs or concrete networking types. Concrete Clients own network
connection, reconnect, stale-callback isolation where applicable, and resource
teardown. They must not cache Worker business messages; pending result
ownership remains in the corresponding Worker Transport.

The WebSocket Client contract requires serialized listener callbacks, stale
connection isolation, thread-safe non-blocking `send`, idempotent lifecycle,
and no callbacks after `close()` returns.

## Verification

```text
./gradlew :transport:worker-core:test
```
