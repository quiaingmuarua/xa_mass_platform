# XA Mass Java Worker

`transport:java-worker` is the Java 21 Worker assembly and Java networking
implementation for [`transport:worker-core`](../worker-core/README.md).

Host extensions can use the shared Core
[retained outcome Reporter](../worker-core/README.md#later-task-outcome-observations)
for later Task observations. It reuses this assembly's existing connection and
run lifetime; no additional Java Worker runtime or executor is installed.

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
  -> virtual-thread OkHttp WebSocket Dispatcher
  -> virtual-thread OkHttp TaskRunner backend
  -> shared WebSocket reconnect scheduler
  -> bounded line-Socket execution pool
  -> bounded Control pool
```

The concrete Platform, WebSocket, Control, and line-Socket Clients are
internal. Cross-module callers use `JavaWorker` or `JavaWorkerManager`.
`OkHttpWorkerPointClient` remains the public Polling client.

## Replica Definitions

`JavaWorkerManager.Builder.replica(key, properties, extensions)` adds an
immutable construction-time extension set for that replica. The existing
two-argument overload supplies no replica extensions. Assembly combines the
fixed default management Definitions, Manager-common extensions and replica
extensions in that order and rejects duplicate full Event Names. A per-replica
Handler may capture its Host-local coordinate; shared platform resources,
Prepare, reconnect, Properties reporting and lifecycle remain unchanged. This
is not runtime Handler registration and cannot override management events.

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
The request shape is unchanged, but Server consumes only identity coordinates
and never stores that Prepare Map as Matching facts.

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
Prepare. Initial and runtime Properties reach Matching through Adapter
observations and Server admission; Prepare never refreshes those facts. The
Worker caches no Endpoint URI, Command, or Result. A lost first publication can
leave no Matching facts until a later full report or connection baseline;
there is no automatic upstream repair. Polling has no such Properties path and
new Polling Workers use default Rule without requiring facts.

## Proactive Properties

The existing Host Provider supplies one consistent flat `Map<String, String>`
snapshot. Keys must be non-blank; values must be strings (including empty
strings). Producers explicitly encode numeric/boolean facts as strings; Transport
does not flatten or coerce them.

`reportProperties()` reads that Provider once and sends a full
`platform.worker.properties.replaced` Map. `reportProperties(updates)` sends
`platform.worker.properties.updated` without mutating the Host:
update the Host's data first, then call it. The SDK retains no extra Map or
history. Both return only Client acceptance; inactive, disconnected, stopped or
closed sends return false, Provider failure returns false, and invalid update
arguments throw. Encoding is bounded to a 1,000,000-byte Report. No Prepare,
retry, ACK, watcher or second Provider is introduced.

Adapter requests one full snapshot after each verified connection/reconnection;
Core unwraps the successful snapshot output into one `properties.replaced`
Report without another Provider read. Both Report payloads are direct string KV
Maps: update merges supplied keys, replacement removes omitted keys. Empty
strings stay present; deletion requires a Host-side removal and full replacement.
Empty Maps are legal, and `set`, `remove`, and `properties` are ordinary keys.
Explicit TASK/SERVER snapshot calls still return correlated `{"properties":{...}}`
Results. Loss may require an explicit full
report or a later connection baseline; this is not guaranteed eventual delivery.
Adapter publishes its complete installed observation via SYSTEM and Server
admission to Matching. A later Demand can read new persistent facts without
another Prepare. Server requests best-effort candidate invalidation after actual
facts changes; Kernel exact confirmation enforces that fence without deleting
Cache entries. The SDK owns no part of upstream publication, persistence or
scheduling invalidation.

JavaWorkerManager exposes these methods with a first `replicaKey` argument,
delegating only to that Worker; there is no group broadcast.

## Platform Resources

A standalone `JavaWorker` owns one package-private Platform containing its
Control executor, OkHttp infrastructure, WebSocket scheduler, and line-Socket
execution capacity. Its threads are non-daemon so a standalone Worker cannot
silently disappear when `main` returns. `close()` closes the Controller first
and then all Platform resources.

There is no Host Command pool. Each active OkHttp WebSocket reader runs on a
virtual thread supplied by the owning Platform Dispatcher; Dispatcher
`maxRequests` equals the fixed Worker capacity rather than OkHttp's default
64. The same Platform supplies a virtual-thread backend for OkHttp's internal
WebSocket TaskRunner. This matters during reconnect bursts, when identify,
writer and close tasks must not expand OkHttp's default unbounded platform
thread pool. OkHttp WebSocket callbacks pass through a physical connection
directly into the Core Transport and Handler. A single
atomic current-Attempt state owns the WebSocket connection, reconnect count
and terminal transition; there is no callback gate or separate open/finished
state. The shared scheduler only creates or reconnects Attempts and never runs
business Handlers. The line-Socket implementation continues to invoke the
Transport from its blocking reader thread. One physical connection preserves
protocol order; replaced Attempts and different Workers are not globally
serialized.

OkHttp does not expose TaskRunner backend selection as a public Java API. The
5.3.0 internal call is isolated inside package-private `JavaWorkerPlatform`;
an OkHttp upgrade must re-prove both the 128-connection owner test and the real
10k reconnect lane.

Closing a WebSocket Client atomically makes it terminal, immediately
closes/cancels the current socket, and returns without waiting for an admitted
callback. Callbacks not admitted from stale Attempts are suppressed; admitted
callbacks may finish, and a late Result remains a one-shot best-effort send.
Platform shutdown remains the owning Worker or Manager's responsibility.

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

Without a batch kind, `start`, keyed `start`, and `reconcile` retain the
ordinary client-key Prepare path. A Host with a bounded, kind-specific replica
inventory may opt into batch preparation during assembly:

```java
JavaWorkerManager manager = JavaWorkerManager.builder(...)
        .batchWorkerKind("SCENARIO_LAB")
        .replica("workers-a.jsonl:1", properties1)
        .replica("workers-a.jsonl:2", properties2)
        .build();

manager.prepareAndStart(List.of(
        "workers-a.jsonl:1",
        "workers-a.jsonl:2"
));
```

`prepareAndStart` loads each selected stopped replica's complete Properties
once, sends one batch of at most 100 entries, correlates the ordered response
by request position, and injects each returned workerId/Endpoint into that
replica's Controller. The configured batch kind tells Server how to derive
identity coordinates from the submitted Map; Java Worker does not interpret
that strategy. The batch creates no Matching facts; those arrive through the
verified connections. Running replicas are untouched. Batch failure does not fall back to
per-replica Prepare, and a concurrent stop prevents the returned coordinate
from starting that replica. On a batch-configured Manager, `start` and
`reconcile` use the same batch path; a keyed start is a one-record batch.
Batch preparation holds no Manager lock or cross-replica gate. Concurrent
requests may repeat the idempotent Prepare call for the same stopped replica;
the replica Controller still installs at most one run. Properties loading and
the control HTTP call remain ordinary bounded startup work. Virtual threads in
this module are confined to OkHttp WebSocket execution: Dispatcher readers and
TaskRunner writer/close work. They do not replace Control, reconnect scheduling
or line-Socket resources.

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

The Manager has no global execution monitor. Each replica owns one atomic
`desiredRunning` value; reconciliation rechecks that value after each
best-effort Controller operation. Keyed operations on different replicas are
independent. Group operations visit every replica in order without
prevalidation or rollback; the first failure is thrown after the pass and
later failures are suppressed. Close atomically rejects new entry, revokes
every desired state, tears replicas down in reverse order, and closes the
shared Platform exactly once.

Java Worker owns no pause state. A future pause remains Kernel delivery
admission plus Adapter delivery or Route behavior; the Worker either receives
no Command or its current run ends normally.

## Verification

```text
./gradlew :transport:java-worker:test
```

The focused test includes 128 simultaneous local WebSockets, verifies that
their listeners and OkHttp TaskRunner execute on virtual threads, proves the
Dispatcher is not limited to 64 calls, and verifies Platform shutdown releases
the shared connections and both virtual-thread Executors. The separate
[Worker Loaded Capacity + Recovery Stability](../../integrations/worker-loaded-recovery/)
proof prepares 15,000 identities, retains 10,000 active connections, closes 40
loaded Tasks across one graceful and two hard Server restarts, and checks
connection, thread and file-descriptor recovery. It is not a throughput or soak
claim.

## Report Event Semantics

The shared Core emits `platform.worker.command.succeeded` or
`platform.worker.command.failed`, preserving opaque Handler output and the
Command forward. `diagnosticCode` is diagnostic only (empty for success).
Properties and identity use their distinct Report names, not callable Handler
entries. See the [Delivery contract](../worker-delivery-contract/README.md#report-semantics)
for coordinated Server, Adapter, SDK and client upgrade requirements.
