# XA Mass Worker Delivery Contract JVM

Status: repository-local Java 21 protocol boundary.

This module contains the transport-neutral Worker Delivery DTOs, validation,
outcome classification, and strict deterministic JSON codec shared by
`server_jvm` and `worker_jvm`.

```text
server_jvm -> worker_delivery_contract_jvm
worker_jvm -> worker_delivery_contract_jvm
```

It has no Spring, Redis, Server, Kernel, scheduling, or business-handler
dependency. `WorkerCommandPage` and Redis queue suffixes are intentionally not
part of this contract because they belong to the Server runtime adapter.

The jar is not published as an SDK. It is a single in-repository source of
truth for the current Java Gateway and reference Worker.

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
```
