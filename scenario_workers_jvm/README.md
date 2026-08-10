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
    "reconnectPolicy": {
      "maxUnstableAttempts": 20,
      "reconnectIntervalMillis": 500,
      "stableConnectionDurationMillis": 10000
    },
    "workers": [{
      "clientWorkerKey": "scenario-phone-number-worker-001",
      "sandboxDirectory":
        "data/scenario-workers/scenario-phone-number-worker-001",
      "workerProperties": {"runtime":"java","region":"local"},
      "indexedPropertyUpdates": {"index.worker.region":"local"}
    }]
  }
}
```

`reconnectPolicy` is optional as a whole and otherwise strict: all three fields
are required and unknown fields are rejected. When omitted, the Client uses 20
unstable connection attempts. The old `retryPolicy`, Preparation retry fields,
and legacy top-level `reconnectIntervalMillis` field are rejected.

`sandboxDirectory` is optional. Without it, the Worker remains ephemeral and
uses `WorkerIdentityStore.noCache()`, so each process start repeats the
idempotent Register call with its fixed client key. With a sandbox, the directory
is a local Worker state home containing `identity.json`,
`worker-properties.json`, and an exclusive `worker.lock`. The configured
`workerProperties` initialize the file once; after that the file is the sole
source of the complete Worker Properties snapshot. Scenario does not persist
Endpoint URI, Binding, Index values, Channels, or pending Results.

`fromJson` only parses and assembles local state. `start()` first opens and
validates every configured sandbox before network activity, then performs:

```text
build one fixed JavaWorkerManager replica set per configured WorkerGroup
-> start every Group Manager
-> load the persisted workerId, or Register when it is absent
-> always Bind workerId as WEBSOCKET and replace the Kernel workerProperties snapshot
-> receive the configured Adapter public URI
-> start each JavaWorker's shared text-message Transport
-> return without waiting for first WebSocket Bind
-> for configured indexes only, wait for workerId within connectTimeoutMillis
-> submit explicit Property Index updates as best effort
```

The aggregate creates one `JavaWorkerHostResources` bundle and shares it across
one `JavaWorkerManager` per configured WorkerGroup. Each Manager owns only its
group's fixed Worker replicas. The control pool is
`max(1, min(workerCount, 4))`, the Handler pool is
`max(1, min(workerCount, max(2, availableProcessors)))`. These daemon threads are aggregate-scoped;
there is no Core or per-Worker control/Handler thread.

Malformed local assembly, duplicate sandbox use, or synchronous Worker
construction/start submission failure closes all local transports, releases
sandbox locks, and fails aggregate startup. Group Managers submit each
Worker's synchronous Register/Bind Preparation to the aggregate Control pool;
the initial connection remains asynchronous. Preparation or reconnect failure
leads that Worker to `STOPPED` and does not close the aggregate. A persisted identity
is never silently replaced; an operator must clear the sandbox explicitly when
the Server Identity registry has been reset.
Editing `worker-properties.json` takes effect on the next Scenario start; there
is no file watcher. Each
long-lived Worker sends only `WorkerConnectionBind(workerId)` to its returned
Adapter URI; the Adapter verifies the persisted endpoint route before exposing
the Channel. Scenario has no connection query and does not expose a first-Bind
startup barrier. For a configured Index update it waits for `workerId`, then
may retry `NOT_FOUND`, within the existing `connectTimeoutMillis` budget. A
missing identity or remaining Index failure is logged as `14010` and skipped.
`close()` closes Group Managers in reverse group order, releases sandbox locks,
and closes the process execution resources last. Startup failure performs the
same cleanup; it preserves sandbox files and does not mutate WorkerGroup,
Worker metadata, score, identity, Binding, or Property Index truth. Scenario
does not expose Manager reconciliation; terminal Workers are not restarted
automatically. Replica topology is configuration-time state: changing the
number of Workers requires updating the manifest and restarting the process.

WorkerGroup directory metadata is initialized separately by the Server profile.
The profile's catalog `eventCodes` may intentionally lag this module's local
Definition selection; Scenario Workers never compare or update the two values.

The module depends internally on Worker Core, `JavaWorker`, and the transport
wire contract. `JavaWorker` injects each configured client key into the final
Properties and delegates one Preparation plus connection supervision to the
shared Core runtime; transparent reconnect remains inside the concrete Client
and Index update uses a private HTTP client. It has no dependency
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
