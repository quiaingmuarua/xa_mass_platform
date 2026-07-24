# Kernel HTTP Examples

Status: internal kernel HTTP boundary and Worker Delivery protocol examples.

Install the example-only dependencies:

```text
python -m pip install -r kernel_design/examples/requirements.txt
```

Start the Kernel command process:

```text
python -m kernel_design.examples.kernel_command_server
```

Start the independent Worker polling process:

```text
python -m kernel_design.examples.worker_adapter_server --endpoint-manager-id endpoint-manager-1
```

Start the international-phone tool Worker:

```text
python -m kernel_design.examples.polling_phone_worker --worker-id worker-1
```

The HTTP hosts use:

```text
Kernel Command Server   127.0.0.1:18080
Worker Adapter Server   127.0.0.1:18081
```

Both accept the same optional `--config kernel.json`; `--host`, `--port`, and
`--log-level` configure only the selected HTTP process. Worker Adapter startup
also requires the immutable `--endpoint-manager-id` mailbox coordinate.

The Kernel host composes `KernelApplication` and `ResourcesCommandClient`.
Its lifespan starts only the scheduling application. It exposes resource
upsert and Task commands, not Worker command consumption or Worker result
ingress. It is the current internal HTTP target of the
[JVM Runtime API Server](../../server_jvm/README.md), not the final public API.

The [Worker Adapter Server](worker-adapter-server.md) composes only
`WorkerCommandConsumerClient` and `SeedResultCommandClient`. It has no
scheduling
lifecycle:

```text
POST /workers/{workerId}/commands:poll
POST /workers/{workerId}/results
```

The Worker-facing `WorkerCommandEnvelope` is the Kernel-defined,
transport-neutral outbound command DTO. Its opaque item contains DeliverSeed.
The Adapter forwards command identity, message type, deadline, and opaque item
unchanged. The Worker returns a semantic `SeedResult` directly, copying only
the commandId for trace correlation.

The Polling Phone Worker depends only on the Worker Adapter HTTP protocol. It
executes:

```text
eventCode = telecom.phone.inspect
payload   = {"phoneNumber": "+14155552671"}
```

using Google libphonenumber's Python port. A successful result reports the ISO
region, country calling code, E.164 form, and possible/valid classifications.
An invalid number is a successful inspection with `isValid=false`.

## Phone Inspection Bootstrap

With the Kernel Command Server and Worker Adapter Server running, create the
resource declarations:

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
  "endpointManagerId": "endpoint-manager-1",
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

Append one due Item using a current or past millisecond timestamp:

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

The Polling Phone Worker receives the command through the Adapter and submits a
result equivalent to:

```json
{
  "countryCallingCode": 1,
  "e164": "+14155552671",
  "isPossible": true,
  "isValid": true,
  "regionCode": "US"
}
```

Task lifecycle routes include explicit approve and close commands. Close is
available for both Task types and does not expose a terminal score.

Dynamic attribute mutation is intentionally absent until the assembly installs
a real dynamic-attribute handler owner.

The Python Kernel Command Server is not a historical or control-plane CRUD
service. Its commands mutate current runtime truth. Public API compatibility,
authentication, and process-level error mapping belong to `server_jvm`.

The Worker examples still intentionally omit Worker identity proof,
pending/ack, production push transport, and Worker self-registration.
