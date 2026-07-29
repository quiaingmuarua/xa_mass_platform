# XA Mass Worker Delivery Contract JVM

Status: repository-local Java 11 compatible protocol boundary.

This module contains the transport-neutral Worker Delivery DTOs, validation,
outcome classification, strict deterministic codec, and `Jsons` facade shared
by `kernel_jvm`, `server_jvm`, `transport/netty-adapter`, and
`transport/okhttp-worker`.

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
{"messageType":"WORKER_BIND","payload":"{\"workerId\":\"worker-1\"}"}
```

All long-lived messages use the same stable outer DTO:

```text
WorkerConnectionMessage(
  messageType: String,
  payload: String
)
```

The payload is the deterministic encoding of the real inner contract:

```text
WORKER_BIND       -> WorkerConnectionBind
TASK_ITEM_COMMAND -> WorkerCommandEnvelope
TASK_ITEM_RESULT  -> SeedResult
```

`WORKER_BIND` is connection setup and does not enter either side's business
Definition Manager. Adapter and Worker install different static Definitions
for the directions they accept. The outer codec validates only
`messageType/payload`; each receiving owner decides whether and when to decode
the payload.

`SeedResultSource` is batch-ingress metadata used between an Adapter and the
Server. It is not part of `SeedResult`, a Worker connection frame, or Redis
result truth.

```text
kernel_jvm -> worker_delivery_contract_jvm
server_jvm -> worker_delivery_contract_jvm
transport/netty-adapter -> worker_delivery_contract_jvm
transport/okhttp-worker -> worker_delivery_contract_jvm
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
