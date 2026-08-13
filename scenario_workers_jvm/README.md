# XA Mass Scenario Workers JVM

`scenario_workers_jvm` is the finite Java 21 capability assembly used by the
checked-in `scenario-workers` Server profile. It is a local Worker Lab, not a
Kernel owner, privileged Server extension, Adapter, plugin SPI, or independently
deployed application.

The module owns the checked-in phone-number and string-utility
`WorkerEventDefinition` extensions. A configured WorkerGroup selects one
immutable extension list through its local `eventCodes`; every discovered
Worker in that group shares the same stateless or thread-safe Handler instances.
The WorkerGroup catalog projection remains a separate Server-owned value and
may lag this local list.

The only public assembly surface is:

```java
ScenarioWorkers workers = ScenarioWorkers.fromJson(
        workerConfigJson,
        "data/scenario-workers",
        URI.create("http://127.0.0.1:18082")
);
workers.start();
workers.close();
```

The JSON config declares groups and local runtime options only. It never lists
individual Workers:

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
    }
  }
}
```

`reconnectPolicy` is optional as a whole and otherwise strict: all three fields
are required and unknown fields are rejected. Omitted request and connect
timeouts use 10 and 15 seconds. The old inline `workers`, `sandboxDirectory`,
retry-policy, and Adapter URI fields are rejected.

## Persistent Worker Lab

The checked-in profile exclusively owns this writable local directory:

```text
data/scenario-workers/
├── scenario-phone-number-workers/
│   ├── scenario-phone-number-worker-001.json
│   └── ...
└── scenario-string-utils-workers/
    ├── scenario-string-utils-worker-001.json
    └── ...
```

Initialization is decided independently for each configured WorkerGroup:

```text
missing data/scenario-workers/{workerGroupId}/
  -> stage that Group's checked-in default Worker files
  -> validate every staged Worker file
  -> move the complete staged directory into place

existing data/scenario-workers/{workerGroupId}/
  -> never seed, merge, repair, or upgrade defaults
  -> load that directory's exact current contents
```

An existing empty Group directory intentionally starts zero Workers. Deleting
one configured Group directory resets only that Group to its defaults on the
next start; deleting the complete Lab root resets every configured Group. A
newly configured Group is initialized only when its directory is absent.
Unconfigured directories, including older top-level sandbox directories, are
ignored. There is no flag file, template version, migration, compatibility
reader, file watcher, or multi-process lock.

The Lab root must end in `data/scenario-workers` and must not pass through a
symbolic link. Only direct, non-symlink lowercase `*.json` children of configured
Group directories are discovered. Files are sorted by name; each group is
bounded to 100 Workers. Any discovered invalid file fails aggregate startup
before a Manager or network Client is created.

Each filename without `.json` is its `clientWorkerKey`; its parent directory is
the configured `workerGroupId`. One file owns the complete persistent local
snapshot:

```json
{
  "schemaVersion": 1,
  "workerId": "optional-platform-issued-id",
  "workerProperties": {
    "runtime": "java",
    "region": "local"
  },
  "indexedPropertyUpdates": {
    "index.worker.region": "local"
  }
}
```

`schemaVersion` must be integer `1`; `workerId` is optional until first
Register; both map fields default to `{}`; unknown fields are rejected. The
filename and parent directory are the only client-key and group coordinates,
so those values are not duplicated inside the JSON. The first platform-issued
Worker ID is written back through a temporary file and atomic replacement. A
different existing ID is never silently replaced.

## Runtime lifecycle

`start()` initializes or opens the Lab, preflights every configured Group, and
then performs:

```text
one JavaWorkerManager for each non-empty configured WorkerGroup
-> start its fixed replica set
-> load persisted workerId, or Register and persist it
-> Bind workerId as WEBSOCKET with the complete Properties snapshot
-> connect through the public Adapter URI returned by Bind
-> return without waiting for initial Adapter verification
-> apply configured Property Index updates as best effort
```

An empty Group owns no Manager. Every Manager owns one bounded daemon Platform
shared only by its replicas. Preparation or endpoint termination stops that
Worker until an explicit later Host start; Scenario does not expose Manager
reconciliation. Index updates may wait for Worker identity within the existing
connect timeout and otherwise log `14010` and skip.

`close()` closes Managers in reverse group order and leaves every Worker JSON
unchanged. Persistent Worker means stable identity, Properties, index requests,
and replica topology across Server restarts; it does not persist Endpoint URI,
Binding, Channels, connection state, Commands, Results, Tasks, or scores. File
edits take effect only on the next process start. One Lab root supports one
Scenario Server process.

The module depends only on Worker Core, Java Worker, the shared transport
contract, and its finite capability libraries. It has no Kernel, Spring,
Server, Adapter implementation, Redis, score, Pacer, reflection, or
`ServiceLoader` dependency.

```text
./gradlew :scenario_workers_jvm:test
```

The repository-level acceptance in
[`integrations/worker-capability-rpc`](../integrations/worker-capability-rpc/)
starts Redis, Python Kernel, Server, Adapter, and the Lab Workers, then proves
20 persistent, globally unique identities and 60 successful Group-scoped RPC
results. RPC rows deliberately do not claim which Worker executed them.
