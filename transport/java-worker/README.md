# XA Mass Java Worker

`transport:java-worker` is the Java 11 Worker assembly and concrete Java
network implementation module for
[`transport:worker-core`](../worker-core/README.md).

It provides:

```text
JavaWorker
  -> RegisteredWorkerPreparation + WorkerRunController
  -> explicit WEBSOCKET or SOCKET selection

JavaWorkerManager
  -> one fixed WorkerGroup replica set
  -> explicit group desired-state reconciliation

JavaWorkerHostResources
  -> process-scoped shared control/Handler resource bundle

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
        .reconnectPolicy(TextMessageReconnectPolicy.defaults())
        .build();

worker.start();
```

For standalone construction, `executionResources` is required and contains
Host-owned shared control and Handler executors. `JavaWorker.close()`
never shuts the bundle down: the Host closes all Workers first, then shuts down
the two underlying executors. There is no per-Worker fallback pool or hidden
static executor.

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
`RegisteredWorkerPreparation` with one `WorkerRunController`: an accepted `start()`
enters `RUNNING`, a missing Worker ID is registered and saved, Endpoint Bind is
performed, and the returned URI starts one reconnecting Client. Temporary
disconnects reuse the same URI and do not repeat HTTP Bind. Exhausting the
Client reconnect budget ends the run in `STOPPED`; only a later explicit
`start()` reloads Properties and performs one Preparation again. A failed
Register or Bind also ends that attempt without a Core retry. `stop()`
preserves identity and discards any result produced while its in-flight Handler
finishes. `JavaWorker` exposes no connection-state query: reconnect remains a
private Client mechanism, while its public snapshot reports only the Worker run
state and prepared identity/Endpoint metadata.

## Managed Java Host

`JavaWorkerManager` runs a fixed replica set for exactly one WorkerGroup.
Capacity, transport, request timeout, reconnect policy, and Definition/Handler
instances are group-wide; Identity, Properties, and `clientWorkerKey` remain
replica-specific:

```java
JavaWorkerHostResources hostResources =
        JavaWorkerHostResources.create(
        totalReplicaCount,
        "xa-java-worker",
        false
);

JavaWorkerManager manager = JavaWorkerManager.builder(
                runtimeApiBaseUrl,
                "phone-workers",
                WorkerTransportType.WEBSOCKET
        )
        .executionResources(hostResources.executionResources())
        .eventDefinitions(eventDefinitions)
        .replica("installation-1", identityStore1, properties1)
        .replica("installation-2", identityStore2, properties2)
        .build();

manager.start();
manager.reconcile();
manager.stop();
manager.close();
hostResources.close();
```

The Builder requires at least one replica and rejects duplicate
`clientWorkerKey` values. Building freezes the topology; there is no runtime
register, unregister, keyed start/stop, or dynamic scaling API. Adding or
removing capacity requires rebuilding from configuration and restarting the
Host process.

One Java process creates one `JavaWorkerHostResources` using its total replica
count and shares the returned bundle across all of its Group Managers. Control
concurrency is bounded at four, and Handler concurrency is bounded by total
replica count and available processors. A
Manager closes its Workers in reverse replica order but never closes the
borrowed resources; the process closes all Managers first and the Host
resources last.

`start` and `stop` update one private group-level desired state and reconcile
every replica immediately. `reconcile()` later compares that intent with each
Worker's actual `STOPPED/RUNNING` snapshot. A terminal replica remains
`STOPPED` until the Host explicitly invokes `reconcile()` or `start()` again.
The Manager has no timer, Worker listener, connection query, aggregate Worker
state, or per-replica lifecycle controls. `snapshot(key)` and
`snapshots()` expose only the underlying Worker lifecycle snapshots.

## Lower-level Composition

Callers may still compose concrete Clients with `WorkerRunController` or
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
