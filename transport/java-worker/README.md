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
  -> text WebSocket connection and bounded fixed reconnect

OkHttpWorkerControlClient
  -> Worker Register and Endpoint Bind HTTP

JdkLineSocketClient
  -> line-oriented TCP connection and bounded fixed reconnect
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
        .retryPolicy(WorkerRetryPolicy.defaults())
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

`JavaWorker` implements Core's `WorkerLifecycle` and delegates its mechanism to
`TextMessageWorkerRuntime`: a missing Worker ID is registered and saved, every
`start()` performs Endpoint Bind, and the returned URI is used for that
run. Temporary disconnects are handled by the selected concrete Client against
the same URI and do not repeat Bind. When its bounded reconnect budget is
exhausted, Core reloads Properties and performs a new bounded prepare round.
`stop()` preserves identity; `refreshProperties()` rebinds only a changed
complete snapshot and rejects an unexpected Endpoint URI change rather than
continuing on a stale URI.

## Lower-level Composition

Callers may still compose concrete Clients with Core transports directly for
Polling, line Socket, or custom lifecycle policy. Concrete Clients expose only
Core interfaces and JDK types. They own URL/request handling, sockets, stale
callback suppression, stable-window accounting, bounded fixed reconnect, and
network resources. They do not
cache offline business messages, and successful `send` is not an application
ACK.

```text
./gradlew :transport:java-worker:test
```
