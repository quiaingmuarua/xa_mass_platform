# XA Mass Java Worker

`transport:java-worker` is the Java 11 Worker assembly and concrete Java
network implementation module for
[`transport:worker-core`](../worker-core/README.md).

It provides:

```text
JavaWorker
  -> RegisteredWorkerPreparation + WorkerLoop
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

Core still owns `PollingWorkerTransport`, the two-state Worker run guard, and
the one-endpoint text-message runtime.
This module does not decode commands, construct
Worker results, or introduce a Worker business-message cache.

## Worker Assembly

```java
JavaWorker worker = JavaWorker.builder(
                URI.create("http://127.0.0.1:18082"),
                "phone-workers",
                "stable-installation-key",
                WorkerTransportType.WEBSOCKET
        )
        .executionResources(executionResources)
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

`executionResources` is required and contains Host-owned shared control,
Handler, and retry executors. A process should normally create one bundle for
its Worker owner and reuse it across Workers. `JavaWorker.close()` never shuts
the bundle down: the Host closes all Workers first, then shuts down the three
underlying executors. There is no per-Worker fallback pool or hidden static
executor.

The fixed client key is injected as the reserved
`workerProperties.clientWorkerKey`; the caller Properties provider may not
override it. `WorkerIdentityStore` is explicit. Long-lived hosts provide a
persistent implementation, while finite tests may deliberately use
`WorkerIdentityStore.noCache()` and rely on Register idempotency for the fixed
key.

`WorkerTransportType.WEBSOCKET` selects `OkHttpTextWebSocketClient`, while
`WorkerTransportType.SOCKET` selects `JdkLineSocketClient`. `POLLING` is
rejected because its request-response lifecycle is assembled separately.

`JavaWorker` implements Core's `WorkerLifecycle`. Its builder composes
`RegisteredWorkerPreparation` with one `WorkerLoop`: an accepted `start()`
enters `RUNNING`, a missing Worker ID is registered and saved, Endpoint Bind is
performed, and the returned URI starts one reconnecting Client. Temporary
disconnects reuse the same URI and do not repeat HTTP Bind. Exhausting the
Client reconnect budget ends the run in `STOPPED`; only a later explicit
`start()` reloads Properties and performs bounded preparation again. `stop()`
preserves identity and discards any result produced while its in-flight Handler
finishes. `JavaWorker` exposes no connection-state query: reconnect remains a
private Client mechanism, while its public snapshot reports only the Worker run
state and prepared identity/Endpoint metadata.

## Lower-level Composition

Callers may still compose concrete Clients with `WorkerLoop` or
`PollingWorkerTransport` for custom lifecycle policy. Concrete Clients expose
only Core interfaces and JDK types. They own URL/request handling, sockets,
stale callback suppression, stable-window accounting, bounded fixed reconnect,
and their existing connection execution lanes. They do not
cache offline business messages, and successful `send` is not an application
ACK. They expose open/message/endpoint-terminal callbacks to Runtime, but no
reconnect event or physical connection query.

```text
./gradlew :transport:java-worker:test
```
