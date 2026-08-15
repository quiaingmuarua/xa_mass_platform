# XA Mass Worker Delivery Transport Implementations

Status: repository-local transport contracts and implementations.

```text
:transport:worker-delivery-contract
  -> Worker Delivery DTOs, Jsons, strict codecs

:transport:netty-adapter
  -> stable WebSocket and Socket Adapter façades
  -> Adapter lifecycle and scheduled Command/Report Process rounds
  -> one private finite queue per Process owner
  -> one shared Netty connection mechanism and route registry per instance
  -> one complete protocol-specific physical Server per instance

:transport:worker-core
  -> Java 11 Worker execution mechanism
  -> WorkerPreparation + two-state WorkerRunController + single-run Transport
  -> synchronous single Preparation on the Host calling thread
  -> synchronous WorkerCommandExecutor and TextMessageClient ports
  -> Polling remains a separate request-response mechanism
  -> network Client, Identity, Properties, and Register/Bind contracts
  -> no concrete network or platform implementation

:transport:java-worker
  -> JavaWorker WebSocket/line-Socket assembly
  -> shared Java network, Control, and Socket resources

:transport:android-worker
  -> shared Android OkHttp, HandlerThread, and Control resources
  -> Android Identity persistence and complete Worker assembly
```

`transport/` groups local transport mechanisms and implementations. It does
not change Kernel ownership. The Adapter delivers already-assigned commands;
the Worker executes statically supplied business handlers. TASK execution and
direct SYSTEM control reuse the same Delivery DTOs, Adapter Processes, physical
connection, and Worker Dispatcher. Server owns CONTROL_ONLY admission,
instance-local mailbox state, and remote-source priority; Transport does not
introduce a CONTROL_ONLY mode field, score check, or second queue owner.

The
[Worker Delivery Contract](worker-delivery-contract/README.md) remains
the protocol source shared by Kernel, Server, Adapter, and Worker. Do not move
connection management, handler execution, lifecycle, or network-library code
into that wire contract.

`transport:worker-core` is not a generic transport framework. Its long-lived
path has three owners: a concrete Client owns networking and transparent
reconnect, the package-private Transport owns Bind/Command/Result protocol, and
`WorkerRunController` owns only the two-state Worker run. Java and Android
hosts compose `RegisteredWorkerPreparation` with one Controller and expose the
same `WorkerLifecycle` contract. One accepted `start()` performs exactly one
Preparation synchronously and, if successful, starts one reconnecting endpoint
Client. Platform modules provide Identity storage, concrete network Clients,
shared network resources, and any asynchronous Host scheduling. A Client's
ordered protocol callback synchronously enters the Transport, Dispatcher, and
Handler; there is no long-connection Command executor or queue. Worker Core
creates and closes no thread, executor, or scheduler.
Preparation failure or Client reconnect exhaustion ends that run; only another
explicit Host `start()` prepares again. Reconnect and physical connection state are not Worker
lifecycle events or queries. No Endpoint URI or Worker business message is
persisted.
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

See:

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
