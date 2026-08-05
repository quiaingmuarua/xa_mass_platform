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
    "workers": [{
      "clientWorkerKey": "scenario-phone-number-worker-001",
      "workerProperties": {"runtime":"java","region":"local"},
      "indexedPropertyUpdates": {"index.worker.region":"local"}
    }]
  }
}
```

`fromJson` only parses and assembles local state. `start()` performs for each
configured Worker:

```text
register workerGroupId + clientWorkerKey through the Identity API
-> recover or obtain the same platform-issued workerId
-> Bind workerId as WEBSOCKET and replace the Kernel workerProperties snapshot
-> receive the configured Adapter public URI
-> create and start every WebSocket Worker transport
-> wait for every configured group to connect
-> submit explicit Property Index updates as best effort
```

Register or Bind failure closes all local transports and fails startup. Each
long-lived Worker sends only `WorkerConnectionBind(workerId)` to its returned
Adapter URI; the Adapter verifies the persisted endpoint route before exposing
the Channel. Index update may briefly observe `NOT_FOUND`, so Scenario retries
it within the connection timeout; remaining Index failure is diagnostic only.
`close()` releases local network and thread resources in reverse order; it does
not mutate WorkerGroup, Worker metadata, score, identity, Binding, or Property
Index truth.

WorkerGroup directory metadata is initialized separately by the Server profile.
The profile's catalog `eventCodes` may intentionally lag this module's local
Definition selection; Scenario Workers never compare or update the two values.

The module depends internally on Worker Core, concrete OkHttp clients, and the
transport wire contract. Register and Bind use the shared narrow OkHttp Worker
control client; Index update uses a private HTTP client. It has no dependency
on `kernel_jvm`, Spring, Server implementation classes, the Netty Adapter,
Redis, scores, or Pacers. The Worker does not configure or know an
`endpointManagerId`; Bind returns the selected public WebSocket URI.

```text
./gradlew :scenario_workers_jvm:test
```

The repository-level cross-process acceptance is owned by
[`integrations/worker-capability-rpc`](../integrations/worker-capability-rpc/).
CI starts Redis, the Python Kernel, and the Server `scenario-workers` profile,
then proves Register, Bind, route verification, all 20 WebSocket connections,
and 60 targeted RPC results. Module tests do not replace that process proof.
