# XA Mass Java Worker

`transport:java-worker` is the Java 11 Worker assembly and Java networking
implementation for [`transport:worker-core`](../worker-core/README.md).

```text
JavaWorker
  -> RegisteredWorkerPreparation
  -> WorkerCommandDispatcher
  -> TextMessageWorkerTransportFactory
  -> WorkerRunController

JavaWorkerManager
  -> one fixed WorkerGroup replica set
  -> explicit desired-state reconciliation

JavaWorkerHostResources
  -> shared OkHttp infrastructure
  -> shared WebSocket reconnect scheduler
  -> bounded line-Socket execution pool
  -> bounded Control pool
```

The concrete WebSocket, Control, and line-Socket Clients are internal. Cross-
module callers use `JavaWorker`, or the Core `TextMessageClientFactory`
exposed by Host Resources for low-level composition. `OkHttpWorkerPointClient`
remains the public Polling client.

## Worker Assembly

```java
JavaWorkerHostResources resources =
        JavaWorkerHostResources.create(
                20,
                "xa-java-worker",
                false
        );

JavaWorker worker = JavaWorker.builder(
                URI.create("http://127.0.0.1:18082"),
                "phone-workers",
                "stable-installation-key",
                WorkerTransportType.WEBSOCKET
        )
        .hostResources(resources)
        .identityStore(identityStore)
        .workerProperties(() -> Map.of(
                "runtime", "java",
                "region", "local"
        ))
        .eventDefinitions(eventDefinitions)
        .requestTimeout(Duration.ofSeconds(10))
        .reconnectPolicy(TextMessageReconnectPolicy.defaults())
        .build();

worker.start();
worker.close();
resources.close();
```

The fixed client key is injected as the reserved
`workerProperties.clientWorkerKey`; caller Properties may not override it.
Long-lived Hosts supply a persistent `WorkerIdentityStore`. Finite tests may
use `WorkerIdentityStore.noCache()` and rely on Register idempotency.

`WEBSOCKET` selects the internal OkHttp text Client and `SOCKET` selects the
internal UTF-8 line Client. `POLLING` remains a separate request-response
assembly.

`start()` performs one synchronous Preparation on its caller's thread:

```text
load Properties
-> recover or Register workerId
-> Endpoint Bind
-> install TextMessageWorkerTransport
-> return while the concrete Client connects asynchronously
```

Temporary disconnects reuse the prepared URI. Reconnect exhaustion returns
the Worker to `STOPPED`; only an explicit later `start()` performs another
Bind. The Worker caches no Endpoint URI, Command, or Result.

## Host Resources

One Java process should create one `JavaWorkerHostResources` for its total
Worker capacity and share it across Workers and Managers. Its arguments are:

```text
workerCapacity
threadNamePrefix
daemonThreads
```

`workerCapacity` sizes the blocking line-Socket pool and bounds the input to
the Control pool, whose concurrency is capped at four. WebSocket connection
creation, stable-window checks, and reconnect timers share one scheduler. All
WebSocket and Control Clients borrow one OkHttp Dispatcher and ConnectionPool.

There is no Host Command pool. OkHttp WebSocket callbacks pass through a
per-Client serialization gate and execute the Core Transport and Handler
synchronously. The shared scheduler never runs business Handlers. The line-
Socket implementation continues to invoke the Transport from its blocking
reader thread. One connection is naturally serial; different Worker
connections remain concurrent through their networking implementations.

Closing a Client waits for its current protocol callback, suppresses later
callbacks from superseded physical attempts, and does not close shared Host
resources. The Host closes all Workers and Managers before closing
`JavaWorkerHostResources`.

## Managed Java Host

`JavaWorkerManager` runs a fixed replica set for exactly one WorkerGroup:

```java
JavaWorkerManager manager = JavaWorkerManager.builder(
                runtimeApiBaseUrl,
                "phone-workers",
                WorkerTransportType.WEBSOCKET
        )
        .hostResources(resources)
        .eventDefinitions(eventDefinitions)
        .replica("installation-1", identityStore1, properties1)
        .replica("installation-2", identityStore2, properties2)
        .build();
```

Topology is immutable after build. `start()` and `reconcile()` submit at most
one synchronous Worker start per stopped replica to the shared Control pool.
Endpoint termination does not schedule restart; a later explicit
`reconcile()` or `start()` is required. Managers never close borrowed Host
resources.

## Verification

```text
./gradlew :transport:java-worker:test
```
