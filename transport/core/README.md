# Transport Core

`transport:core` is the Java 11 Worker mechanism shared by JVM and Android
network clients.

It owns:

- Worker event definitions, parameter resolution, dispatch, and error
  classification.
- The transport-neutral `WorkerCommandExecutor`.
- Polling, WebSocket, and line Socket Worker Transport state machines.
- String-only point, text WebSocket, and line socket client interfaces.

It does not own network implementations, Adapter behavior, host process
lifecycle, Redis access, or Kernel scheduling.

The three Worker Transports accept either caller definitions or a custom
executor together with the matching Client interface:

```java
WebSocketWorkerTransport worker =
        new WebSocketWorkerTransport(
                textWebSocketClient,
                workerId,
                eventDefinitions
        );
```

The Transport owns and closes its Client. It owns Worker Delivery protocol
state such as Bind, serialized command execution, pending result retention,
and reconnect-time result replay. The Client owns only network connection,
string receipt/send, reconnect, and resource teardown.

The WebSocket client contract requires serialized listener callbacks, stale
connection isolation, thread-safe non-blocking `send`, idempotent lifecycle,
and no Worker business-message cache.
