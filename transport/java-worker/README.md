# XA Mass Java Worker

`transport:java-worker` is the Java 11 Worker assembly and Java networking
implementation for [`transport:worker-core`](../worker-core/README.md).

```text
JavaWorker
  -> owns one package-private JavaWorkerPlatform
  -> WorkerControlPreparation
  -> WorkerCommandDispatcher
  -> TextMessageWorkerTransportFactory
  -> WorkerRunController

JavaWorkerManager
  -> one fixed WorkerGroup replica set
  -> owns one package-private JavaWorkerPlatform shared by its replicas
  -> explicit desired-state reconciliation

JavaWorkerPlatform (package-private)
  -> shared OkHttp infrastructure
  -> shared WebSocket reconnect scheduler
  -> bounded line-Socket execution pool
  -> bounded Control pool
```

The concrete Platform, WebSocket, Control, and line-Socket Clients are
internal. Cross-module callers use `JavaWorker` or `JavaWorkerManager`.
`OkHttpWorkerPointClient` remains the public Polling client.

## Worker Assembly

```java
JavaWorker worker = JavaWorker.create(
        URI.create("http://127.0.0.1:18082"),
        "phone-workers",
        "stable-installation-key",
        WorkerTransportType.WEBSOCKET,
        () -> Map.of(
                "runtime", "java",
                "region", "local"
        ),
        definitionExtensions,
        WorkerConnectionOptions.defaults()
);

worker.start();
worker.close();
```

The fixed client key is injected as the reserved
`workerProperties.clientWorkerKey`; caller Properties may not override it.
Java Worker does not store Worker ID. Each explicit start sends the Group,
fixed client key and complete Properties to Server Prepare; the Server-owned
identity registry returns the same Worker ID while its Redis state remains.

`WEBSOCKET` selects the internal OkHttp text Client and `SOCKET` selects the
internal UTF-8 line Client. `POLLING` remains a separate request-response
assembly.

The Definition collection is an extension set, not the Worker's complete
Handler map. Assembly delegates to Core's static Definition assembly, which adds
`platform.worker.probe`, `platform.worker.properties.snapshot` and
`platform.worker.events.snapshot` before the defensive copy of business
extensions and rejects duplicate full Event Names. Host code registers only
short capability names through `WorkerEventDefinition.extension(...)`; the
internal map uses their `extension.worker.*` Event Names. Properties remain
live, while the sorted Event snapshot is fixed for the Worker process lifetime.
Neither exposes the assembly-owned `clientWorkerKey`. Connection close is
handled by Transport rather than registered as a Definition. The common
overload omits both extensions and options; another overload accepts extensions
with default connection options.
`create()` assembles local resources but performs no Prepare or
connection I/O until `start()`.

`start()` submits one Preparation to the Worker's internal Control executor
and returns immediately:

```text
load Properties
-> one Worker Prepare request
-> receive workerId and Endpoint
-> install TextMessageWorkerTransport
-> return while the concrete Client connects asynchronously
```

Temporary disconnects reuse the prepared URI. Reconnect exhaustion returns
the Worker to `STOPPED`; only an explicit later `start()` performs another
Prepare. That Prepare carries the only canonical Properties refresh; a running
provider change is observable through an explicit Worker snapshot Command but
waits for the next stop/start before it reaches Kernel resource truth. The
Worker caches no Endpoint URI, Command, or Result.

## Platform Resources

A standalone `JavaWorker` owns one package-private Platform containing its
Control executor, OkHttp infrastructure, WebSocket scheduler, and line-Socket
execution capacity. Its threads are non-daemon so a standalone Worker cannot
silently disappear when `main` returns. `close()` closes the Controller first
and then all Platform resources.

There is no Host Command pool. OkHttp WebSocket callbacks pass through a
per-Client serialization gate and execute the Core Transport and Handler
synchronously. The shared scheduler never runs business Handlers. The line-
Socket implementation continues to invoke the Transport from its blocking
reader thread. One connection is naturally serial; different Worker
connections remain concurrent through their networking implementations.

Closing a Client waits for its current protocol callback and suppresses later
callbacks from superseded physical attempts. Platform shutdown remains the
owning Worker or Manager's responsibility.

## Managed Java Host

`JavaWorkerManager` runs a fixed replica set for exactly one WorkerGroup:

```java
JavaWorkerManager manager = JavaWorkerManager.builder(
                runtimeApiBaseUrl,
                "phone-workers",
                WorkerTransportType.WEBSOCKET
        )
        .extendEventDefinitions(phoneDefinitionExtensions)
        .extendEventDefinitions(commonDefinitionExtensions)
        .options(WorkerConnectionOptions.defaults())
        .replica("installation-1", properties1)
        .replica("installation-2", properties2)
        .build();
```

Topology is immutable after build. Repeated `extendEventDefinitions` calls
append in order, and zero business
extensions are valid. The effective registry is still finalized and checked
by Core when replicas are assembled. A Manager creates one daemon Platform;
replicas in that Manager share its Control pool, OkHttp infrastructure,
WebSocket scheduler, and Socket pool. Different Managers do not share
resources. `start()` and `reconcile()` request one start per stopped replica.
Endpoint termination does not schedule restart; a later explicit
`reconcile()` or `start()` is required. `close()` closes replicas in reverse
order and then closes the Manager Platform.

## Verification

```text
./gradlew :transport:java-worker:test
```
