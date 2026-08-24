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

Capability implementations register short names such as
`phonenumber.e164`; `WorkerEventDefinition.extension(...)` stores the full
`extension.worker.phonenumber.e164` Event Name. TaskItem `eventCode`, Control
Call `messageType`, and WorkerGroup `eventCodes` always carry that full name.

The only public assembly surface is:

```java
ScenarioWorkers workers = ScenarioWorkers.fromJson(
        capabilityAssemblyJson,
        "data/scenario-workers",
        URI.create("http://127.0.0.1:18082")
);
workers.start();
workers.close();
```

The `capabilityAssemblyJson` document declares locally hosted Groups, their
concrete Event Definitions, and local runtime options only. It never lists
individual Workers:

```json
{
  "scenario-phone-number-workers": {
    "eventCodes": [
      "extension.worker.phonenumber.e164",
      "extension.worker.phonenumber.country",
      "extension.worker.phonenumber.original-carrier"
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
are required and unknown fields are rejected. The omitted request timeout uses
10 seconds. The old inline `workers`, `sandboxDirectory`, retry-policy, and
Adapter URI fields are rejected.

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
ignored. There is no flag file, template version, default-set merge, file
watcher, or multi-process lock. The only file-schema transition is the bounded
v1-to-v2 migration described below.

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
  "schemaVersion": 2,
  "workerProperties": {
    "runtime": "java",
    "region": "local"
  }
}
```

`schemaVersion` must be integer `2`; `workerProperties` defaults to `{}` and
unknown fields are rejected. A legal v1 file is read once, preserves its
Properties, drops its former `workerId`, and is atomically rewritten as v2. The
filename and parent directory are the only client-key and group coordinates,
so those values are not duplicated inside the JSON. Scenario never persists a
platform-issued Worker ID; the Server identity registry resolves it from the
Group and client key on every explicit Worker start.

## Runtime lifecycle

`start()` initializes or opens the Lab, preflights every configured Group, and
then performs:

```text
one JavaWorkerManager for each non-empty configured WorkerGroup
-> start its fixed replica set
-> Prepare once with Group, client key and complete Properties
-> receive workerId and WEBSOCKET Endpoint
-> connect through the public Adapter URI returned by Prepare
-> return without waiting for initial Adapter verification
```

An empty Group owns no Manager. Every Manager owns one bounded daemon Platform
shared only by its replicas. Preparation or endpoint termination stops that
Worker until an explicit later Host start; Scenario does not expose Manager
reconciliation. Prepare in that explicit start is the only canonical
Properties refresh; file edits and live Provider changes otherwise wait for the
next process start.

`close()` closes Managers in reverse group order and leaves every Worker JSON
unchanged. Persistent Lab state means stable client keys, Properties, and
replica topology. With Server identity Redis retained, repeated Prepare maps
those coordinates back to the same Worker IDs; the files themselves do not
store IDs. The Lab does not persist Endpoint URI, Binding, Channels, connection
state, Commands, Results, Tasks, or scores. File edits take effect only on the
next process start. One Lab root supports one Scenario Server process.

The module depends only on Worker Core, Java Worker, the shared transport
contract, and its finite capability libraries. It has no Kernel, Spring,
Server, Adapter implementation, Redis, score, Pacer, reflection, or
`ServiceLoader` dependency.

```text
./gradlew :scenario_workers_jvm:test
```

Repository-level acceptance starts Redis and one Java Server. Server supervises
the temporary Python Pacer CLI and assembles the Adapter plus these Lab Workers;
two independent clients then prove the boundary:

- [`worker-fleet-acceptance`](../integrations/worker-fleet-acceptance/) proves
  the exact two-by-ten replica topology, schema-v2 Lab files, Runtime Preview
  client-key identity mapping, Adapter routes, probe execution, Properties
  observation, and identity reuse across a real Server/Scenario Host restart;
- [`worker-capability-task`](../integrations/worker-capability-task/) proves two
  finite Tasks close 60 submitted Items across six Group/Event combinations to
  60 uniquely correlated exported success Results.

Capability Task evidence deliberately does not claim which Worker executed an
Item. Fleet acceptance does not freeze dynamic Properties or business Result
payloads.
