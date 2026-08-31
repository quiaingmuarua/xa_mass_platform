# XA Mass Scenario Workers JVM

`scenario_workers_jvm` is the finite standalone Java 21 Worker Host used with
the checked-in `scenario-workers` Server profile. It is a local Worker Lab, not
a Kernel owner, privileged Server extension, Adapter, production Worker
platform, or plugin SPI. Server has no compile-time or lifecycle dependency on
this module.

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

The supported process entry is:

```powershell
.\gradlew.bat :scenario_workers_jvm:runScenarioWorkers `
  --args="--runtime-api-base-url=http://127.0.0.1:18082 `
  --sandbox-root=D:\proof\data\scenario-workers `
  --control-port=18086 --initial-workers=all"
```

`ScenarioWorkerHostMain` loads the checked
`default-capability-assembly.json`; callers cannot replace it with dynamic
classes, Spring configuration, Redis coordinates or Adapter URIs. The only
arguments are the Runtime API base URL, Lab root, loopback control port, and
initial Worker mode. `--control-port=18086` and `--initial-workers=all` are the
defaults. Port `0` selects an ephemeral test port; `initial-workers=none`
assembles the complete inventory without starting a Worker. The fixed assembly
declares the two locally hosted Groups, their concrete Event Definitions and
local reconnect options. It never lists individual Workers:

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

One standalone Host process exclusively owns this writable local directory:

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
Worker until an explicit later Host start. Prepare in that explicit start is
the only canonical Properties refresh. Each Prepare reopens the Worker's JSON
file, so an atomic file update does not affect the current run and becomes
visible on the next explicit start. A start requested while an earlier stop is
still converging is rejected with `409`; the caller must observe `STOPPED` and
retry instead of relying on an implicit restart.

The Host exposes a loopback-only JDK `HttpServer` control surface:

```text
GET    /lab/v1/workers
GET    /lab/v1/workers/{workerGroupId}/{clientWorkerKey}
PUT    /lab/v1/workers/{workerGroupId}/{clientWorkerKey}
POST   /lab/v1/workers/{workerGroupId}/{clientWorkerKey}:start
POST   /lab/v1/workers/{workerGroupId}/{clientWorkerKey}:stop
POST   /lab/v1/workers/{workerGroupId}/{clientWorkerKey}:schedule-stop
DELETE /lab/v1/workers/{workerGroupId}/{clientWorkerKey}:scheduled-stop
```

`PUT` accepts the complete schema-v2 state document and atomically replaces
only the already discovered Worker's file. It cannot introduce a path, Group,
or Worker. Filesystems that cannot provide `ATOMIC_MOVE` fail the write rather
than silently weakening this contract. `schedule-stop` accepts one
`delayMillis` in `1..86400000`; one
Host-wide daemon scheduler owns at most one nonpersistent plan per Worker.
These endpoints expose Lab desired/runtime state only. They do not claim
Adapter connectivity, Kernel score, or schedulability. The stable ready line is:

```text
SCENARIO_WORKER_LAB_READY control=http://127.0.0.1:<port> initialWorkers=<all|none>
```

`close()` closes Managers in reverse group order and leaves every Worker JSON
unchanged. Persistent Lab state means stable client keys, Properties, and
replica topology. With Server identity Redis retained, repeated Prepare maps
those coordinates back to the same Worker IDs; the files themselves do not
store IDs. The Lab does not persist Endpoint URI, Binding, Channels, connection
state, Commands, Results, Tasks, or scores. File edits take effect only on the
next explicit Worker start. One Lab root supports one Scenario Worker Host
process.

The module depends only on Worker Core, Java Worker, the shared transport
contract, and its finite capability libraries. It has no Kernel, Spring,
Server, Adapter implementation, Redis, score, Pacer, reflection, or
`ServiceLoader` dependency.

```text
./gradlew :scenario_workers_jvm:test
./gradlew :scenario_workers_jvm:installDist
```

Repository-level acceptance starts Redis, one Java Server and one independent
Scenario Worker Host. Server owns the Java Kernel Pacer applications and its
configured Adapter; the proof launcher owns the Worker Host process. Three
independent clients then prove the boundary:

- [`worker-fleet-acceptance`](../integrations/worker-fleet-acceptance/) proves
  the exact two-by-ten replica topology, schema-v2 Lab files, Runtime Preview
  client-key identity mapping, Adapter routes, probe execution, Properties
  observation, and identity reuse across a real standalone Host restart while
  Server, Pacer, Redis and Lab remain available;
- [`worker-capability-task`](../integrations/worker-capability-task/) proves two
  finite Tasks close 60 submitted Items across six Group/Event combinations to
  60 uniquely correlated exported success Results;
- [`worker-lab-reliability`](../integrations/worker-lab-reliability/) controls
  four fixed replicas and proves explicit stop/restart, scheduling
  serviceability convergence, next-Prepare Properties refresh, Group isolation,
  and eventual completion of a previously unserviceable Task.

Capability Task evidence deliberately does not claim which Worker executed an
Item. Fleet acceptance does not freeze dynamic Properties or business Result
payloads.
