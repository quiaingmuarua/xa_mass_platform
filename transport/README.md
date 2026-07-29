# XA Mass Worker Delivery Transport Implementations

Status: repository-local Netty Adapter and OkHttp Worker implementations.

```text
../worker_delivery_contract_jvm
  -> Worker Delivery DTOs, Jsons, strict codecs

:transport:netty-adapter
  -> complete Adapter instances
  -> Netty WebSocket and line-oriented Socket listeners
  -> bounded mailbox dispatch and result buffering

:transport:okhttp-worker
  -> serial Worker command execution
  -> WorkerEventHandler contract
  -> Polling, WebSocket, and line-oriented Socket transports
```

`transport/` is a repository grouping for concrete network implementations.
It does not change Kernel ownership. The Adapter delivers already-assigned
commands; the Worker executes statically supplied business handlers.

The root
[Worker Delivery Contract](../worker_delivery_contract_jvm/README.md) remains
the protocol source shared by Kernel, Server, Adapter, and Worker. Do not move
connection management, handler execution, lifecycle, or network-library code
into that contract.

There is no `transport/common` module. Add one only after concrete shared
implementation behavior exists; do not expand the protocol contract for
speculative reuse.

See:

- [Netty Adapter](netty-adapter/README.md)
- [OkHttp Worker](okhttp-worker/README.md)

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
./gradlew :transport:netty-adapter:test
./gradlew :transport:okhttp-worker:test
```
