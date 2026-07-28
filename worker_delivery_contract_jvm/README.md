# XA Mass Worker Delivery Contract JVM

Status: repository-local Java 21 protocol boundary.

This module contains the transport-neutral Worker Delivery DTOs, validation,
outcome classification, and strict deterministic JSON codec shared by
`server_jvm` and `worker_jvm`.

Polling uses `WorkerCommandEnvelope` and `SeedResult` directly. Long-lived
transports first establish the process-local Worker binding:

```text
connect
-> WorkerConnectionBind(workerId)
-> business messages
```

The strict bind wire is:

```json
{"messageType":"WORKER_BIND","workerId":"worker-1"}
```

`WORKER_BIND` is connection setup, not a business message type, and therefore
does not enter `WorkerConnectionMessage` or its dispatcher. After binding,
long-lived transports use the strict flat business-message union:

```text
TASK_ITEM_COMMAND -> TaskItemCommandMessage(WorkerCommandEnvelope)
TASK_ITEM_RESULT  -> TaskItemResultMessage(SeedResult)
```

The bind DTO and business union have no generic payload and are not Kernel
runtime or persistence contracts.

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
