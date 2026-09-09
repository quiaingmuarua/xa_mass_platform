# XA Mass Worker Delivery Contract

Status: repository-local Java 11 protocol boundary.

This module contains the transport-neutral Worker Delivery DTOs, strict
deterministic codec, fixed Report event names, and `Jsons` facade shared by
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
  diagnosticCode,
  payload,
  forward
)
```

`src` and `dst` use the explicit wire enum:

```text
TASK | SERVER | SYSTEM | KERNEL | ADAPTER | WORKER
```

The DTO layer validates structure, not route policy. Current Worker execution
uses `TASK|SERVER|ADAPTER -> WORKER`; Kernel Serviceability uses
`KERNEL -> ADAPTER`. The receiving Owner validates the route combination.
`sourceId` is an opaque, non-blank identifier in the `src`
namespace. Workers use `workerId`; Adapters use `adapterId`. It is consistency
evidence, not authentication.

Delivery has no outer message or correlation ID. `DeliveryReport.fromCommand()`
requires an explicit Report event name, copies the opaque forward context, and
routes the Report to the Command source. It never copies the Command name.
TaskItem identity remains inside its owning Task contract and the opaque Result Context; Delivery does not inspect it.

For a Task command:

- `messageType` is the TaskItem `eventCode`;
- `payload` is the encoded TaskItem payload;
- `forward` is opaque Result Routing context;
- `executeBeforeMillis` is checked before execution starts.

For a Server-owned Direct Call:

- a Worker target uses `SERVER -> WORKER` and the Server-selected map key is
  the workerId;
- an Adapter target uses `SERVER -> ADAPTER`; its response-map key is a
  response-local opaque entry key which Transport must ignore;
- `DeliveryReport.fromCommand()` returns Worker or Adapter evidence to
  `dst=SERVER` with an explicit result event and the original `forward`;
- the Server Direct Call owner alone interprets that `forward` for waiter
  correlation.

`SERVER` names Server-owned requests and their correlated replies. `SYSTEM`
names platform events, not a Server process or Direct Call alias. A Report's
`src + sourceId` identifies its producer; its `messageType` contract defines the
event's semantic owner. An unsolicited Worker event does not become Server-owned
because its HTTP ingress is hosted by Server. The fixed Adapter-produced
`platform.adapter.worker-properties.observed` SYSTEM event carries a complete
observation for Server admission and Matching persistence. Unknown SYSTEM
events remain per-item rejections; none complete a Direct Call waiter.
Worker Properties update/replace reports remain `WORKER -> ADAPTER`; the
Adapter merges them before producing its distinct full upstream observation.

`DIRECT_CALL` is therefore not a Delivery DTO field or another protocol
envelope. Caller admission, Worker mailbox offer/replace policy, timeout,
Adapter FIFO priority and aggregate HTTP results remain outside this
transport-neutral module.

The Worker supplies `src=WORKER`, `sourceId=workerId`, `diagnosticCode`, and its
opaque payload. An Adapter may instead report a pre-delivery rejection as
`src=ADAPTER`, `sourceId=adapterId` while preserving the Command routing
fields needed by downstream owners.

Before opening a Worker transport, the Worker obtains a long-lived
platform-issued `workerId` and public Endpoint URI from one Server Prepare call
using its `workerGroupId + clientWorkerKey`, requested transport, and complete
Worker Properties snapshot. The Worker does not persist or hint that ID on a
later explicit start. Worker Delivery treats
`workerId` as an opaque non-blank routing value; its concrete format remains
owned by the Server Identity implementation.

WebSocket and line Socket send a direct `DeliveryReport` as their first value:

```json
{
  "dst":"ADAPTER",
  "forward":"",
  "messageType":"worker.connection.identify",
  "diagnosticCode":"",
  "payload":"null",
  "sourceId":"server-issued-worker-id",
  "src":"WORKER"
}
```

The Adapter consumes this Adapter-directed Report locally and takes the opaque
route identity from `sourceId`; the payload is exactly `null`. The first
workerId occurrence in one Adapter process is passed through the injected
Server route-verification port. Server may batch those single-item requests
when reading current Endpoint Bindings. Success is cached process-locally, so
later physical reconnects for that workerId activate a replacement Channel
without another Server read. There is no ACK or verification HTTP route.
Polling sends no identity Report; Server verifies its persisted
`system-polling` route on each point request.
Identity reporting does not create or update Endpoint Binding and is not
authentication, heartbeat, Worker resource mutation, or endpoint migration.

The optional Kernel Worker Serviceability policy queries current Adapter route
state through the same DTOs:

```text
Command
  src=KERNEL
  dst=ADAPTER
  messageType=platform.adapter.worker-connections.snapshot
  payload={workerIds:[...]}
  forward=worker-serviceability:v1:<checkStartedAtMillis>

Report
  src=ADAPTER
  dst=KERNEL
  messageType=platform.adapter.command.succeeded
  diagnosticCode=""
  same forward
  payload={stateByWorkerId:{...}}
```

This is a normal Adapter Handler invocation and Report, not a third envelope.
The Server transports the evidence to the Kernel-owned handoff; Transport does
not interpret snapshot state or mutate scheduling score.

The fixed connection-lifecycle control event is
`ADAPTER -> WORKER / worker.connection.close`. Its payload and forward fields
are empty JSON value and empty string respectively. Worker Transport consumes
the non-expired Command and ends its current run without returning a Result.
Connection lifecycle and direct SERVER controls both use the existing
`DeliveryCommand` and `DeliveryReport` DTOs; there is no third connection DTO
or transport-specific wrapper.

## Report Semantics

Command `messageType` identifies an execution intent. Report `messageType`
identifies an event that has happened; the names need not and normally do not
match. There are three business categories and one protocol category:

| Category | Contract |
| --- | --- |
| Command result | `platform.worker.command.succeeded / failed` or `platform.adapter.command.succeeded / failed`; opaque Handler output, original `forward`, destination is the Command source |
| Delivery fact | `platform.adapter.command.delivery-failed` ends an expired TASK delivery; independent `platform.adapter.worker-delivery.expired` supplies KERNEL serviceability evidence |
| Observation | Properties updated/replaced/observed, connection.changed and worker-poll.observed; each name defines its own payload and semantic Owner |
| Connection protocol | `worker.connection.identify`; close remains a Command with no Result |

These categories are documentation, not a classification field, queue or
registry. The complete names, payloads and boundaries are in
[the event catalog](../EVENTS.md#report-event-contracts).
Event names identify contracts, not every possible error or state value.
Ordinary extension Commands share the fixed command-result events; Report
names are never callable capabilities or entries in `events.snapshot`.

`diagnosticCode` is a required non-null string, including `""`, used only for
diagnostics. It has no numeric format or namespace requirement and cannot
determine success, failure, admission, correlation or scheduling evidence.
Producers normally use `""` for success, identity and observations, and may
use local error codes for failure. Receivers select exact event names plus
source/destination/correlation, never code prefixes or event suffixes.
Codec validates structure without a global event allowlist.

`DeliveryReport.fromCommand(command, producer, sourceId, reportMessageType,
diagnosticCode, payload)` copies only the reply destination and opaque correlation.
Command results retain opaque output; no result envelope is added.

### Coordinated Upgrade

This is a direct protocol cutover. Server, Adapter, Java/Android Worker SDKs,
Polling clients and API clients must upgrade together. There is no old
Command-name echo, numeric classification fallback, overload or dual reader.
Observed Direct Call responses now require `messageType`; `diagnosticCode`
remains diagnostic, and `observed` can represent a failed command.
Reports and observed Direct Call results use only the `diagnosticCode` field
name; the retired diagnostic field spelling is not an accepted alias.
Existing Redis data is not cleared or rewritten by this migration.

## JSON Boundary

`Jsons` exposes only JDK JSON values:

```text
Map / List / String / Boolean / Number / null
```

The private JSON engine is fixed inside this module. Gson types, arbitrary
POJO reflection, runtime engine selection, and fallback decoding are not part
of the public contract. Protocol codecs reject missing fields, extra fields,
wrong types, blank Report source IDs, missing/null/non-string diagnostics, and non-positive
deadlines. A legacy outer `messageId` is rejected as an extra field; missing
`src/sourceId` is not accepted as a legacy Report shape.

This module has no Spring, Redis, Server, Kernel, scheduling, connection,
lifecycle, or business-handler dependency. Server HTTP DTOs and Redis queue
suffixes remain owner-local.

## Verification

```text
./gradlew :transport:worker-delivery-contract:test
```
