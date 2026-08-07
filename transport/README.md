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
  -> Polling plus one long-lived text-message Worker state machine
  -> shared text-message startup lifecycle
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

`transport:worker-core` is not a generic transport framework. It contains the
shared Worker execution and protocol state machines plus narrow network and
control Client contracts. Java and Android long-lived hosts both delegate
identity recovery, bounded Register/Bind preparation, Properties refresh, and
Transport supervision to Core's `TextMessageWorkerRuntime` and expose the same
`WorkerLifecycle` state, snapshot, and listener contract. Platform modules
provide Identity storage and concrete network Clients. Each Client owns
bounded reconnect for one returned Endpoint; exhaustion returns control to the
Core supervisor. No Endpoint URI is persisted.
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
