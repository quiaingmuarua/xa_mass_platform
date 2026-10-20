# Shared Scenario Preview

Status: current shared scenario launch and archive owner.

This finite preview starts one Server and one independent Worker Simulator.
[Boot](../../server_boot_jvm/README.md#platform-and-preview) owns the fixed scenario
topology and profile; this document owns source/ZIP launch and delivery. The
[production Runtime ZIP](README.md#runtime-archive) excludes Host.

## Source launch

From the checkout root, install Python prerequisites and launch:

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

From this directory the entry is `python run_preview.py --build`. Build requires
Java 21, Node 22.19+ below 25, Corepack/pnpm 11.9.0 and Gradle's existing wrapper.
The frontend build uses Corepack when available, otherwise pnpm on PATH, with
the versions declared in `frontend/package.json`; neither tool present fails
before dependency installation. An install/build failure is reported without
retrying through another package manager.
Run requires external Java 21, Python 3.11+ and Redis 7. It does not start or stop Redis.
Redis defaults to `redis://127.0.0.1:6379/15`; override it with `--redis-url`.
The root and packaged entries share local argument resolution and process lifecycle.
Use `--java-count GROUP=N`, `--server-port`, `--adapter-port`, `--work-dir`, or
`--java-config` for an explicit environment. No business environment variables
or retired population CLI aliases are read. See [source launch](README.md#source-launch).

Open the Console at `http://127.0.0.1:18500` and the device Host at
`http://127.0.0.1:18504/lab`. Boot owns the
[page mappings](../../server_boot_jvm/README.md#pages-and-configuration);
the [Frontend Owner](../../frontend/README.md) describes page operations.

The Console starts at development login, with one built-in admin and two operators
for Messages and App Checks workflow verification. Server owns a JSON-initialized,
process-memory user directory shared by all clients; only Server restart resets
user edits. Its top bar displays the current user and
logout; see [development login](../../frontend/README.md#development-login-and-access).
Direct App Checks task/configuration requests declare their caller through the
[scenario headers](../../scenarios/app-checks-jvm/README.md#api-和-task-数据).
Task requests resolve those declarations against the Server directory on each
request; role and Project changes affect the next admission. Single-user proof
clients explicitly use seed administrator `1000`. Catalog readiness remains
caller-free. These identities do not authenticate users.

## Inventory and process lifecycle

The default Java configuration starts 60 Demo Workers and 20 in each App Group.
App A/B retain simulated registration and Messages. Demo installs original-carrier,
uint32-prime and MD5 events alongside its other capabilities. Only registration
uses assignment-window observation. No Worker selection implicitly enables resources.

The public local/ZIP entry turns configured counts into an explicit quantity
startup plan. It preserves existing inventory and asks the Simulator Owner to
add validated records only when needed; lowering a count starts a subset without
deleting data. A zero count starts none. Coordinates and retained Properties do
not change. Java controls still belong to one Host process.

The Python `Preview` composition used by Scenario proofs retains their explicit
seeded inventory and coordinate-based fixture behavior; it shares `LocalRun` for
staging, Server/Host launch, readiness and cleanup. Proof workload counts and
fault timing are not reinterpreted as an inventory migration.

Default inventory remains `<preview-root>/data/scenario-workers`. An explicit
`--work-dir` supplies `<work-dir>/data/scenario-workers`; detailed Java configuration
may supply its own file-relative inventory when no work directory is selected.
Readiness checks selected RUNNING identities and actual Adapter routes, not
Properties publication, Matching eligibility or business success.
Each acceptance run uses an isolated inventory root and reuses it for Host restarts.
The launcher selects a complete Simulator example, writes the final Runtime URL,
absolute inventory path, port, events, count, seed and template into one
`worker-simulator.json` in its run directory, and passes only `--config` to Main.
The launcher never evaluates templates or corrects sampled country counts.
App Checks configuration persists separately at `<inventory-parent>/app-checks/configuration.json`,
alongside `data/scenario-workers`. The launcher passes that file as
the explicit Scenario configuration-path argument; it never removes the configuration on shutdown.
An external Runtime YAML may specify an absolute seed file URI for a fresh environment;
otherwise the module's packaged Preview seed is used. Seeds initialize only a missing
configuration file. Saved edits require a manual Server restart. Host window length changes require a new Redis scope. Tasks and execution state remain in Redis.
The scenario holds `configuration.json.lock` while running and publishes complete edits
with same-directory atomic replacement. A second instance cannot own the same file.
Stop Server before editing the file manually. Failed or uncertain saves retain editor
text for readback; missing or damaged files are not reseeded while running.
The ZIP contains code and the seed resource, never materialized configuration, lock or
temporary files. Acceptance runs use their private inventory sibling for a distinct
configuration file; source/ZIP starts do not
touch an already running Preview's configuration.

Batch Checks configuration uses a checkId-keyed checks object, named type
defaults and per-check event/refill/query bindings. Host `xa.mass.worker-assembly.fixed-window` owns window definitions,
Group bindings and registration-assigned feedback. App A/B retain 60000 ms/10 and their original fields; other resources are unchanged.
The JSON file stores the whole document without fingerprints or format/revision counters;
saves compare a computed content fingerprint. The admin page edits the whole JSON document;
saving does not affect the running process. Use a new JSON file and Redis scope when
switching from H2; old databases are neither read, migrated nor deleted. Creation sends only
requestId, checkId and (for phone seeds only) country. Tasks retain their execution snapshot across later
configuration saves/restarts; changing an existing window length requires a new scope.
Preview exposes app-a-registration, app-b-registration, original-carrier, uint32-prime and md5-prefix. Carrier and MD5 use explicit batch events with seedsPerItem=10; registration and prime remain at 1. Input and CSV remain one seed per row. Existing configuration files are not reseeded; verify this configuration with a new file/scope/inventory.
The two text checks are local computation demonstrations, with no external city/DNS/stock data source.
The scenario document now contains common/checks only; old poolGroups input is rejected. Use a new configuration file/scope
for this cutover. The launcher does not migrate or replace a running environment.

`run.json` records count, appCount, seed, appChecksConfiguration and fixed `scenarios: ["sms", "messages", "app-checks"]`, not a
promised country distribution. The Host uses the existing combined capability
configuration; no Simulator runtime mode selects business deployment.
Acceptance fixtures materialize their exact inventory before launch instead of
searching seeds or repairing quotas; each [business proof](#verification) owns
its population and capability selection. Existing inventories and historical run
evidence are never migrated or deleted by this launcher.
`--port` accepts 1..65531 and sets Server base, Adapter +3 and Host +4. Occupied ports fail without
stopping the existing service. Ctrl+C stops only owned processes and cleans the
exact generated `test_products_<UUID>` scope through SCAN/UNLINK. Either child
process exiting ends the run; there is no automatic recovery. A hard-killed
launcher may leave processes and data, so normal shutdown is required for cleanup.

Server health and all catalogs must initialize before the Host starts, then
readiness verifies actual Adapter routes. Source and ZIP use this same launcher.
The shared HTTP client reuses a connection per thread and endpoint while requests
remain less than five seconds apart. It closes an idle connection before the next
request; a failed or uncertain mutation is surfaced without automatic replay.
Optional headers belong to each request and are not retained on the connection;
the shared client never injects a default developer caller.

## Messages multi-application cutover

Preview binds Messages applications Demo, App A and App B to the corresponding
Groups. All three install message.send; App A/B retain app.registration.check.
Their explicit phone ranges do not overlap, and country/messaging.enabled
Properties are initialized in new inventories. API creation requires appId and
no longer accepts senderPhone. Template content is opaque to Messages. The Host
now defaults each Group to text sending; existing JSON demonstration fixtures
explicitly set `messageContentMode: "lab-json"`. Creation success is not sending
acceptance or a receipt. Host-root `messages` settings define finite deduplication
and observation windows; they do not change Runtime Item retention.

This configuration expands the immutable App Group event definitions. Use a fresh
Redis scope and fresh inventory; do not apply it over existing create-only Groups
or edit retained inventory to resemble a migration. The launcher already selects
an independent test_products scope. For source and ZIP alike, choose an unused
port and a new sandbox root, for example from the Preview directory:

```powershell
python run_preview.py --server-port 18640 --adapter-port 18643 --work-dir ./environments/messages-v3
```

Use a new inventory path ending in `data/scenario-workers`. The three Catalog
applications and actual Worker capabilities must be verified after startup.
Keep the previous instance and inventory separate; no task copying, Group overwrite,
automatic replay of frozen requests or automatic replacement of an existing Preview
occurs. Opening a new console session avoids carrying old environment drafts.

## Archive delivery

```powershell
.\gradlew.bat :distribution:server:previewZip
.\gradlew.bat :distribution:server:verifyPreviewArchive
```

The ZIP contains the current Server JAR, Host classpath in `worker-simulator/lib`,
the four standalone Simulator examples in `worker-simulator/config` (including the
minimal Lab configuration using built-in defaults), unified frontend, config,
Python entry and requirements. The output is
`distribution/server/build/distributions/xa-mass-scenario-preview-0.1.0-preview.zip`.
After extraction, run from its root:

```text
python -m pip install -r requirements.txt
python run_preview.py
```

No Node or Gradle is needed. The manifest identifies versions, HEAD, the fixed scenarios and
SHA-256 fingerprints of binaries, frontend, launcher and deployment config.
Preview version `0.1.0-preview` is independent of the platform project version.
Its tasks do not require a release version or stage the Runtime ZIP.
Source staging uses `build/preview/server`, `build/preview/host`,
`build/preview/config` and `build/preview/frontend` in this distribution;
the frontend includes the current build's generated diagnostic dictionary. It does not reuse
a possibly running installation in another preview. All staging inputs come from
the same Gradle graph as the ZIP.

[The canonical preview YAML](../../server_boot_jvm/src/main/resources/application-preview.yaml)
is packaged in the Boot JAR and copied to source `build/preview/config` and archive
`config`. Archive verification checks all four Boot profiles, compares that external
copy with the JAR resource, and rejects application configuration in nested
platform/Scenario libraries and all test configuration. It also compares frontend
bytes and manifest fingerprints; the generated diagnostic dictionary is delivered
alongside the frontend.

## Verification

Business runners remain in the checkout. Their `--build` path uses source staging;
`--root <extracted-directory>` loads the packaged launcher, shared HTTP client and
artifacts without checkout fallback or building inside the ZIP. Run evidence and
acceptance scripts are not packaged. Runs store private process metadata/logs
beneath `build/runs`; CI
publishes only safe summaries, never message bodies, replies or full Properties.
The private Server access log contains only HTTP method, path and status, enabling
proofs to count Prepare calls independently without logging request or result bodies.
The [SMS acceptance](../../scenarios/sms-reception-jvm/README.md#检查与验收),
[Scenario Coexistence](../../integrations/scenario-coexistence/README.md) and
[App Checks oracle](../../scenarios/app-checks-jvm/README.md#装配与证明)
own their independent workloads, exact fixtures, source/ZIP commands and nonclaims.
Launch lifecycle and archive checks belong here; run them with
`.\gradlew.bat :distribution:server:previewLauncherTest`, which also runs the root
launcher tests. The preview verifier is `python -m distribution.archive_tools preview`.
Both the SMS Preview workflow and Scenario Coexistence lane run these checks before
their separate business proofs.

### Text sending cutover

The shipped products/messages Host configurations explicitly select text mode.
JSON-looking bodies remain literal text in that mode. To retain an old receipt
demonstration, configure its Group as lab-json before startup. Existing Task/Item
identities and stored Results need no migration; queued work is interpreted by the
Worker configuration selected for execution. Host windows do not survive restart.
Do not restart an existing Preview or overwrite its staged artifacts as part of
validation: build and run a separate source copy, port, scope and inventory.

## Raw Task workspace

Preview explicitly declares `default` with an empty Group binding list alongside
the business Projects. Its Group capabilities include all registered resources,
including later API registrations after manual refresh. Other Projects retain
their declared bindings. The admin task workbench supports RPC and finite tasks
for declared Projects. RPC lists long-lived Tasks separately from its JSON debug
page. Creation uses the logged-in caller, explicit immutable supply (initially
empty), and creation-time approval; SMS retains the host-configured default.
Programmatic and web calls use the same 1..100 Item HTTP batch and known-ID Result
reads. Neither opening the debugger nor switching Groups creates work. The list
is a bounded current batch; known Task IDs remain directly accessible.
Finite creation uses a content-area page with payload JSONL, TXT and full Item
JSONL, followed by independent quantity approval. Raw result previews and terminal
successful JSONL exports preserve Worker output without Scenario interpretation.
Project declarations never create Tasks. Existing caller and input-owner checks
continue to apply.

## Task result archives

The launcher supplies `XA_MASS_TASK_ARCHIVE_DIRECTORY=<run-output>/task-archives`
for its fresh scope. Server owns the 1h grace, sequential archive and 7d final
retention lifecycle. Grace/retention overrides are explicit proof configuration,
not alternate product defaults. Normal launcher shutdown stops owned processes,
cleans only its generated Redis scope, and removes only this private archive root.
Persistent Worker inventory and App Checks configuration file remain untouched.
A hard-killed launcher still requires explicit cleanup of its own run resources.
