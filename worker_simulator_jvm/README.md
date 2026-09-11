# XA Mass Worker Simulator JVM

`worker_simulator_jvm` is a finite Java 21 device and business simulator using
the real Java Worker SDK. Lab, [SMS](../products/sms-reception/README.md) and
[Messages](../products/message-campaigns/README.md) compose capabilities on the
same file inventory, Properties, Managers and controls; scenario is a use case,
not a separate kind of Worker.
It has one process entry, one loopback control server and one HTML console.
The Lab uses the checked-in `scenario-workers` Server profile. It is not
a Kernel owner, privileged Server extension, Adapter, production Worker
platform, or plugin SPI. Server has no compile-time or lifecycle dependency on
this module.

The module owns the checked-in phone-number and string-utility
`WorkerEventDefinition` extensions. A configured WorkerGroup selects one
immutable extension list through its local `events`; every discovered
Worker in that group shares the same stateless or thread-safe Handler instances.
SMS/Messages reporting Handlers capture their stable replica binding and read its
current immutable Properties. The common Manager installs per-replica Definitions
alongside shared tools and selected checkpoint/witness capabilities.
The WorkerGroup catalog projection remains a separate Server-owned value and
may lag this local list.

Capability implementations register short names such as
`phonenumber.e164`; `WorkerEventDefinition.extension(...)` stores the full
`extension.worker.phonenumber.e164` Event Name. TaskItem `eventCode`, Control
Call `messageType`, and WorkerGroup `eventCodes` always carry that full name.

## One configuration, one entry

The installed entry is:

```text
xa-mass-worker-simulator --config simulator.json
```

From a checkout:

```powershell
.\gradlew.bat :worker_simulator_jvm:runWorkerSimulator --args="--config worker_simulator_jvm/config/lab.json"
```

`WorkerSimulatorMain` accepts only `--config <path>`, `--config=<path>` and
`--help`. There are no CLI overrides, implicit configuration resources,
separate capability files or separate startup-plan files. The JSON is read once;
omitted settings resolve to built-in defaults before the Host receives a complete
configuration. Unknown fields, duplicate JSON keys and invalid explicit values
(including null) fail startup, rather than silently using defaults.

Ordinary startup only needs to select Groups, optionally overriding their counts:

```json
{
  "workerGroups": {
    "demo-sim": {
      "count": 100
    }
  }
}
```

Only `workerGroups` is required; an empty object is legal and starts no Groups.
There are no implicit additional Groups. Root defaults are:

| Setting | Default |
| --- | --- |
| `runtimeApiBaseUrl` | `http://127.0.0.1:18082` |
| `sandboxRoot` | `./data/scenario-workers` |
| `controlPort` | `18086` |
| `startupPlan` | Start all discovered inventory, no scheduled stops |

Relative `sandboxRoot` paths resolve against the configuration file's directory,
including the default path, not the process working directory. The Runtime API URL must be absolute HTTP(S).
The control listener is loopback-only; port `0` selects an ephemeral test port.

An empty configuration object is sufficient for any of the three built-in Groups.
`count` is optional (0..15,000): `demo-sim` defaults to 60, all other Groups to 50.
`propertiesTemplate` is optional for built-in Groups and uses the local defaults
below. `events` defaults to `[]`, and `newEnvironment` defaults to false.
Optional `requestTimeoutMillis` defaults to 10,000. Optional `reconnectPolicy`
defaults to `maxUnstableAttempts=20`, `reconnectIntervalMillis=500`,
`stableConnectionDurationMillis=10000`; each field can be overridden independently,
and `{}` uses all three defaults. Explicit values must remain positive. These are
Group settings, not a second override layer.

Nonempty `events` selects exact compiled extension names and rejects duplicates
or unknown names. Empty `events` uses a finite local Group binding:

| Group | Local default extensions | Default initialization Properties |
| --- | --- | --- |
| `scenario-phone-number-workers` | E.164, country and original-carrier tools | `runtime=java`, `capability=libphonenumber`, `region=local`, sequential `labSlot` from 1, `convergenceSlot=A` |
| `scenario-string-utils-workers` | String tools and command checkpoint | Same Lab fields, with `capability=string-utils` |
| `demo-sim` | SMS, Messages and shared string tools | `runtime=java`, sequential `phone` from 861700000001, cyclic `country=CN/US/GB`, `operator=Preview SIM`, `simulated=true`, `messaging.enabled=true` |

Other Groups must specify extensions and a template explicitly (an empty template
is legal if the selected capabilities require no Properties). Group defaults are
local; the default `demo-sim` template includes `messaging.enabled=true` only when
Messages is actually selected by the effective events. An explicit template remains
untouched, including any capability Properties the caller chooses. Delay, fail and execution-witness
remain explicit choices. SDK platform events remain installed independently.
Server `eventCodes` is catalog metadata, never the source of live Handlers.
SMS/Messages Owners and console panels are created from installed capabilities,
not from a runtime scenario or product mode.

The checked-in [Lab](config/lab.json), [SMS](config/sms.json),
[Messages](config/messages.json) and [combined products](config/products.json)
files are standalone examples, not preset loaders. Lab is a minimal Group-only
configuration and initializes two Groups of 50 Workers. The product examples
retain explicit templates for Preview's configurable country distribution and
select only `demo-sim`. Installation and Preview
archives include these files under `config`.

### Deterministic initialization templates

Templates are an optional customization surface, particularly for precise CI
fixtures. Omission selects the Group's default template; an explicitly supplied
template replaces it in full, with no implicit merge. `{}` generates no mutable
Properties, not default Properties. Capability validation still applies, so an
empty template cannot initialize number-capable Workers. For example, a complete
custom template for `demo-sim` can be:

```json
{
  "runtime": "java",
  "phone": {"$index": [861700000001, 1]},
  "country": {"$choice": ["CN", "US", "GB"]},
  "battery": {"$range": [80, 99]},
  "messaging.enabled": "true"
}
```

Each property value is a string or an object containing exactly one supported
operator. Let `i` be the one-based Group generation ordinal, continuous across files:

| Expression | Output |
| --- | --- |
| `{"$index":[start,step]}` | Decimal `start + (i - 1) * step`; step must be positive |
| `{"$range":[min,max]}` | Decimal integers cycling through the inclusive range |
| `{"$choice":["A","B",...]}` | Values cycling in supplied order; repeats retain their ratio |

Numeric arguments must be integral and fit signed 64-bit arithmetic; invalid
ranges and generation overflow fail before installation. Choice accepts a
nonempty array of strings. Literal empty strings are legal. No random values,
formatting, nested expressions, field references or runtime evaluation exist.
The inventory Owner adds schema-v2 and file coordinates;
`labInventoryKey`, `labInventoryLine` and `clientWorkerKey` cannot be supplied by
a template. Materialized Properties contain strings only.

### Embedded startup plan

Optional `startupPlan` is an object in the same configuration:

```json
{
  "initialWorkers": [
    {"workerGroupId":"scenario-string-utils-workers","labWorkerKey":"workers-000.jsonl:1"}
  ],
  "scheduledStops": [
    {"workerGroupId":"scenario-string-utils-workers","labWorkerKey":"workers-000.jsonl:1","delayMillis":5000}
  ]
}
```

Omitting the plan starts all inventory; explicit empty `initialWorkers` starts
none. There is no plan schema-version wrapper. Coordinates and duplicates are
validated against the entire effective inventory before committing generated
Groups or creating Managers. A scheduled stop must refer to an initial Worker.
The plan owns only initial local intent and startup stop timing, not Properties,
Worker identity, Tasks, Adapter state or Kernel expectations.

## Messages and shared products

`config/messages.json` selects message senders; `config/products.json` selects
SMS and Messages on the same replicas. Both examples use mixed-country `demo-sim`, declared by
distribution before Host startup. One Manager per nonempty Group and one HTTP server
form the common Host assembly. Properties additionally contain
`messaging.enabled="true"` and enter Matching through the existing SDK/Adapter path.
Use the [shared launcher](../distribution/product-preview/README.md). Messages adds no timer, watcher or replay loop.

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
recipient inbox; its receipt buttons open the common device input panel for that Worker.
No simulated input calls the Messages Backend or invents product Results.

## SMS Scenario

Start the Server with its `sms-reception` profile first, then use a complete
configuration based on [sms.json](config/sms.json) with the matching Runtime URL.
Product startup registers one mixed-country `demo-sim` Group; the Host never
registers Groups. The example generates CN/US/GB numbers deterministically.
All configurations use the same filename plus physical-line identity and the
existing `SCENARIO_LAB` batch Prepare. Product examples do not implicitly start
the Lab Groups. Existing inventory is reused regardless of count/template changes
unless that Group explicitly requests `newEnvironment=true`.

SMS Properties contain phone, country, operator and simulated strings and are
editable through common inventory and live Properties controls.
The SMS example selects start/cancel and MD5, SHA1 and Base64; explicit
`events` may add validation capabilities or Messages. Delay/fail are not defaults.
Actual `platform.worker.events.snapshot` evidence identifies loaded handlers;
Group catalog metadata is not the execution oracle.

The single `/lab` page always shows common Worker management, and additionally shows local
Worker state, number inventory, explicit Worker start/stop, device input
and finite traffic controls. It does not manage product orders or infer routing
or schedulability. All configurations expose the same common control routes.
The HTTP executor has 8 threads and 128 queued requests with caller backpressure;
SMS bodies are at most 8 KiB, while existing Lab body limits remain unchanged.

| SMS Host route | Contract |
| --- | --- |
| `GET /lab/v1/sms/health` | Host startup, prepared identity count and number count; no Adapter readiness claim |
| `GET /lab/v1/sms/inventory?offset=0&limit=100` | Paged Group, replica key, country, phone, actual Worker ID, desired/local state and active subscriptions; limit 1..1000 |
| `GET /lab/v1/sms/metrics` | Local matching, dedup, capacity and traffic observations |
| `GET /lab/v1/sms/records?offset=0&limit=100` | Bounded acceptance evidence, never a product Result source |
| `POST /lab/v1/sms/traffic/start` | Existing finite `{ratePerSecond, durationSeconds}` input |
| `POST /lab/v1/sms/traffic/stop` | Stop the finite stimulus stream |
| `POST /lab/v1/sms/workers/{groupId}/{replicaKey}:start` | Request a local Worker run; no properties mutation |
| `POST /lab/v1/sms/workers/{groupId}/{replicaKey}:stop` | Close number admission and request SDK stop; HTTP 202 acknowledges local control only |

SMS owns one process-wide ListeningRegistry and one expiry/traffic clock.
The [business contract](../products/sms-reception/README.md#监听和分发契约)
owns templates, dedup and resource limits. Constructors start no timer. The actual
installed capabilities determine whether the SMS clock/routes and Messages Owner
exist; the common scheduled-stop Owner is available to every configured Group.

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
by an explicit Group `events` list. Each replica receives its own immutable
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

Initialization belongs to the existing inventory Owner. Startup first parses the
complete configuration and resolves capabilities, then loads reused Groups and
stages all Groups requiring generation. It validates schema, capacity, number
requirements, cross-Group phone uniqueness and startup coordinates against this
effective world before changing any target directory.

- With `newEnvironment=false`, a missing Group is generated from count/template;
  an existing directory is reused exactly, even when empty. It is never topped up,
  truncated, repaired or overwritten.
- With `newEnvironment=true`, only that Group is regenerated on process startup.
  A later single-Worker start reloads its file and never runs the template.
- Generated files start at `workers-000.jsonl`, contain at most 100 records and
  retain continuous Group ordinals. Existing filenames and physical lines never
  change on reuse.

A replacement first moves the old, validated Group directory to a temporary
backup and installs the validated staged directory. Installation failure attempts
restoration and reports failure; a failed restoration retains the backup and
reports its path. Success removes that backup. Cleanup is restricted to validated
direct child directories and never recursively targets the inventory root or
another Group. There is no cross-Group transaction: later Prepare or network
failure does not roll back installed files.

The Lab example initializes two Groups with 50 records each, one file and one
initial 50-record Prepare per Group. Unconfigured directories are ignored.
“New environment” means local inventory only: no Redis cleanup, Binding reset or
new Worker identity is implied. Identical file coordinates may obtain the same
Server-issued IDs. Templates are not a Provider, and no template versions,
background synchronization, watcher or multi-process lock are introduced.

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
one-record starts explicitly reload the Worker's inventory file. The Provider and
business Handlers read one current immutable Map, not disk. HTTP file PUT updates
both the file and that local snapshot, but does not publish to the connection.
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
GET    /lab/v1/workers/{workerGroupId}/{labWorkerKey}:inputs
POST   /lab/v1/workers/{workerGroupId}/{labWorkerKey}:inputs
GET    /lab/v1/workers/{workerGroupId}/{labWorkerKey}:messages?offset=0&limit=100
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
The command checkpoint is an explicitly installed reliability fixture for
`extension.worker.lab.checkpoint`. One opaque token can hold one target Handler
for at most 120 seconds; release, timeout, or Host close opens the gate. It is
not a Core hook, generic fault DSL, Worker identity context, or production
control event.

### Device inputs

Each stable file coordinate has one `:inputs` entry for HTTP, the console and CI.
GET returns descriptors with `eventName`, `title` and `payloadExample`, derived
from the replica's actual installed capabilities, never Server Group metadata.
Properties inputs are always discoverable. POST accepts exactly
`{"eventName":"message.read","payload":{"messageId":"message-1"}}`;
payload must be an object. These are local simulated device events, not Command
or DeliveryReport names and not callable Worker Handlers.

| Input | Payload and local effect |
| --- | --- |
| `properties.update` | String KV Map; persist merged Properties and publish the supplied keys |
| `properties.replace` | String KV Map; persist replacement of mutable Properties and publish the full snapshot |
| `sms.receive` | `{text,smsId?,phone?}`; receive on the selected SIM; text at most 1024 characters |
| `message.deliver` | `{messageId}`; commit delivery before publication |
| `message.read` | `{messageId}`; delivery required |
| `message.reply` | `{messageId,text,requestId?}`; delivery required; text at most 4096 characters |

Omitted SMS/reply IDs are generated once and returned; explicit IDs support
repeat-input proofs. IDs are bounded to 128 characters. Requests retain their
existing bounds: Properties 64 KiB, SMS 8 KiB, Messages 1,000,000 bytes. Unknown
inputs, unavailable capabilities and malformed payloads are rejected, without
raw protocol fallback. Unknown Workers/messages return 404, invalid input 400,
and business-state conflicts 409. There is no shared RUNNING prerequisite:
Properties publication requires a running Worker; SMS and historical Messages
retain their own admission rules.

The Host dispatches once to the existing Properties, SMS or Messages Owner.
Scenario code chooses timing; the device Owner admits and commits input; the
real SDK/retained Reporter produces upstream evidence outside state gates.
No input queue, asynchronous task, automatic retry or server-side operation log
is introduced. A successful HTTP response is a local result, not a platform ACK.
SMS returns its existing MATCHED/IGNORED/DUPLICATE status; Messages retains
persisted/unchanged/held/sendAccepted. SERVER Direct Call records have no later
TASK Reporter: local receipt simulation cannot manufacture TRACKED publication.

SMS uses the selected Sim rather than looking up another Worker by phone. An
explicit phone is checked against that Sim's current address inside its gate,
before dedup or matching; an old address is rejected without side effects.
Messages likewise checks message ownership against the selected Sender inside
its existing gate. `:messages` pages that Sender's retained records (offset >= 0,
limit 1..1000); it adds no index or record copy.

Properties input automatically retains `labInventoryKey/labInventoryLine` from
the physical coordinate. Explicit values must match; `clientWorkerKey` is
forbidden. Update merges the supplied keys; replacement deletes omitted mutable
keys, including an empty replacement when capability requirements allow it.
Empty strings remain values. The existing Manager publishes `properties.updated`
or `properties.replaced` using the same Provider as business Handlers. Neither
input starts, stops or prepares a Worker; file-only PUT still saves without publishing.

Each Worker has a non-queuing Properties operation gate, acquired before the
inventory monitor and retained through the SDK send. The ordinary file PUT
and explicit start use the same gate. File read/modify/replace retains the existing serialized
protection so other records in the same JSONL file survive concurrent edits.
The SDK send runs outside the inventory monitor; stop/shutdown may revoke the
run while publication is in progress. Closing the control listener interrupts
its daemon handlers without joining a publication before Worker shutdown.
Unknown Workers return 404, invalid
Properties return 400, and a stopped/stopping Worker or occupied gate returns
409. Successful local completion returns
`200 {"persisted":true,"sendAccepted":true|false}`. False leaves the file in
place, with no retry or compensation. This result is local send acceptance,
not an Adapter/Server ACK. There is no file watcher, automatic scan, arbitrary
protocol injection or new Worker SDK API.

Number-capable replicas require nonblank `phone` and strict `[A-Z]{2}` `country`;
phone is unique across all number-capable replicas in this Host, not inferred from
country. Validation and number conflicts are checked before atomic file replacement.
Only a successful write installs the snapshot/address update; file I/O never holds
a business gate and SDK/Reporter calls never hold the inventory gate.

A phone change withdraws the old SMS address, locally ends its listeners as
`INTERRUPTED` and clears Reporters before exposing the new address. It never emits
a synthetic ending Report or carries an old address alias. Previously admitted
input rechecks the original SIM binding before matching. Country changes affect
new admission, not existing business records. Historical Messages keep their
original phone/country and run-bound Reporter and can still receive receipts.
Duplicate commands return their original records before current-Properties checks.
Start/stop uses stable replica bindings, and finite traffic chooses stable devices
then reads their current address. No property change re-Prepares or stops a Worker.

The collection-level stop endpoint accepts `1..100` unique existing Worker
coordinates. It validates the complete request before issuing any stop and
returns `202 {"acceptedCount":n}`. The call is a one-shot Lab mutation: the
Host does not retry or compensate a failed batch, and stopping a run does not
delete its Server-issued identity.

The Host also makes two finite background-fault Definitions available to an
explicit Group `events` list:

```text
extension.worker.lab.delay  {"delayMillis":1..30000}
extension.worker.lab.fail   {}
```

Delay occupies the current synchronous Handler path and returns one successful
Report after the requested interval. Fail produces the ordinary Worker `3303`
execution-failure Report without stopping the Worker or its connection. They
have no probability, scheduler, mutable fault state or HTTP control surface and
are not selected by the default Group bindings. Worker Convergence Health
selects them only for its String Group background workload.

These endpoints expose Lab desired/runtime state only. They do not claim
Adapter connectivity, Kernel score, or schedulability. The stable ready line is:

```text
WORKER_SIMULATOR_READY control=http://127.0.0.1:<port>/lab initialWorkerCount=<n> scheduledStopCount=<n> groupCount=<n>
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
The device input panel loads descriptions and examples from the Host, keeps
inventory coordinates read-only, and selects receipts from the chosen Worker's
paged messages rather than guessing its latest message. Local input/results and
HTTP errors are held only in the current browser page, capped at 20 operations.
SMS and Messages shortcuts use the same panel logic; records, metrics, finite
traffic and receipt hold/release retain their separate purposes. The superseded
Worker Properties mutation and product single-input routes are removed, not aliases.
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
state, Commands, Results, Tasks, or scores. External direct file edits take effect on the
next explicit Worker start; HTTP edits update current local state immediately. One Lab root supports one Worker Simulator
process.

The module depends only on Worker Core, Java Worker, the shared transport
contract, and its finite capability libraries. It has no Kernel, Spring,
Server, Adapter implementation, Redis, score, Pacer, reflection, or
`ServiceLoader` dependency.

```text
./gradlew :worker_simulator_jvm:test
./gradlew :worker_simulator_jvm:installDist
python -m unittest discover -s worker_simulator_jvm/src/test/python -p 'test_*.py'
```

The installed-entry proof launches the real generated script from another working
directory with omitted process/template/resource settings, observes the actual
control API, checks default Lab/SIM generation and 101-record cross-file numbering,
and restarts against the retained inventory despite changed count/template input.

Repository-level proofs start Redis, one Java Server and one independent
Worker Simulator. Server owns the Java Kernel Pacer applications and its
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
