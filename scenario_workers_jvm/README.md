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
  --control-port=18086 `
  --startup-plan=D:\proof\startup-plan.json"
```

`ScenarioWorkerHostMain` loads the checked
`default-capability-assembly.json`; callers cannot replace it with dynamic
classes, Spring configuration, Redis coordinates or Adapter URIs. The only
arguments are the Runtime API base URL, Lab root, loopback control port, and an
optional strict startup plan. `--control-port=18086` is the default. Port `0`
selects an ephemeral test port. When no startup plan is supplied, all discovered
Workers start, preserving ordinary Fleet and Capability behavior. The fixed assembly
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

The optional startup plan is validated completely before any replica starts:

```json
{
  "schemaVersion": 1,
  "initialWorkers": [
    {
      "workerGroupId": "scenario-string-utils-workers",
      "labWorkerKey": "scenario-string-utils-worker-a.jsonl:1"
    }
  ],
  "scheduledStops": [
    {
      "workerGroupId": "scenario-string-utils-workers",
      "labWorkerKey": "scenario-string-utils-worker-a.jsonl:1",
      "delayMillis": 5000
    }
  ]
}
```

Coordinates must name the discovered inventory, duplicates are rejected, and a
startup scheduled stop may reference only an initial Worker. The plan owns only
this process's initial desired state and startup stop schedule. It does not own
Properties, Worker identity, Tasks, Adapter state, or Kernel expectations.

## Persistent Worker Lab

One standalone Host process exclusively owns this writable local directory:

```text
data/scenario-workers/
├── scenario-phone-number-workers/
│   ├── scenario-phone-number-worker-a.jsonl
│   └── scenario-phone-number-worker-b.jsonl
└── scenario-string-utils-workers/
    ├── scenario-string-utils-worker-a.jsonl
    └── scenario-string-utils-worker-b.jsonl
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
reset obtained by deleting a configured Group directory or the complete Lab
root; old Worker file layouts are not migrated.

The Lab root must end in `data/scenario-workers` and must not pass through a
symbolic link. Only direct, non-symlink `*.jsonl` children of configured
Group directories are discovered. Files are sorted by name; each group is
bounded to 100 Worker records, and each file contains `1..100` records. Any
discovered invalid file fails aggregate startup
before a Manager or network Client is created.

Each physical line is one complete persistent local snapshot. Immutable
`labInventoryKey` and `labInventoryLine` Properties must match its filename and
one-based physical line. `<filename>:<line>` is the Lab-local `labWorkerKey`
used by the control API; it is not `clientWorkerKey` or a general Transport
identity field. The parent directory supplies the configured `workerGroupId`:

```json
{"schemaVersion":2,"workerProperties":{"labInventoryKey":"scenario-string-utils-worker-a.jsonl","labInventoryLine":1,"runtime":"java","labSlot":1}}
{"schemaVersion":2,"workerProperties":{"labInventoryKey":"scenario-string-utils-worker-a.jsonl","labInventoryLine":2,"runtime":"java","labSlot":2}}
```

Blank lines, comments, multi-line objects, missing `workerProperties`, schema
versions other than integer `2`, and unknown fields are rejected. A Lab PUT
validates the complete file, replaces only the selected line, preserves every
other physical line and line count, then atomically replaces the file. A PUT
cannot change either inventory field. Scenario never persists a
platform-issued Worker ID; `workerKind=SCENARIO_LAB` tells Server to derive its
private registration coordinate from Group plus the inventory fields. Mutable
Properties such as `labSlot` do not participate in identity.

## Runtime lifecycle

`start()` initializes or opens the Lab, preflights every configured Group, and
then performs:

```text
one JavaWorkerManager for each non-empty configured WorkerGroup
-> group the selected initial replicas by inventory file
-> batch Prepare each file's selected records, at most 100 per call
-> inject each returned workerId and WEBSOCKET Endpoint into its replica
-> connect through the public Adapter URI returned by Prepare
-> return without waiting for initial Adapter verification
```

An empty Group owns no Manager. Every Manager owns one bounded daemon Platform
shared only by its replicas. Preparation or endpoint termination stops that
Worker until an explicit later Host start. Prepare in that explicit start is
the only canonical Properties refresh. Initial file batches and later
one-record batches both reopen the Worker's inventory file, so an atomic file
update does not affect the current run and becomes
visible on the next explicit start. A start requested while an earlier stop is
still converging is rejected with `409`; the caller must observe `STOPPED` and
retry instead of relying on an implicit restart.

The control surface does not promise idempotent orchestration or eventual
completion of an accepted request. It reports the immediate local Lab snapshot;
callers may observe later local state, but the Host does not retry, compensate,
or reconcile an operation until a preferred external projection appears. A
failed or ambiguous operation is simply not a valid mutation anchor for a
convergence proof.

The Host exposes a loopback-only JDK `HttpServer` control surface:

```text
GET    /lab/v1/workers
GET    /lab/v1/workers/{workerGroupId}/{labWorkerKey}
PUT    /lab/v1/workers/{workerGroupId}/{labWorkerKey}
POST   /lab/v1/workers/{workerGroupId}/{labWorkerKey}:start
POST   /lab/v1/workers/{workerGroupId}/{labWorkerKey}:stop
POST   /lab/v1/workers/{workerGroupId}/{labWorkerKey}:schedule-stop
DELETE /lab/v1/workers/{workerGroupId}/{labWorkerKey}:scheduled-stop
PUT    /lab/v1/workers/{workerGroupId}/{labWorkerKey}:command-checkpoint
GET    /lab/v1/workers/{workerGroupId}/{labWorkerKey}:command-checkpoint
DELETE /lab/v1/workers/{workerGroupId}/{labWorkerKey}:command-checkpoint
```

`PUT` accepts the complete schema-v2 state document and atomically replaces
only the already discovered Worker's file. It cannot introduce a path, Group,
or Worker. Filesystems that cannot provide `ATOMIC_MOVE` fail the write rather
than silently weakening this contract. `schedule-stop` accepts one
`delayMillis` in `1..86400000`; one
Host-wide daemon scheduler owns at most one nonpersistent plan per Worker.
The command checkpoint is a String-Worker-only reliability fixture for
`extension.worker.lab.checkpoint`. One opaque token can hold one target Handler
for at most 120 seconds; release, timeout, or Host close opens the gate. It is
not a Core hook, generic fault DSL, Worker identity context, or production
control event. These endpoints expose Lab desired/runtime state only. They do not claim
Adapter connectivity, Kernel score, or schedulability. The stable ready line is:

```text
SCENARIO_WORKER_LAB_READY control=http://127.0.0.1:<port> initialWorkerCount=<n> scheduledStopCount=<n>
```

The same loopback control server exposes a dependency-free local console at:

```text
http://127.0.0.1:<control-port>/lab
```

The console lists the fixed Lab inventory and delegates single-Worker start,
stop, scheduled stop, and complete schema-v2 Properties replacement to the
APIs above. Its desired/runtime fields are only local Host state; the page does
not claim Adapter connectivity, Kernel score, or schedulability. Automatic
list refresh never reloads a Properties document while it is being edited.
The loopback server uses a bounded four-thread control executor. A slow
single-Worker Prepare does not hold the Scenario inventory monitor, allowing a
stop or local snapshot request to reach its independent replica. This is
control-plane responsiveness, not a claim that Lab actions are transactional
or distributed truth.

`close()` closes Managers in reverse group order and leaves every Worker JSON
unchanged. Persistent Lab state means stable Lab Worker keys, Properties, and
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
  Lab-coordinate identity mapping, Adapter routes, probe execution, Properties
  observation, and identity reuse across a real standalone Host restart while
  Server, Pacer, Redis and Lab remain available;
- [`worker-capability-task`](../integrations/worker-capability-task/) proves two
  finite Tasks close 60 submitted Items across six Group/Event combinations to
  60 uniquely correlated exported success Results;
- [`worker-lab-reliability`](../integrations/worker-lab-reliability/) owns three
  isolated convergence lanes: Worker state propagation, execution-time Host
  loss with Task recovery, and a finite seeded multi-round campaign. The Lab is
  only the mutation source and local witness; the Harness compares established
  local facts with independent Adapter, Kernel, and Task observations without
  repairing the Lab into an expected final world.

Capability Task evidence deliberately does not claim which Worker executed an
Item. Fleet acceptance does not freeze dynamic Properties or business Result
payloads.
