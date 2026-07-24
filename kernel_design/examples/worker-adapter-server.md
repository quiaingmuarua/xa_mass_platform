# Worker Adapter Server

Status: executable Worker Delivery Protocol example.

Kernel boundary:
[Worker Delivery Dispatch](../doc/scheduling/worker-delivery-dispatch.md).

## Purpose

The Worker Adapter Server is an independent HTTP process between one configured
Adapter Worker-command bucket and polling Workers:

```text
Worker
  -> poll own WorkerId
  -> receive WorkerCommandEnvelope
  -> decode DeliverSeed and execute its opaque delivery item
  -> return SeedResult with the original commandId

Worker Adapter Server
  -> WorkerCommandConsumerClient
  -> SeedResultCommandClient
```

It does not start `KernelApplication`, register resources, select Workers,
generate command identity, define message types, decode DeliverSeed or
ResultContext, mutate score, or classify Item finality.

## Start

```text
python -m kernel_design.examples.worker_adapter_server --endpoint-manager-id endpoint-manager-1
python -m kernel_design.examples.worker_adapter_server --endpoint-manager-id endpoint-manager-1 --config kernel.json
```

`--endpoint-manager-id` fixes the mailbox bucket this process may consume. The
default address is `127.0.0.1:18081`. The optional JSON is the same assembly
configuration used by the Kernel process; the Adapter reads only Redis URL and
prefix through its two Worker Delivery clients.

## Poll Command

```http
POST /workers/{workerId}/commands:poll
```

No command returns `204`. One available command returns:

```json
{
  "commandId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
  "executeBeforeMillis": 1234567890,
  "messageType": "TASK_ITEM",
  "opaqueItem": "{\"opaqueDeliveryItem\":\"{...}\",\"opaqueResultContext\":\"...\",\"workerId\":\"worker-1\"}"
}
```

Task Dispatch generated all four fields after exact Item claim. The Adapter
returns them unchanged. It consumes only the URL WorkerId field from its
configured endpoint-manager bucket and rechecks `executeBeforeMillis` before
responding. An expired command returns `204` without result evidence.

Consumption is destructive. If the HTTP response is lost, delivery is unknown
and Item claim plus Worker lease expiry provide recovery.

The Worker decodes `opaqueItem` as `DeliverSeed`, verifies the inner WorkerId,
and then interprets only `opaqueDeliveryItem`. It must copy
`opaqueResultContext` unchanged into its `SeedResult`.

## Submit Result

```http
POST /workers/{workerId}/results
```

```json
{
  "commandId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
  "opaqueResultContext": "...",
  "outcomeCode": "200",
  "opaqueResultPayload": "{\"value\":1}"
}
```

The Worker copies the command's canonical `commandId` into `SeedResult`.
`commandId` is trace correlation only; it is not TaskItem `messageId`, an
idempotency key, a lease fence, or result truth.

The Adapter submits `SeedResult` directly through `SeedResultCommandClient`.
A Worker may submit only:

```text
200   successful Worker execution
1xxx Worker failure after execution entry
```

`3xxx` remains reserved for future trusted Adapter rejection evidence. A
polling Worker cannot submit it and this example Adapter does not generate it.
A `200` SeedResult must carry a non-empty opaque payload; JSON `"null"`
represents success with no business value.

Accepted evidence returns `202 {"accepted": true}`. A zero accepted count
returns `503`; runtime exceptions remain HTTP failures for the Worker to retry.
The Adapter does not parse `SeedResult.opaqueResultContext`.

## Phone Worker

The runnable
[`polling_phone_worker.py`](polling_phone_worker.py) decodes the stable protocol
and executes `telecom.phone.inspect` with Google libphonenumber:

```text
python -m kernel_design.examples.polling_phone_worker --worker-id worker-1
```

Its outcome mapping is:

```text
200   inspection completed, including isValid=false
1400  malformed DeliverSeed, delivery payload, or phoneNumber type
1404  unsupported eventCode
1500  unexpected tool or result-encoding failure
```

## Bootstrap

WorkerGroup and Worker declarations must already exist through
`ResourcesCommandClient` or the Kernel Command Server resource routes. The
Worker Adapter does not infer WorkerGroup, assign WorkerId, or maintain a
connection registry in this slice.

## Multi-Adapter Contract

Polling, WebSocket, and future transport profiles must carry the same
outbound `WorkerCommandEnvelope` and inbound `SeedResult` contracts. They may
choose different session, batching, and flow-control policies, but may not
generate a different command id, reinterpret `messageType`, or expose inner
Kernel payloads as transport-specific fields.

## Non-Goals

- authentication, authorization, encryption, or trusted Worker identity;
- pending/ack, command persistence, Adapter retries, or exactly-once execution;
- push, WebSocket, session, or Worker route migration;
- Task/result queries or Worker resource registration;
- score, lease, scheduling, or result-finality ownership.
