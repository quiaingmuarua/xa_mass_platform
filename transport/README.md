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
  -> Polling, WebSocket, and line Socket Worker state machines
  -> string-only network Client contracts
  -> no concrete network or platform implementation

:transport:okhttp-worker
  -> OkHttp point/WebSocket and JDK line-socket Client implementations

:transport:android-client
  -> Android HandlerThread/Looper WebSocket client
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
already-shared Worker execution and protocol state machines plus narrow
string-only network Client contracts. JVM and Android hosts select a concrete
Client and explicitly construct the same Worker Transport. Netty Adapter
behavior remains independent until a concrete shared mechanism exists.

See:

- [Netty Adapter](netty-adapter/README.md)
- [Worker Core](worker-core/README.md)
- [OkHttp Worker](okhttp-worker/README.md)
- [Android Client](android-client/README.md)

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
./gradlew :transport:worker-core:test
./gradlew :transport:netty-adapter:test
./gradlew :transport:okhttp-worker:test
./gradlew :transport:android-client:testDebugUnitTest
./gradlew :transport:android-client:assembleDebug
```
