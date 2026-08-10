# XA Mass Java Worker

`transport:java-worker` is the Java 11 Worker assembly and Java networking
implementation for [`transport:worker-core`](../worker-core/README.md).

It provides:

```text
JavaWorker
  -> RegisteredWorkerPreparation
  -> WorkerCommandDispatcher
  -> WorkerRunController

JavaWorkerManager
  -> one fixed WorkerGroup replica set
  -> explicit desired-state reconciliation

JavaWorkerHostResources
  -> shared OkHttp infrastructure
  -> shared WebSocket reconnect scheduler
  -> bounded line-Socket execution pool
  -> bounded Control pool
  -> fixed zero-buffer Command pool
```

The concrete WebSocket, Control, and line-Socket Clients are internal platform
implementations. Cross-module callers use `JavaWorker`, or the Core-level
`TextMessageClientFactory` exposed by Host Resources for low-level composition.
`OkHttpWorkerPointClient` remains the public Polling client.

## Worker Assembly

```java
JavaWorkerHostResources resources =
        JavaWorkerHostResources.create(
                20,
                8,
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
explicitly use `WorkerIdentityStore.noCache()` and rely on Register
idempotency for the fixed key.

`WEBSOCKET` selects the internal OkHttp text Client and `SOCKET` selects the
internal UTF-8 line Client. `POLLING` is rejected because Polling remains a
separate request-response assembly.

`start()` synchronously performs one Preparation on its caller's thread:

```text
load Properties
-> recover or Register workerId
-> Endpoint Bind
-> install TextMessageWorkerTransport
-> return while the concrete Client connects asynchronously
```

Temporary disconnects reuse the prepared URI. Reconnect exhaustion returns the
Worker to `STOPPED`; only an explicit later `start()` performs another Bind.
Register or Bind failure also ends that single attempt. The Worker does not
cache Endpoint URIs, Commands, or Results.

## Host Resources

One Java process should create one `JavaWorkerHostResources` for its total
Worker capacity and share it across Workers and Group Managers. The arguments
are:

```text
workerCapacity
maxConcurrentCommands
threadNamePrefix
daemonThreads
```

`workerCapacity` sizes the blocking line-Socket pool and bounds the input to
the Control pool, whose concurrency is capped at four. WebSocket connection
state and reconnect timers share one scheduler. All WebSocket and Control
clients borrow one OkHttp Dispatcher and ConnectionPool.

The Command pool has exactly `maxConcurrentCommands` threads and a
`SynchronousQueue`. When all slots are occupied, a new Command is rejected
immediately and its Transport returns a correlated `1500`; it is never queued.
With concurrency above one, shared Definitions and Handlers must be
thread-safe.

Closing a Worker closes only its current Client and Preparation. It does not
close shared Host resources or interrupt other Workers. The Host must close
all Workers and Managers before closing `JavaWorkerHostResources`.

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

manager.start();
manager.reconcile();
manager.stop();
manager.close();
```

The Builder requires a non-empty replica set and unique client keys. Topology
is immutable after build. `start()` and `reconcile()` submit at most one
synchronous Worker start per stopped replica to the shared Control pool.
Endpoint termination does not schedule restart; a later explicit
`reconcile()` or `start()` is required. Managers never close the borrowed Host
resources.

## Verification

```text
./gradlew :transport:java-worker:test
```
