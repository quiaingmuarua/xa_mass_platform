# XA Mass Scenario Workers JVM

`scenario_workers_jvm` is the finite Java 21 capability assembly used by the
checked-in `scenario-workers` Server profile. It demonstrates ordinary Workers;
it is not a Kernel owner, privileged Server extension, Adapter, plugin SPI, or
independently deployed application.

The module owns its built-in phone-number and string-utility
`WorkerEventDefinition` values. Those definitions are package-private and are
selected by each worker-config group's local `eventCodes`. This field is local
assembly input, not the WorkerGroup catalog projection. Every Worker in the
same configured group receives the same immutable Definition list and shared
Handler instances, so built-in Handlers must be stateless or thread-safe.

The only public assembly surface is:

```java
ScenarioWorkers workers = ScenarioWorkers.fromJson(
        workerConfigJson,
        URI.create("http://127.0.0.1:18082")
);
workers.start();
workers.close();
```

The worker JSON is keyed by WorkerGroup ID:

```json
{
  "scenario-phone-number-workers": {
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

`fromJson` only parses and assembles local state. `start()` performs:

```text
create and start every WebSocket Worker transport
-> wait for every configured group to connect
-> register each Worker through the public Runtime Resource HTTP API
-> replace workerProperties through the same API
-> submit explicit Property Index updates as best effort
```

Required registration or property-update failure closes all local transports
and fails startup. Index failure is diagnostic only. `close()` releases local
network and thread resources in reverse order; it does not mutate WorkerGroup,
Worker descriptor, score, or Property Index truth.

WorkerGroup directory metadata is initialized separately by the Server profile.
The profile's catalog `eventCodes` may intentionally lag this module's local
Definition selection; Scenario Workers never compare or update the two values.

The module depends internally on Worker Core, the concrete OkHttp network
client, and the transport wire contract. Worker registration uses a private JDK
HTTP client and Server's public API JSON. It has no dependency on `kernel_jvm`,
Spring, Server implementation classes, the Netty Adapter, Redis, scores, or
Pacers.

```text
./gradlew :scenario_workers_jvm:test
```
