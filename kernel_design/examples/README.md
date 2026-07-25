# Polling Phone Worker Example

Status: runnable Worker client for the executable-spec Runtime Server.

Install the Runtime Server and Worker dependencies:

```text
python -m pip install -r kernel_design/runtime_server/requirements.txt
python -m pip install -r kernel_design/examples/requirements.txt
```

Start the prerequisite Runtime Server:

```text
python -m kernel_design.runtime_server
```

Start the international-phone polling Worker:

```text
python -m kernel_design.examples.polling_phone_worker --worker-id worker-1
```

The Runtime Server listens on `127.0.0.1:18080`. Its protocol contract is
owned by
[Worker Delivery Dispatch](../doc/scheduling/worker-delivery-dispatch.md):

```text
Runtime Command API
  -> WorkerGroup/Worker upsert
  -> Task create/approve/close/Item append

Worker Delivery Gateway
  -> target Worker point poll/result
  -> long-lived Adapter cursor consume/batch result
```

Polling is not an independent Adapter process. Pure polling Workers explicitly
bind their declarations to the built-in logical route:

```text
endpointManagerId = system-polling
```

The Gateway uses `WorkerCommandConsumerClient` and
`SeedResultCommandClient`; only `KernelApplication` participates in the
Server lifespan.

## Phone Inspection Bootstrap

Create the resource declarations:

```http
PUT /worker-groups/phone-tools
{
  "attributes": {},
  "eventCodes": ["telecom.phone.inspect"],
  "itemAllocationFields": ["workerId"]
}
```

```http
PUT /worker-groups/phone-tools/workers/worker-1
{
  "endpointManagerId": "system-polling",
  "attributes": {"runtime": "python"},
  "dynamicAttributeNames": []
}
```

Create and approve a Task:

```http
POST /tasks
{
  "taskId": "phone-inspection-task",
  "workerGroupId": "phone-tools",
  "taskType": "TASK_DRIVEN",
  "allocationRule": {
    "attributes.runtime": {"$eq": "python"}
  },
  "config": {
    "priority": "50",
    "maximumCandidateWorkers": "10",
    "maxRetryTimes": "3"
  }
}
```

```http
POST /tasks/phone-inspection-task/approve
```

Append one due Item:

```http
POST /tasks/phone-inspection-task/items
{
  "items": [
    {
      "messageId": "phone-message-1",
      "eventCode": "telecom.phone.inspect",
      "createdAtMillis": 1784764800000,
      "payload": {"phoneNumber": "+14155552671"}
    }
  ]
}
```

The Worker point-polls:

```text
/worker-delivery/endpoint-managers/system-polling/
  workers/worker-1/commands:poll
```

and submits a result through the corresponding point result route. A successful
inspection stores:

```json
{
  "countryCallingCode": 1,
  "e164": "+14155552671",
  "isPossible": true,
  "isValid": true,
  "regionCode": "US"
}
```

## Worker Boundary

This directory contains Worker examples, not the Runtime Server or scheduling
implementation. The Phone Worker depends only on the Worker Delivery HTTP
contract and its phone-number library.

The example does not implement WebSocket transport, Adapter sessions,
pending/ack, Worker self-registration, result views, or exactly-once
execution.
