# XA Mass Worker Delivery Contract JVM

Status: repository-local Java 11 protocol boundary.

This module contains the transport-neutral Worker Delivery DTOs, strict
deterministic codec, outcome classification, and `Jsons` facade shared by
`kernel_jvm`, `server_jvm`, `transport/netty-adapter`, and
`transport/worker-core`. Java and Android Worker modules consume the protocol
through that Core boundary rather than defining platform-specific wire DTOs.

## Protocol

Command target identity remains outside the message. Reports carry the
producer declaration as `src + sourceId`. The two asymmetric message
contracts are:

```text
DeliveryCommand(
  src,
  dst,
  messageType,
  executeBeforeMillis,
  payload,
  forward
)

DeliveryReport(
  src,
  sourceId,
  dst,
  messageType,
  outcomeCode,
  payload,
  forward
)
```

`src` and `dst` use the explicit wire enum:

```text
TASK | SYSTEM | ADAPTER | WORKER
```

The DTO layer validates structure, not route policy. Current Worker execution
uses `TASK|SYSTEM|ADAPTER -> WORKER`; the receiving Owner validates the route
combination. `sourceId` is an opaque, non-blank identifier in the `src`
namespace. Workers use `workerId`; Adapters use `adapterId`. It is consistency
evidence, not authentication.

Delivery has no outer message or correlation ID. `DeliveryReport.fromCommand()`
copies the Command message type and opaque forward context, then routes the
Report to the Command source. TaskItem identity remains inside its owning Task
contract and the opaque Result Context; Delivery does not inspect it.

For a Task command:

- `messageType` is the TaskItem `eventCode`;
- `payload` is the encoded TaskItem payload;
- `forward` is opaque Result Routing context;
- `executeBeforeMillis` is checked before execution starts.

The Worker supplies `src=WORKER`, `sourceId=workerId`, `outcomeCode`, and its
opaque payload. An Adapter may instead report a pre-delivery rejection as
`src=ADAPTER`, `sourceId=adapterId` while preserving the Command routing
fields needed by downstream owners.

Before opening a Worker transport, the Worker obtains a long-lived
platform-issued `workerId` from the Server Identity API using its
`workerGroupId + clientWorkerKey`. It then calls the Server Bind API with its
requested transport and complete Worker Properties snapshot. Bind persists the
delivery endpoint and returns its public URI. Worker Delivery treats
`workerId` as an opaque non-blank routing value; its concrete format remains
owned by the Server Identity implementation.

WebSocket and line Socket send a direct `DeliveryReport` as their first value:

```json
{
  "dst":"ADAPTER",
  "forward":"",
  "messageType":"worker.connection.identify",
  "outcomeCode":"200",
  "payload":"null",
  "sourceId":"server-issued-worker-id",
  "src":"WORKER"
}
```

The Adapter consumes this Adapter-directed Report locally and takes the opaque
worker ID from `sourceId` without interpreting its format. The first occurrence
of that worker ID in one Adapter process is passed to Server route verification;
success is cached process-locally, so later physical reconnects identify and
activate a replacement Channel without another Server read. There is no ACK.
Polling sends no identity Report; Server verifies its persisted
`system-polling` route on each point request.
Identity reporting does not create or update Endpoint Binding and is not
authentication, heartbeat, property-index update, or endpoint migration.

The other fixed control event is
`ADAPTER -> WORKER / worker.connection.close`. Its payload and forward fields
are empty JSON value and empty string respectively. Worker Transport consumes
the non-expired Command and ends its current run without returning a Result.
These controls use the existing `DeliveryCommand` and `DeliveryReport` DTOs; there
is no third connection DTO or transport-specific wrapper.

## JSON Boundary

`Jsons` exposes only JDK JSON values:

```text
Map / List / String / Boolean / Number / null
```

The private JSON engine is fixed inside this module. Gson types, arbitrary
POJO reflection, runtime engine selection, and fallback decoding are not part
of the public contract. Protocol codecs reject missing fields, extra fields,
wrong types, blank Report source IDs, invalid outcome codes, and non-positive
deadlines. A legacy outer `messageId` is rejected as an extra field; missing
`src/sourceId` is not accepted as a legacy Report shape.

This module has no Spring, Redis, Server, Kernel, scheduling, connection,
lifecycle, or business-handler dependency. Server HTTP DTOs and Redis queue
suffixes remain owner-local.

## Verification

```text
./gradlew :worker_delivery_contract_jvm:test
```
