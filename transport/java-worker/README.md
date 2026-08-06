# XA Mass Java Worker

`transport:java-worker` is the Java 11 Worker assembly and concrete Java
network implementation module for
[`transport:worker-core`](../worker-core/README.md).

It provides:

```text
JavaWorker
  -> shared TextMessageWorkerRuntime assembly
  -> explicit WEBSOCKET or SOCKET selection

OkHttpWorkerPointClient
  -> target Worker poll/result HTTP

OkHttpTextWebSocketClient
  -> text WebSocket connection and fixed reconnect

OkHttpWorkerControlClient
  -> Worker Register and Endpoint Bind HTTP

JdkLineSocketClient
  -> line-oriented TCP connection and fixed reconnect
```

Core still owns `PollingWorkerTransport` and `TextMessageWorkerTransport`.
This module does not decode commands, construct
Worker results, or introduce a second pending-result state machine.

## Long-lived Assembly

```java
JavaWorker worker = JavaWorker.builder(
                URI.create("http://127.0.0.1:18082"),
                "phone-workers",
                "stable-installation-key",
                WorkerTransportType.WEBSOCKET
        )
        .identityStore(identityStore)
        .workerProperties(() -> Map.of(
                "runtime", "java",
                "region", "local"
        ))
        .eventDefinitions(eventDefinitions)
        .requestTimeout(Duration.ofSeconds(10))
        .reconnectInterval(Duration.ofMillis(250))
        .build();

worker.start();
```

The fixed client key is injected as the reserved
`workerProperties.clientWorkerKey`; the caller Properties provider may not
override it. `WorkerIdentityStore` is explicit. Long-lived hosts provide a
persistent implementation, while finite tests may deliberately use
`WorkerIdentityStore.noCache()` and rely on Register idempotency for the fixed
key.

`WorkerTransportType.WEBSOCKET` selects `OkHttpTextWebSocketClient`, while
`WorkerTransportType.SOCKET` selects `JdkLineSocketClient`. `POLLING` is
rejected because its request-response lifecycle is assembled separately.

`JavaWorker` delegates lifecycle to Core's `TextMessageWorkerRuntime`: a missing
Worker ID is registered and saved, every `start()` performs Endpoint Bind, and
the returned URI is used for that session. Temporary disconnects are handled
by the selected concrete Client against the same session URI and do not repeat
Bind. `stop()` preserves identity; `refreshProperties()` rebinds only a changed
complete snapshot.

## Lower-level Composition

Callers may still compose concrete Clients with Core transports directly for
Polling, line Socket, or custom lifecycle policy. Concrete Clients expose only
Core interfaces and JDK types. They own URL/request handling, sockets, stale
callback suppression, fixed reconnect, and network resources. They do not
cache offline business messages, and successful `send` is not an application
ACK.

```text
./gradlew :transport:java-worker:test
```
