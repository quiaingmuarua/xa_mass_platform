# XA Mass Worker Delivery Transport Implementations

Status: repository-local transport contracts and implementations.

```text
../worker_delivery_contract_jvm
  -> Worker Delivery DTOs, Jsons, strict codecs

:transport:netty-adapter
  -> complete Adapter instances
  -> Netty WebSocket and line-oriented Socket listeners
  -> bounded mailbox dispatch and result buffering

:transport:worker-core
  -> Java 11 Worker execution mechanism
  -> WorkerPreparation + two-state WorkerLoop + single-run text runtime
  -> Host-injected shared control/Handler/retry execution resources
  -> Polling remains a separate request-response mechanism
  -> network Client, Identity, Properties, and Register/Bind contracts
  -> no concrete network or platform implementation

:transport:java-worker
  -> JavaWorker WebSocket/line-Socket assembly
  -> OkHttp point/WebSocket and JDK line-socket Client implementations

:transport:android-worker
  -> Android HandlerThread/Looper WebSocket Client
  -> Android Identity persistence and complete Worker assembly
```

`transport/` groups local transport mechanisms and implementations. It does
not change Kernel ownership. The Adapter delivers already-assigned commands;
the Worker executes statically supplied business handlers.

The root
[Worker Delivery Contract](../worker_delivery_contract_jvm/README.md) remains
the protocol source shared by Kernel, Server, Adapter, and Worker. Do not move
connection management, handler execution, lifecycle, or network-library code
into that wire contract.

`transport:worker-core` is not a generic transport framework. Its long-lived
path has three owners: a concrete Client owns networking and transparent
reconnect, the package-private Runtime owns Bind/Command/Result protocol, and
`WorkerLoop` owns only the two-state Worker run. Java and Android hosts compose
`RegisteredWorkerPreparation` with one `WorkerLoop` and expose the same
`WorkerLifecycle` contract. One accepted `start()` covers bounded preparation
and one reconnecting endpoint Client. Platform modules provide Identity
storage, concrete network Clients, and explicitly owned shared execution
resources. Worker Core creates and closes no thread, executor, or scheduler.
Client reconnect exhaustion ends that run; only another explicit `start()`
prepares again. Reconnect and physical connection state are not Worker
lifecycle events or queries. No Endpoint URI or Worker business message is
persisted.
Netty Adapter behavior remains independent until a concrete shared mechanism
exists.

See:

- [Netty Adapter](netty-adapter/README.md)
- [Worker Core](worker-core/README.md)
- [Java Worker](java-worker/README.md)
- [Android Worker](android-worker/README.md)

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
./gradlew :transport:worker-core:test
./gradlew :transport:netty-adapter:test
./gradlew :transport:java-worker:test
./gradlew :transport:android-worker:testDebugUnitTest
./gradlew :transport:android-worker:assembleDebug
```
