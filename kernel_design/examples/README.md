# Polling Phone Worker Example

Status: runnable Worker client for the Java Runtime API Server.

Install the Runtime Server and Worker dependencies:

```text
python -m pip install -r kernel_design/runtime_server/requirements.txt
python -m pip install -r kernel_design/examples/requirements.txt
```

Start the Python scheduling process and Java Runtime API Server:

```text
python -m kernel_design.runtime_server
./gradlew :server_jvm:bootRun
```

Start the international-phone polling Worker:

```text
python -m kernel_design.examples.polling_phone_worker --worker-id worker-1
```

The Worker calls the Java server at `127.0.0.1:18082`. Its protocol contract is
owned by
[Worker Delivery Dispatch](../doc/scheduling/worker-delivery-dispatch.md):

```text
Java Runtime Command API
  -> WorkerGroup/Worker upsert
  -> Task create/approve/close/Item append

Java Worker Delivery Gateway
  -> target Worker point poll/result
  -> long-lived Adapter cursor consume/batch result
```

Polling is not an independent Adapter process. Pure polling Workers explicitly
bind their declarations to the built-in logical route:

```text
endpointManagerId = system-polling
```

The Java Gateway reads only WorkerCommand mailbox fields and appends only
SeedResult queue entries. Python remains the scheduling and ResultRouting
owner.

## Phone Inspection Bootstrap

Create the resource declarations:

```http
PUT /api/v1/worker-groups/phone-tools
{
  "attributes": {},
  "eventCodes": ["telecom.phone.inspect"],
  "itemAllocationFields": ["workerId"]
}
```

```http
PUT /api/v1/worker-groups/phone-tools/workers/worker-1
{
  "endpointManagerId": "system-polling",
  "attributes": {"runtime": "python"},
  "dynamicAttributeNames": []
}
```

Create and approve a Task:

```http
POST /api/v1/tasks
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
POST /api/v1/tasks/phone-inspection-task/approve
```

Append one due Item:

```http
POST /api/v1/tasks/phone-inspection-task/items
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
/api/v1/worker-delivery/endpoint-managers/system-polling/
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
