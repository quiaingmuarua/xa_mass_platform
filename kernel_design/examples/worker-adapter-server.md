# Worker Adapter Server

Status: executable Worker Delivery Dispatch protocol example.

Kernel boundary:
[Worker Delivery Dispatch](../doc/scheduling/worker-delivery-dispatch.md).

## Purpose

The Worker Adapter Server is an independent HTTP process between
one configured Adapter DeliverSeed bucket and polling Workers:

```text
Worker
  -> poll own WorkerId
  -> receive one Adapter-private command
  -> execute opaque delivery item
  -> return one Adapter-private result

Worker Adapter Server
  -> DeliverSeedConsumerClient
  -> SeedResultCommandClient
```

It does not start `KernelApplication`, register resources, select Workers,
decode result context, mutate score, or classify Item finality.

## Start

```text
python -m kernel_design.examples.worker_adapter_server --endpoint-manager-id endpoint-manager-1
python -m kernel_design.examples.worker_adapter_server --endpoint-manager-id endpoint-manager-1 --config kernel.json
```

`--endpoint-manager-id` is required and fixes the mailbox bucket this process
may consume. The default address is `127.0.0.1:18081`. `--host`, `--port`, and
`--log-level` configure only this HTTP process. The optional JSON is the same
assembly configuration used by the Kernel process; this Adapter reads only the
Redis URL and prefix through the two transport clients.

## Poll Command

```http
POST /workers/{workerId}/commands:poll
```

No mailbox value returns `204`. One available Seed returns:

```json
{
  "commandId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
  "messageType": "TASK_SEED",
  "opaqueDeliveryItem": "{\"eventCode\":\"telecom.phone.inspect\",\"payload\":{\"phoneNumber\":\"+14155552671\"}}",
  "opaqueResultContext": "...",
  "taskItemClaimUntilMillis": 1234567890
}
```

The Adapter consumes only the WorkerId field named in the URL from its
configured endpoint-manager bucket. The same WorkerId in another Adapter bucket
is not visible. It rechecks the claim deadline before responding. An expired
Seed returns `204` without result evidence. Consumption is destructive; if the
HTTP response is lost, the outcome is unknown and Item claim plus Worker lease
expiry provide recovery.

`commandId` is generated for wire correlation and diagnostics only. It is not
persisted, checked as a Kernel fence, or promoted into DeliverSeed/SeedResult.

## Submit Result

```http
POST /workers/{workerId}/results
```

```json
{
  "commandId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
  "messageType": "TASK_SEED_RESULT",
  "opaqueResultContext": "...",
  "outcomeCode": "200",
  "opaqueResultPayload": "{\"countryCallingCode\":1,\"e164\":\"+14155552671\",\"isPossible\":true,\"isValid\":true,\"regionCode\":\"US\"}"
}
```

The Worker may submit only:

```text
200   successful Worker execution
1xxx Worker failure after execution entry
```

`3xxx` is reserved for a future trusted Adapter with direct pre-execution
rejection evidence. It cannot be submitted by a polling Worker and this example
server does not generate it. A `200` result must contain a non-empty opaque
payload; JSON `"null"` represents a successful operation with no business
value.

The Adapter forwards only `opaqueResultContext`, `outcomeCode`, and
`opaqueResultPayload` as one `SeedResult`. Accepted evidence returns
`202 {"accepted": true}`. A zero accepted count returns `503`; runtime
exceptions remain HTTP failures for the Worker to retry.

The runnable
[`polling_phone_worker.py`](polling_phone_worker.py) example consumes this
protocol and executes `telecom.phone.inspect` with Google libphonenumber. It is
a Worker process, not part of this Adapter host:

```text
python -m kernel_design.examples.polling_phone_worker --worker-id worker-1
```

Its outcome mapping is:

```text
200   inspection completed, including isValid=false
1400  malformed delivery payload or phoneNumber type
1404  unsupported eventCode
1500  unexpected tool or result-encoding failure
```

The URL WorkerId and echoed commandId are private protocol coordinates. The
Adapter does not parse opaque context to compare identities and does not add
either value to SeedResult.

## Bootstrap

WorkerGroup and Worker declarations must already exist through
`ResourcesCommandClient` or the Kernel Command Server resource routes. The
Worker Adapter does not infer WorkerGroup, assign WorkerId, or maintain a
connection registry in this slice.

## Non-Goals

- authentication, authorization, encryption, or trusted Worker identity;
- pending/ack, command persistence, Adapter retries, or exactly-once execution;
- push, WebSocket, session, or Worker route migration;
- Task/result queries or Worker resource registration;
- score, lease, scheduling, or result-finality ownership.
