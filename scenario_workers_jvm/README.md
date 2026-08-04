# XA Mass Scenario Workers JVM

`scenario_workers_jvm` is the finite Java 21 capability assembly used by the
checked-in `scenario-workers` Server profile. It is a root module because its
event definitions and Worker resource declarations are business scenario
choices, not Worker Delivery mechanisms.

The assembly is driven by two startup-time immutable maps:

```text
eventCode -> WorkerEventDefinition
workerGroupId -> ordered eventCodes
```

The first map is supplied to `ScenarioWorkers.fromJson(...)` by the host. The
second is parsed from the ordered JSON deployment manifest. A WorkerGroup
resolves its event codes once and every Worker in that group receives the same
immutable Definition list and Handler instances. Handlers therefore must be
stateless or thread-safe. Worker identity is resource and transport context;
it is not an Event Definition parameter or business-result field.

The module exposes:

```text
ScenarioWorkers.fromJson(...)
ScenarioWorkers.start()
ScenarioWorkers.close()
PhoneNumberWorkerEvents.definitions()
StringUtilityWorkerEvents.definitions()
```

The capability providers own the checked-in business Definitions. The Server
may flatten those lists into the event-code map, but it does not implement or
execute their Handlers. Definitions that are not referenced by a configured
WorkerGroup have no resource or runtime side effect.

The JSON object is keyed directly by WorkerGroup ID:

```json
{
  "scenario-phone-number-workers": {
    "attributes": {"capability":"libphonenumber"},
    "eventCodes": [
      "phonenumber.e164",
      "phonenumber.country",
      "phonenumber.original-carrier"
    ],
    "endpointManagerId": "scenario-websocket",
    "websocketUri": "ws://127.0.0.1:18083/api/v1/worker-delivery/websocket",
    "workers": [{
      "workerId": "scenario-phone-number-worker-001",
      "workerProperties": {"runtime":"java","region":"local"},
      "indexedPropertyUpdates": {"index.worker.region":"local"}
    }]
  }
}
```

There is no bundle ID, capability `type`, reflected class name, dynamic
registry, or per-Worker Definition factory. `eventCodes` must be non-empty,
unique, and present in the host-supplied Definition map. `{}` starts no
WorkerGroup or Worker.

Each generic WorkerGroup lifecycle performs:

```text
resolve ordered Definitions
-> upsert WorkerGroup
-> register each Worker
-> replace workerProperties
-> best-effort Property Index updates
-> start real WebSocket Worker transports
-> bounded initial connection wait
```

Groups start in manifest order and close in reverse order. Partial startup
failure closes local Worker transports but does not roll back Kernel resources.
`close()` releases only local network and thread resources; it does not change
Worker descriptors, scores, or Property Index truth.

The module depends on Kernel owner contracts and the concrete Worker network
client. It does not depend on Spring, Server, the Netty Adapter, Redis, scores,
Pacers, or HTTP controllers.

```text
./gradlew :scenario_workers_jvm:test
```
