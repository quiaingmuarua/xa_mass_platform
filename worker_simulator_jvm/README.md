# XA Mass Worker Simulator JVM

`worker_simulator_jvm` is a finite Java 21 device and business simulator using
the real Java Worker SDK. Lab, [SMS](../scenarios/sms-reception-jvm/README.md) and
[Messages](../scenarios/message-campaigns-jvm/README.md) compose capabilities on the
same file inventory, Properties, Managers and controls; scenario is a use case,
not a separate kind of Worker.
It has one process entry, one loopback control server and one HTML console.
The [App Checks](../scenarios/app-checks-jvm/README.md) capability adds stateless
one-shot lookup Handlers on `app-a-sim` and `app-b-sim` in Preview. Each per-replica
definition captures the actual identity at execution entry; request input cannot
choose its executor. It uses the existing Handler thread for deterministic delay,
then returns registered/unregistered or throws the existing execution exception.
No business lifecycle, callback, queue, Reporter or result cache is installed.
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
| `seed` | `0` (signed 64-bit initialization seed) |
| `startupPlan` | Start all discovered inventory, no scheduled stops |
| `messages` | Finite messaging windows and capacities; see [message retention](#message-retention-and-admission) |

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
| `demo-sim` | SMS, Messages and shared string tools | `runtime=java`, sequential `phone` from 861700000001, seeded equal-weight `country=CN/US/GB`, `operator=Preview SIM`, `simulated=true`, `messaging.enabled=true` |

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
retain explicit templates for Preview's seeded country distribution and
select only `demo-sim`. Installation and Preview
archives include these files under `config`.

### Deterministic initialization templates

Templates are an optional customization surface for reproducible synthetic
populations. Exact CI quotas are materialized as explicit inventory instead.
Omission selects the Group's default template; an explicitly supplied
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
operator. Let `i` be the one-based Group generation ordinal, continuous across files,
and `h = hash64(seed, workerGroupId, propertyName, i - 1)`:

| Expression | Output |
| --- | --- |
| `{"$index":[start,step]}` | Decimal `start + (i - 1) * step`; step must be positive |
| `{"$range":[min,max]}` | Decimal `min + unsignedRemainder(h, max - min + 1)` |
| `{"$choice":["A","B",...]}` | Select slot `unsignedRemainder(h, optionCount)`; repeated values are probability weights |

Set the optional root `"seed": 712` to choose another reproducible population.
The fixed hash is SHA-256 truncated to its first 8 bytes. Its input is big-endian:
8-byte signed seed, 4-byte UTF-8 Group byte length plus Group bytes, 4-byte UTF-8
property-name byte length plus name bytes, then 8-byte zero-based offset. The
digest is interpreted as an unsigned 64-bit value for remainder calculations.
There is no shared Random, mutable sequence or runtime random state. Algorithm
and tuple encoding are fixed by golden-vector tests, not a configurable algorithm.
Count, paths, file names, template parameters and traversal order are not hash
inputs: extending a population or changing another property/Group does not
perturb existing values. Reproducibility covers Properties and coordinates, not
JSON object field order. Random sampling neither promises exact quotas nor that
a small population contains every choice; unique phones continue using `$index`.

Numeric arguments must be integral and fit signed 64-bit arithmetic; invalid
ranges and generation overflow fail before installation. Choice accepts a
nonempty array of strings. Literal empty strings are legal. No time-based seed,
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

Messages is composed inside this module; the platform Worker SDK still owns only
execution and the original-run Reporter, without business state.

| Component | Owner |
| --- | --- |
| `MessageWorkerEndpoint` | Sender admission, the 64/256 send bound and original Reporter associations; no Lab, content-mode or HTTP implementation dependency |
| `MessageLab` | Acceptance fingerprints, first sending identity, optional observations, instructions and receipt diagnostics; no Worker SDK dependency |
| `MessageLabHttp` | One shared HTTP client, wire classification and the existing bounded callback executor; no acceptance or association ledger |
| `MessageScenario` | Fixed Host composition, Group content modes, startup/shutdown, the existing maintenance tick and cross-Owner admission/stop coordination |

The internal synchronous `MessageSendOperation` takes the unchanged message and
sender snapshot, nullable callback identity and remaining budget. It returns the
first accepted SENT snapshot and adopted callback identity. Definite rejection
releases the reservation, preserving input versus execution-failure classification;
an uncertain attempt retains it until expiry. The Worker endpoint checks association
and never retries the operation. Its focused proof constructs no Lab or listener.
`MessageProtocol` owns pure wire checks, not mutable facts or a shared platform DTO.

Lab receiving admission runs through the Worker's bounded `admitReceiver` critical
section. Sender/callback validation and Lab retention are ordered against
`stopWithCleanup`, which revokes associations and releases that Sender's records
under the same gate. The lock order remains Worker then Lab; callbacks only enqueue
local work here, and network/Reporter execution stays outside those gates. Do not
replace this operation with a boolean precheck followed by an unlocked Lab write.
Already admitted callbacks retain the SDK's run isolation and need not be awaited.

Host composition owns the single 100 ms tick, calling association expiry before Lab
maintenance. Lab no longer starts a thread or calls back into Worker maintenance.
Shutdown revokes Worker admission/associations before stopping that tick, clearing
Lab records and closing callback/network resources. Group modes are captured from
the existing startup configuration; no registration service or new configuration
format is introduced. Host routes call the actual Worker/Lab component, with no
old aggregate forwarding API. SMS/common Host input validation does not depend on
Messages helpers.

`config/messages.json` selects message senders; `config/products.json` installs
SMS and Messages on Demo and Messages/App Checks on App A/B. All senders expose
explicit phone, country and messaging.enabled Properties. Group/Pool selection
stays with Matching and Kernel. The Host never chooses replacement Workers.
Use the [shared launcher](../distribution/server/PREVIEW.md).

Group `messageContentMode` is captured at startup: `text` is the default and
`lab-json` explicitly selects receipt demonstrations. Unknown values fail startup.
This setting is not a Worker Property, Catalog field, or task parameter. The five
fields of `extension.worker.message.send` remain
`campaignId,messageId,country,recipientId,body`. Body is nonblank text of at most
4096 characters and is never trimmed or rewritten. Text mode accepts JSON-looking
strings literally and generates no automatic delivered/read/replied observations.

```text
message.send Handler -> real HTTP /lab/v1/messages/send -> confirmed SENT
    -> ordinary execution Result and Item completion (no receipt wait)
optional Lab observation -> bounded HTTP callback -> original run Reporter
    -> best-effort later Item/Result observation
```

The receiving Lab shares the Host process and loopback listener. It is a simulated
business channel, not evidence of third-party delivery. A send must obtain its
actual HTTP acceptance response before returning SENT. Rejected/uncertain sends
remain failures of that execution; no callback failure changes a confirmed send.

### Explicit JSON receipt demonstrations

Only `lab-json` parses the following body protocol; invalid JSON, duplicate or
unknown fields, invalid types and sequences reject before acceptance:

```json
{"receipts_status":["read","replied"],"delayMs":[1000,4000],"probability":0.5,"text":"收到了"}
```

`receipts_status` defaults to [], with at most 16 read/replied steps. Read is legal
once before replies; repeated replies are legal. `delayMs` is an integer or
inclusive two-integer range in 0..60000, default [1000,4000]. Probability is 0..1,
default zero, and omits only the last requested step. Replies require nonblank
text. JSON-mode acceptance generates delivered when observation capacity is
available; text mode does not. The Host seed and message ID fix the first plan.
Plans use the existing 100ms clock, at most 100 actions per tick. Observations keep
monotonic business time `max(clock,lastTime+1)` and do not wait for HTTP callbacks.

### Message retention and admission

The optional Host-root `messages` object accepts exactly these positive settings:

| Field | Default |
| --- | --- |
| dedupWindowMillis | 600000 |
| maxDedupEntries | 200000 |
| receiptWindowMillis | 600000 |
| maxTrackedMessages | 20000 |
| maxRecentSentRecords | 1000 |

Deduplication is receiver-owned, keyed by messageId. Each compact entry retains
only a versioned SHA-256 input fingerprint, first Sender, acceptance time and
nullable first callback identity. The digest uses length-prefixed UTF-8 fields
`lab-message-input/v1,campaignId,messageId,country,recipientId,body`. A matching
repeat returns the first SENT reconstructed from the verified request and first
Sender/time; conflicting input rejects. Neither reads nor repeats renew the
window. New identities at capacity receive HTTP 429 before acceptance; existing
identities still reconcile. There is no cumulative Campaign quota or duplicate
Campaign configuration directory. This is a bounded in-process guarantee, not
persistent or external exactly-once delivery. Expiry/restart ends that guarantee.

Reporter admission is optional. Before business HTTP the Worker tries to reserve
an association; full capacity or 1024 pending/uncertain associations skips new
tracking but still sends. Internal HTTP carries a nullable callbackId and returns
the first adopted identity. An untracked, expired or stopped original association
cannot be replaced by a retry's Reporter. Definite rejection releases the new
reservation; uncertain responses retain it until expiry. Callbacks can precede
the send response. All HTTP and Reporter calls run outside the state gates.

The association window starts at registration; receiver windows start at first
acceptance, using monotonic elapsed time. Task completion does not end tracking.
Full payloads, plans and latest replies are independently bounded to tracked
observations. A separate ring keeps the latest send summaries, including untracked
sends, without bodies. The Lab view prefers an observation while present and never
counts its summary twice. Each observation
keeps only its last 64 receipt diagnostics. The 10000 held-receipt cap drops new
held observations instead of rejecting sends or ending subsequent actions. Reply
operation deduplication is capped at 100000 IDs and ends with its observation;
exhaustion rejects only that reply operation. Callback queue admission, HTTP
success, and Reporter local acceptance remain separate evidence.

The existing tick and admissions remove bounded batches of expired records.
There are no additional maintenance threads, replay, sweeps of all history or
Task-state lookups for cleanup. Indexed Worker stop releases that Worker's full
observations, plans, held receipts and operation IDs, and revokes its associations.
Acceptance fingerprints remain until their own deadline. Already admitted calls
may finish; stop does not wait for a callback. Retained Runtime Results are not
cleared. Host restart restores neither deduplication nor observation windows.

One shared HTTP client admits 64 concurrent sends and at most 256 additional
waiting Handlers, parked on the existing caller threads with fair permit admission.
Waiting and
HTTP share the original five-second call budget; full admission and timeout still
fail, and stop does not wait for these calls. No Kernel scheduling or retry policy
changes. Metrics include sendInFlight, queuedSends and sendCapacityRejected.
Callbacks use four workers and 128 queued entries. Connect timeout remains one second. Sending
concurrency is separate from optional observation capacity. Metrics distinguish
acceptedMessages from current dedupEntries/trackedMessages/recentSentRecords,
skipped/expired associations, dropped receipts, discarded diagnostics and callback
failures. sendAttempts counts Handler send invocations, not Kernel retries.

### Messages Host API

| Route | Local contract |
| --- | --- |
| POST /lab/v1/messages/send | Existing message/sender envelope with nullable callbackId; returns first snapshot and nullable adopted callbackId |
| POST /lab/v1/messages/acceptances:load | `{messageIds:[...]}`, 1..100 known IDs; items contain inputFingerprint, original sender and observedAtMillis; missingIds includes expired records. Read-only, no renewal or body output |
| POST /lab/v1/workers/{group}/{replica}:inputs | message.receipt validates the original association and returns local reportAccepted |
| GET /lab/v1/messages/health | Host lifecycle and prepared identity evidence |
| GET /lab/v1/messages/inventory | Group/replica/Worker properties, limit 1..1000 |
| GET /lab/v1/messages/records | Currently retained observations and recent sends; total is retained count, acceptedTotal is cumulative; not complete history |
| GET /lab/v1/messages/metrics | Acceptance, bounded state and observation diagnostics |
| POST /lab/v1/messages/receipts:hold | `{enabled}`; no automatic flush |
| POST /lab/v1/messages/receipts:release | 1..10000 retained receiptIds; duplicates within a release retain original time/content |
| POST /lab/v1/messages/workers/{group}/{replica}:start | Start one local replica |
| POST /lab/v1/messages/workers/{group}/{replica}:stop | Revoke associations and retained observations before SDK stop |

Bodies remain bounded to 1000000 bytes. Manual read/reply requires a retained
observation; expired/stopped/untracked messages return unavailable. Local record
pages mark trackingAvailable and the Lab UI disables actions for summaries.
DIRECT_CALL still has only its synchronous Result; it cannot manufacture a TASK
Reporter. Public proof artifacts and ordinary logs exclude message/reply bodies.

## SMS Scenario

Start the Server with its `preview` profile first, then use a complete
configuration based on [sms.json](config/sms.json) with the matching Runtime URL,
or use the shared [Preview launcher](../distribution/server/PREVIEW.md) for both
scenarios and their Host. Preview registers one mixed-country `demo-sim` Group; the Host never
registers Groups. The example generates CN/US/GB numbers deterministically.
All configurations use the same filename plus physical-line identity and the
existing `SCENARIO_LAB` batch Prepare. Product examples do not implicitly start
the Lab Groups. Existing inventory is reused regardless of seed/count/template changes
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
The [business contract](../scenarios/sms-reception-jvm/README.md#监听和分发契约)
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

- With `newEnvironment=false`, a missing Group is generated from seed/count/template;
  an existing directory is reused exactly, even when empty. It is never topped up,
  truncated, repaired or overwritten.
- With `newEnvironment=true`, only that Group is regenerated on process startup.
  A later single-Worker start reloads its file and never runs the template.
- Changing seed does not overwrite retained edits or re-Prepare Workers. Use a
  new inventory directory or explicit Group rebuild to generate a new population.
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
| `message.read` | `{messageId}`; acts on an actually received message |
| `message.reply` | `{messageId,text,requestId?}`; delivery required; text at most 4096 characters |
| `message.receipt` | Internal HTTP callback: callbackId, receiptId and full snapshot; original association required |

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
The input router owns no queue or retry. Messages owns the bounded HTTP callback
queue above. A successful HTTP response is a local result, not a platform ACK.
SMS retains MATCHED/IGNORED/DUPLICATE; manual Messages returns
persisted/unchanged/held/receiptId/callbackQueued. SERVER Direct Call records have no later
TASK Reporter: local receipt simulation cannot manufacture TRACKED publication.

SMS uses the selected Sim rather than looking up another Worker by phone. An
explicit phone is checked against that Sim's current address inside its gate,
before dedup or matching; an old address is rejected without side effects.
Messages likewise checks message ownership against the selected Sender inside
its existing gate. `:messages` pages that Sender's retained records (offset >= 0,
limit 1..1000), using the existing Worker stop index instead of scanning every
retained Host record. It adds no independent index or record copy.

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
starting outbound Worker connections. After Manager and route assembly, HTTP and
the Messages clock start before those connections so early callbacks are accepted;
ordinary controls remain gated by startup completion. The listener address remains
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
and restarts against retained inventory despite changed seed/count/template input.
It also compares two fresh processes in different directories with seed 712,
including 101-record file boundaries, then checks retained edits and explicit
seeded rebuild. Product proofs separately materialize their exact country quotas.

Repository-level proofs start Redis, one Java Server and one independent
Worker Simulator. Server owns the Java Kernel Pacer applications and its
configured Adapter; each proof runner owns the Worker Host process:

- [`worker-correctness`](../integrations/worker-correctness/) proves the exact
  two-by-fifty topology, Lab-coordinate identity mapping, Adapter routes,
  extension reachability, 100 final Results and identity reuse across a real
  Host restart;
- [Worker Dynamic Matching](../integrations/worker-dynamic-matching/README.md)
  proves loaded Matching query execution follows live Worker and Platform facts,
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
