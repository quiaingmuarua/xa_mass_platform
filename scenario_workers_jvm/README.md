# XA Mass Scenario Workers JVM

`scenario_workers_jvm` is the finite standalone Java 21 Worker Host for the
default Lab and the [SMS business workload](../products/sms-reception/README.md).
It has one process entry, one loopback control server and one HTML console.
The Lab uses the checked-in `scenario-workers` Server profile. It is not
a Kernel owner, privileged Server extension, Adapter, production Worker
platform, or plugin SPI. Server has no compile-time or lifecycle dependency on
this module.

The module owns the checked-in phone-number and string-utility
`WorkerEventDefinition` extensions. A configured WorkerGroup selects one
immutable extension list through its local `eventCodes`; every discovered
Worker in that group shares the same stateless or thread-safe Handler instances.
SMS reporting Handlers instead capture their actual number; the common Manager
assembly installs these per-replica Definitions alongside shared string events.
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
  --capability-assembly=D:\proof\capability-assembly.json `
  --startup-plan=D:\proof\startup-plan.json"
```

Without `--capability-assembly`, `ScenarioWorkerHostMain` loads the checked
`default-capability-assembly.json`. A caller may instead supply one strict
assembly JSON file to select a finite subset of the Host's compiled Groups,
Event Definitions, request timeout, and reconnect policy. It cannot name
dynamic classes, Spring configuration, Redis coordinates or Adapter URIs. The
other arguments are the Runtime API base URL, Lab root, loopback control port,
and an optional strict startup plan. `--control-port=18086` is the default.
Port `0` selects an ephemeral test port. When no startup plan is supplied, all
discovered Workers start, preserving ordinary Fleet and Capability behavior.
An assembly never lists individual Workers:

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
      "labWorkerKey": "workers-000.jsonl:1"
    }
  ],
  "scheduledStops": [
    {
      "workerGroupId": "scenario-string-utils-workers",
      "labWorkerKey": "workers-000.jsonl:1",
      "delayMillis": 5000
    }
  ]
}
```

Coordinates must name the discovered inventory, duplicates are rejected, and a
startup scheduled stop may reference only an initial Worker. The plan owns only
this process's initial desired state and startup stop schedule. It does not own
Properties, Worker identity, Tasks, Adapter state, or Kernel expectations.

## Messages and shared products

`--scenario=messages --device-counts=20,20,20` creates generated message senders;
`--scenario=products` installs SMS and Messages on the same replicas. Country
Groups are respectively `messages-cn/us/gb` and `demo-cn/us/gb`, declared by
distribution before Host startup. The shared Manager collection and HTTP server
remain the only Host assembly. Properties additionally contain
`messaging.enabled="true"` and enter Matching through the existing SDK/Adapter path.
Use the [shared launcher](../distribution/product-preview/README.md); Lab and SMS
entrypoints retain their behavior. Messages adds no timer, watcher or replay loop.

`extension.worker.message.send` is the only way to create recipient records.
It checks the five-field campaign/message/country/recipient/body contract and
deduplicates stable message IDs across Workers. A duplicate returns the retained
snapshot and preserves the first Reporter. Conflicting content is rejected.
The channel caps storage at 50 campaigns and 50,000 messages; it stores latest
reply only, with at most 100,000 reply operation fingerprints per run.

| Messages Host API | Local contract |
| --- | --- |
| `GET /lab/v1/messages/health` | Prepared identities and Host lifecycle |
| `GET /lab/v1/messages/inventory` | Group/replica/country/phone/actual Worker, paged 1..1000 |
| `GET /lab/v1/messages/records` | Only actually sent messages, paged 1..1000 |
| `GET /lab/v1/messages/metrics` | Capacity, held receipts, publication and dedup counts |
| `POST /lab/v1/messages/{id}:deliver` | Empty object; commit delivery before publication |
| `POST /lab/v1/messages/{id}:read` | Empty object; delivery required |
| `POST /lab/v1/messages/{id}:reply` | `{requestId,text}`, 128/4096 character bounds; delivery required; multiple replies |
| `POST /lab/v1/messages/receipts:hold` | `{enabled}`; default false, no automatic flush |
| `POST /lab/v1/messages/receipts:release` | 1..10,000 existing receiptIds in caller order; duplicates preserve original time/content |
| `POST /lab/v1/messages/workers/{group}/{replica}:start` | Start one local replica |
| `POST /lab/v1/messages/workers/{group}/{replica}:stop` | Revoke both products' Reporter associations before SDK stop |

Bodies are bounded to 1,000,000 bytes. Actions commit complete immutable snapshots
under the channel gate, then call the original Reporter outside it with strictly
increasing milliseconds. Later tags 7/8/9 represent delivery/read/reply. Repeating
an action does not republish it; same reply operation with changed content is
rejected. Responses describe persistence and local send admission, never remote ACK.
Held receipts are capped at 10,000 and can only be released once (including repeated
IDs within that release). They cannot inject tag, forward or arbitrary payload.
Send failure leaves the local business fact intact without retry or compensation.

Stopping a Worker clears its associated Reporters. Records remain readable and
can accept local actions after restart, but the new run cannot publish those old
messages. The product retains its last actual observation. `/lab` shows a paged
recipient inbox and, in products mode, the existing SMS input panel alongside it.
No simulated input calls the Messages Backend or invents product Results.

## SMS Scenario

`--scenario=lab` is the default and preserves the Lab CLI and inventory contract.
`--scenario=sms` selects generated CN, US and GB pools without opening Lab files:

```powershell
.\gradlew.bat :scenario_workers_jvm:runScenarioWorkers `
  --args="--scenario=sms --sms-counts=20,20,20 --runtime-api-base-url=http://127.0.0.1:18390 --control-port=18394"
```

Start the Server with its `sms-reception` profile first. The product startup
registers `sms-cn`, `sms-us` and `sms-gb`; the Host never registers Groups.
Counts must be three positive integers totaling at most 10,000. SMS rejects
`--sandbox-root`, `--capability-assembly` and `--startup-plan`; the Lab rejects
`--sms-counts`. Both modes use the same main, Manager collection and cleanup.
Each nonempty Group has one JavaWorkerManager. SMS keeps ordinary CLIENT_KEY
Prepare, keys `CN-0`, `US-0`, `GB-0` and the existing deterministic phone numbers.
Lab file coordinates and its SCENARIO_LAB batch preparation remain separate.

SMS Properties contain phone, country, operator and simulated strings and are
read-only in the console. SDK preparation still supplies its registration key.
Each number installs SMS start/cancel plus the existing MD5, SHA1 and Base64
events. Neither file-based Lab faults nor checkpoints are installed in SMS.
Actual `platform.worker.events.snapshot` evidence identifies loaded handlers;
Group catalog metadata is not the execution oracle.

The single `/lab` page selects the scene at startup. In SMS mode it shows local
Worker state, number inventory, explicit Worker start/stop, raw SMS injection
and finite traffic controls. It does not manage product orders or infer routing
or schedulability. Existing Lab control routes are registered only in Lab mode.
The HTTP executor has 8 threads and 128 queued requests with caller backpressure;
SMS bodies are at most 8 KiB, while existing Lab body limits remain unchanged.

| SMS Host route | Contract |
| --- | --- |
| `GET /lab/v1/sms/health` | Host startup, prepared identity count and number count; no Adapter readiness claim |
| `GET /lab/v1/sms/inventory?offset=0&limit=100` | Paged Group, replica key, country, phone, actual Worker ID, desired/local state and active subscriptions; limit 1..1000 |
| `GET /lab/v1/sms/metrics` | Local matching, dedup, capacity and traffic observations |
| `GET /lab/v1/sms/records?offset=0&limit=100` | Bounded acceptance evidence, never a product Result source |
| `POST /lab/v1/sms/sms` | Raw `{phone, smsId, text}` input; text at most 1024 characters |
| `POST /lab/v1/sms/traffic/start` | Existing finite `{ratePerSecond, durationSeconds}` input |
| `POST /lab/v1/sms/traffic/stop` | Stop the finite stimulus stream |
| `POST /lab/v1/sms/workers/{groupId}/{replicaKey}:start` | Request a local Worker run; no properties mutation |
| `POST /lab/v1/sms/workers/{groupId}/{replicaKey}:stop` | Close number admission and request SDK stop; HTTP 202 acknowledges local control only |

SMS owns one process-wide ListeningRegistry and one expiry/traffic clock.
The [business contract](../products/sms-reception/README.md#监听和分发契约)
owns templates, dedup and resource limits. Constructors start no timer. Lab mode
creates no SMS clock or routes; SMS creates no Lab scheduled-stop executor.

Stopping a number closes new admission, ends active subscriptions locally as
INTERRUPTED and drops their Reporters under the number gate. SDK stop and network
publication happen outside that gate; stop does not wait for a slow start or
Report send. A stopped SDK snapshot is also cleaned by the existing SMS clock.
Transparent reconnect of a RUNNING run retains its subscriptions. This local
fault action sends neither a product cancellation command nor a synthetic ending
Report. Without actual ending evidence, the product reaches UNCONFIRMED at its
existing deadline. A previously won match is never reclassified by stop.

Explicit restart retains the number/identity and the process-wide dedup history,
but does not restore old subscriptions. Duplicate commands return the retained
snapshot without transferring the original Reporter. Already-admitted SDK
callbacks may finish late; their Reporters remain bound to the original Task/run.
There is no new replay, automatic restart, or callback cancellation guarantee.
Shutdown stops HTTP admission and timers, cleans SMS state, revokes Workers and
then waits boundedly for resources. Partial assembly failures close created
Managers; Server never owns this Host lifecycle.

## Actual Execution Witness

The optional `extension.worker.lab.execution-witness` capability is selected only
by an explicit capability assembly. Each replica receives its own immutable
Handler Definition through `JavaWorkerManager.Builder.replica`. The closure
captures the configured WorkerGroup and actual Lab replica key at construction;
neither the caller's token nor Task targeting supplies this execution identity.
Shared Group capabilities still use the existing common Definitions.

The input is exactly `{ "probeToken": "...", "delayMillis": 1000 }`: a nonblank
token of at most 256 characters and an integer delay in 0..30000 ms. The Handler
records ENTERED, waits outside the journal lock, records COMPLETED and returns
JSON null through the ordinary Worker Result path. An interrupted wait records
FAILED. This capability creates no thread, scheduler or runtime registration.

The process-local journal retains at most 655,360 immutable records. Overflow is
sticky and explicit; it fails the invocation without eviction or reset. The
loopback-only `GET /lab/v1/execution-witnesses?after=0&limit=100` returns
`records`, `nextCursor` and `overflowed`. Sequence numbers start at one, each
attempt ID is its entry sequence, and completion carries the same actual
Group/replica/token. Cursors are 0..current size and page limits are 1..100.
Invalid queries return 400 and non-GET methods return 405.

[Worker Dynamic Matching](../integrations/worker-dynamic-matching/README.md)
uses this independent execution witness together with public Task Results.
Its journal and correlation tokens remain private proof data, outside CI
artifacts. The capability is absent from the default Lab assembly.

## Persistent Worker Lab

One standalone Host process exclusively owns this writable local directory:

```text
data/scenario-workers/
├── scenario-phone-number-workers/
│   └── workers-000.jsonl
└── scenario-string-utils-workers/
    └── workers-000.jsonl
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

The checked default world is two Groups with 50 records each. Each Group is
seeded as one `workers-000.jsonl` file, so initial Batch Prepare runs as one
50-record request per Group. Existing local Group directories are intentionally
not upgraded; delete `data/scenario-workers` to opt into the new default world.

The Lab root must end in `data/scenario-workers` and must not pass through a
symbolic link. Only direct, non-symlink `*.jsonl` children of configured
Group directories are discovered. Files are sorted by name; each group is
bounded to 15,000 Worker records, and each file contains `1..100` records. Any
discovered invalid file fails aggregate startup
before a Manager or network Client is created.

Each physical line is one complete persistent local snapshot. Immutable
`labInventoryKey` and decimal-string `labInventoryLine` Properties must match
its filename and one-based physical line. All Properties values are strings;
only the outer `schemaVersion` remains numeric. `<filename>:<line>` is the Lab-local `labWorkerKey`
used by the control API; it is not `clientWorkerKey` or a general Transport
identity field. The parent directory supplies the configured `workerGroupId`:

```json
{"schemaVersion":2,"workerProperties":{"labInventoryKey":"workers-000.jsonl","labInventoryLine":"1","runtime":"java","capability":"string-utils","region":"local","labSlot":"1","convergenceSlot":"A"}}
{"schemaVersion":2,"workerProperties":{"labInventoryKey":"workers-000.jsonl","labInventoryLine":"2","runtime":"java","capability":"string-utils","region":"local","labSlot":"2","convergenceSlot":"A"}}
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

Batch Prepare supplies Server-owned identity coordinates and establishes access;
it does not store Matching Properties. Initial and subsequent Properties reach
Matching only through Adapter observations and Server admission. Startup does
not wait for that best-effort publication. A proof that needs current Matching
facts must observe their arrival independently of the local start result.

An empty Group owns no Manager. Every Manager owns one bounded daemon Platform
shared only by its replicas. Preparation or endpoint termination stops that
Worker until an explicit later Host start. Initial file batches and later
one-record batches reopen the Worker's inventory file. The ordinary file PUT
remains a file-only edit: it does not publish to the current connection and is
loaded on the next explicit start (or a later explicit full Properties report).
The separate live Properties operations below persist and publish during an
existing run, without Prepare. A start requested while an earlier stop is
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
POST   /lab/v1/workers:stop
GET    /lab/v1/workers/{workerGroupId}/{labWorkerKey}
PUT    /lab/v1/workers/{workerGroupId}/{labWorkerKey}
PATCH  /lab/v1/workers/{workerGroupId}/{labWorkerKey}:properties
PUT    /lab/v1/workers/{workerGroupId}/{labWorkerKey}:properties
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
control event.

The `:properties` operations accept a direct string KV Map for one known,
running Worker. PATCH merges the supplied keys into the complete persisted
record, then calls the existing Manager incremental publication method for
`properties.updated`. PUT atomically replaces that record, then calls the
existing full publication method for `properties.replaced`; its Provider
reopens the file. Full replacement must retain both immutable inventory
coordinates exactly and deletes all omitted mutable keys. Empty strings remain
values. Neither operation starts, stops or prepares a Worker.

Each Worker has a non-queuing Properties operation gate, acquired before the
inventory monitor and retained through the SDK send. The ordinary file PUT
uses the same gate. File read/modify/replace retains the existing serialized
protection so other records in the same JSONL file survive concurrent edits.
The SDK send runs outside the inventory monitor; stop/shutdown may revoke the
run while publication is in progress. Closing the control listener interrupts
its daemon handlers without joining a publication before Worker shutdown.
Unknown Workers return 404, invalid
Properties return 400, and a stopped/stopping Worker or occupied gate returns
409. Successful local completion returns
`200 {"persisted":true,"sendAccepted":true|false}`. False leaves the file in
place, with no retry or compensation. This result is local send acceptance,
not an Adapter/Server ACK. There is no file watcher, automatic scan, generic
event injection or new Worker SDK API.

The collection-level stop endpoint accepts `1..100` unique existing Worker
coordinates. It validates the complete request before issuing any stop and
returns `202 {"acceptedCount":n}`. The call is a one-shot Lab mutation: the
Host does not retry or compensate a failed batch, and stopping a run does not
delete its Server-issued identity.

The Host also makes two finite background-fault Definitions available to an
explicit capability assembly:

```text
extension.worker.lab.delay  {"delayMillis":1..30000}
extension.worker.lab.fail   {}
```

Delay occupies the current synchronous Handler path and returns one successful
Report after the requested interval. Fail produces the ordinary Worker `3303`
execution-failure Report without stopping the Worker or its connection. They
have no probability, scheduler, mutable fault state or HTTP control surface and
are not selected by the default capability assembly. Worker Convergence Health
selects them only for its String Group background workload.

These endpoints expose Lab desired/runtime state only. They do not claim
Adapter connectivity, Kernel score, or schedulability. The stable ready line is:

```text
SCENARIO_WORKER_LAB_READY control=http://127.0.0.1:<port>/lab initialWorkerCount=<n> scheduledStopCount=<n> scenario=<lab|sms|messages|products>
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
The loopback server uses the shared eight-thread executor with 128 waiting tasks. A slow
single-Worker Prepare does not hold the Scenario inventory monitor, allowing a
stop or local snapshot request to reach its independent replica. This is
control-plane responsiveness, not a claim that Lab actions are transactional
or distributed truth. The Host binds the configured control listener before
starting outbound Worker connections, but starts serving requests only after
the initial startup plan completes. The listener address therefore remains
reserved even when the loaded-recovery proof widens the OS ephemeral port
range.

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

Repository-level proofs start Redis, one Java Server and one independent
Scenario Worker Host. Server owns the Java Kernel Pacer applications and its
configured Adapter; each proof runner owns the Worker Host process:

- [`worker-correctness`](../integrations/worker-correctness/) proves the exact
  two-by-fifty topology, Lab-coordinate identity mapping, Adapter routes,
  extension reachability, 100 final Results and identity reuse across a real
  Host restart;
- [Worker Dynamic Matching](../integrations/worker-dynamic-matching/README.md)
  proves loaded PRECOMPUTED execution follows live Worker and Platform facts,
  with actual replica witnesses and independent Result closure;
- [`worker-convergence-health`](../integrations/worker-convergence-health/)
  owns two isolated 2x500 scenarios: deterministic Worker/Server state
  convergence and execution-time Host loss with Task recovery/finality. The
  Lab remains only the mutation source and local witness; the Harness compares
  established local facts with independent Adapter, Kernel and Task
  observations.
- [Worker Loaded Capacity + Recovery Stability](../integrations/worker-loaded-recovery/)
  generates
  one 15,000-record Group, stops a deterministic 5,000-run subset during load,
  and proves the retained 10,000-Worker world can drain four 50,000-Item
  workloads across one graceful and two hard Server restarts while resources
  remain stable. It is a nightly/manual proof, not part of the fixed default
  Lab inventory.

Worker Correctness deliberately does not claim which Worker executed an Item
or freeze capability-specific Result values.
