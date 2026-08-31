# XA Mass Worker Delivery Transport Implementations

Status: repository-local transport contracts and implementations.

Cross-module scheduling and delivery authority is fixed by the root
[architecture entrypoint](../README.md). This document owns only the Transport
module map and common implementation boundaries.

```text
:transport:worker-delivery-contract
  -> Worker Delivery DTOs, Jsons, strict codecs

:transport:netty-adapter
  -> stable WebSocket and Socket Adapter facades
  -> Adapter lifecycle and scheduled Command/Report Process rounds
  -> one private finite queue per Process owner
  -> one shared Netty connection mechanism and route registry per instance
  -> one complete protocol-specific physical Server per instance

:transport:worker-core
  -> Java 11 Worker execution mechanism
  -> WorkerPreparation + owner-local two-state WorkerRunController
  -> one Preparation per accepted start on an injected Control Executor
  -> synchronous WorkerCommandExecutor and TextMessageClient ports
  -> Polling remains a separate request-response mechanism
  -> network Client, Properties, and one-shot Prepare contracts
  -> no concrete network or platform implementation

:transport:java-worker
  -> JavaWorker WebSocket/line-Socket assembly
  -> shared Java network, Control, and Socket resources

:transport:android-worker
  -> shared Android OkHttp, HandlerThread, and Control resources
  -> persistent Android client key and complete Worker assembly
```

`transport/` groups local transport mechanisms and implementations. It does
not change Kernel ownership. The Adapter delivers already-assigned commands;
the Worker executes statically supplied business handlers. TASK execution and
caller-targeted DIRECT_CALL reuse the same Delivery DTOs, Adapter Processes,
physical connection, and Worker Dispatcher. Server owns Direct Call admission
and correlation; the delivery owner supplies the shared Worker mailbox.
Transport does not introduce a Direct mode field, score check, or second queue
owner.
Adapter management events come from one immutable `platform.adapter.*`
Handler map. Java and Android Workers prepend finite `platform.worker.*`
Definitions before Host `extension.worker.*` Definitions. Event Names describe
capability ownership, while Command `src` remains invocation evidence. Neither
surface supports runtime registration. Their fixed `events.snapshot` handlers
report only the immutable names loaded by that process, not WorkerGroup or
scheduling truth.

The
[Worker Delivery Contract](worker-delivery-contract/README.md) remains
the protocol source shared by Kernel, Server, Adapter, and Worker. Do not move
connection management, handler execution, lifecycle, or network-library code
into that wire contract.

`transport:worker-core` is not a generic transport framework. Its long-lived
path has three owners: a concrete Client owns networking and transparent
reconnect, the package-private Transport owns identity/Command/Result protocol, and
`WorkerRunController` owns only the two-state Worker run. Java and Android
hosts compose `WorkerControlPreparation` with one Controller and expose the
same `WorkerLifecycle` contract. One accepted `start()` performs exactly one
Preparation on the injected Control Executor and, if successful, starts one
reconnecting endpoint Client. Platform modules provide the one-shot control
Client, concrete network Clients, shared network resources, and asynchronous
execution. A physical connection's protocol callback synchronously enters the
Transport, Dispatcher, and Handler; there is no long-connection Command
executor or queue. The Java WebSocket Client closes without waiting for an
admitted callback, and Core adds no cross-Attempt or cross-run Handler fence.
Worker Core creates and closes no thread, executor, or scheduler.
Preparation failure or Client reconnect exhaustion ends that run; only another
explicit Host `start()` prepares again. Active stop revokes the run before
closing its Client outside the run-state gate; the Java WebSocket Client does
not wait for callback completion. Reconnect and physical connection state are
not Worker lifecycle events or queries. Worker
owns no pause or delivery-admission state; those remain Kernel and Adapter
concerns. No Endpoint URI or Worker business message is persisted.
The Netty Adapter is Netty-specific rather than a generic transport framework.
Its shared connection mechanism owns identity, route verification, Command
routing, and Result ingress; its WebSocket and Socket Servers independently
own their complete physical network resources and protocols. This three-owner
production cut is frozen. The two physical Servers share a behavior contract
in tests rather than a lifecycle implementation, and each Server plus the
Adapter scheduler enforces its own bounded shutdown budget. Source priority
applies when Server answers a remote Command consume request; it does not
reorder commands already in the Adapter queue or preempt a Worker Handler
already running on the connection callback lane.

Within the connection owner, one per-Worker Route entry is pending, connected,
or retained disconnected verification evidence. Only disconnected
verification evidence is TTL/capacity cached; active connections are never
cache-evicted, and Channel metadata contains only the claimed workerId for
callback correlation. The separate properties projection is capacity bounded
but not time deleted: age changes `FRESH` to `STALE`, while eviction changes it
to `UNKNOWN`. Connection and properties expose independent snapshots without
an atomic join or shared version. Neither cache is scheduling, Binding or
Worker lifecycle truth.

See:

- [Transport Platform Event Catalog](EVENTS.md)
- [Netty Adapter](netty-adapter/README.md)
- [Worker Core](worker-core/README.md)
- [Java Worker](java-worker/README.md)
- [Android Worker](android-worker/README.md)

## Verification

```text
./gradlew :transport:worker-delivery-contract:test
./gradlew :transport:worker-core:test
./gradlew :transport:netty-adapter:test
./gradlew :transport:java-worker:test
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
```
