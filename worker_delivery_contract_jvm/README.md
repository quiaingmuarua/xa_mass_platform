# XA Mass Worker Delivery Contract JVM

Status: repository-local Java 11 compatible protocol boundary.

This module contains the transport-neutral Worker Delivery DTOs, validation,
outcome classification, strict deterministic codec, and `Jsons` facade shared
by `server_jvm`, `worker_delivery_adapter_jvm`, and
`worker/okhttp-worker`.

`Jsons` exposes only JDK JSON values:

```text
Map / List / String / Boolean / Number / null
```

The private JSON engine is fixed inside this module. Gson types, arbitrary
POJO reflection, runtime engine selection, and fallback decoding are not part
of the public contract. Protocol DTO codecs still validate exact field sets,
types, canonical UUIDs, message types, and deadlines explicitly.

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
worker_delivery_adapter_jvm -> worker_delivery_contract_jvm
worker/okhttp-worker -> worker_delivery_contract_jvm
```

It has no Spring, Redis, Server, Kernel, scheduling, or business-handler
dependency. Server batch HTTP request/response DTOs and Redis queue suffixes
are intentionally not part of this transport-neutral contract.

The Java 11 compatible jar is not published as an SDK. It is the single
in-repository protocol source for the Server, Adapter, and Worker library.

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
```
