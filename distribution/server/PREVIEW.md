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
`XA_MASS_REDIS_URL` defaults to `redis://127.0.0.1:6379/15`.

The root command builds once through the Preview task graph and calls this same
launcher in-process. Its `--count`, `--app-count`, `--seed`, `--port` and `--sandbox-root` options
are Preview-only; the default Lab and AgentForge launch behavior is unchanged.

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

`--count 100 --seed 712` initializes a reproducible random demo-sim population:
defaults are 60 demo Workers and
seed 0. Count accepts 1..10,000; seed is a signed 64-bit integer. Country choices
have equal weight, not exact quotas or guaranteed small-sample coverage.
The previous country-count argument is removed rather than reinterpreted.
`--app-count` defaults to 20 per App Group (100 total Workers with other defaults).
App A/B each declare and install both app.registration.check and phonenumber.original-carrier.
Batch Checks offers registration and carrier execution configurations for both Groups;
registration keeps its window Pool while carrier uses Any and does not enter that window observation.
There is no extra carrier Preview Group or separate Worker count parameter. The independent
number capability examples and platform proofs remain separate.
The app-count parameter accepts 0..15000, the existing Host Group bound. Zero removes both App Groups
from this run's Host configuration, even if those inventory directories already
exist. It neither deletes inventory nor removes the profile's Groups or APIs.
`--count` continues to control only demo-sim. Readiness observes all configured inventory
through the generic Lab Worker endpoint and verifies their actual Adapter routes.
This gate waits for Host RUNNING and connected routes, not Properties publication,
Matching eligibility or successful business execution.
Inventory persists at `<preview-root>/data/scenario-workers` (the source default
is `distribution/server/data/scenario-workers`); `--sandbox-root`
selects another root with that suffix. Existing Group directories, including empty
ones, are never reseeded or adjusted to count/seed. Readiness uses actual inventory.
To change the generated population, choose a fresh inventory root or explicitly
rebuild the Group through Simulator configuration; changing seed alone preserves edits.
Each acceptance run uses an isolated inventory root and reuses it for Host restarts.
The launcher selects a complete Simulator example, writes the final Runtime URL,
absolute inventory path, port, events, count, seed and template into one
`worker-simulator.json` in its run directory, and passes only `--config` to Main.
The launcher never evaluates templates or corrects sampled country counts.
App Checks configuration persists separately at `<inventory-parent>/app-checks/configuration`
(H2 creates the `.mv.db` file), alongside `data/scenario-workers`. The launcher passes
that prefix as `XA_MASS_APP_CHECKS_DB_PATH`; it never removes the database on shutdown.
`XA_MASS_APP_CHECKS_SEED` may point to an explicit seed resource for a fresh environment;
otherwise the module's packaged Preview seed is used. Seeds initialize only an empty
configuration table. Saved edits require a manual Server restart; changed window length
requires a new Redis scope. Existing tasks and Redis state are not moved into H2.
The ZIP contains code and the seed resource, never a database file. Acceptance runs
use their private inventory sibling for a distinct database; source/ZIP starts do not
touch an already running Preview's configuration.

Batch Checks configuration uses a checkId-keyed checks object, named type
defaults, per-check event/refill/query bindings and optional window Pool groups.
H2 stores the whole document without format/revision counters; saves compare a content fingerprint. The admin page edits the whole JSON document;
saving does not affect the running process. Use a new H2 file and Redis scope when
switching from the old table/document shape; there is no migration or fallback. Creation sends only
requestId, checkId and (for phone seeds only) country. Tasks retain their execution snapshot across later
configuration saves/restarts; changing an existing window length requires a new scope.
Preview exposes app-a-registration/app-a-carrier and app-b-registration/app-b-carrier.
The text seed path is verified by a test-only MD5 check; it is not a production Preview option.
Existing H2 documents are not overwritten by seeds. This checks schema cutover uses a new
database, Redis scope and inventory; the launcher does not migrate or replace a running environment.

`run.json` records count, appCount, seed, appChecksDatabase and fixed `scenarios: ["sms", "messages", "app-checks"]`, not a
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
python run_preview.py --port 18640 --sandbox-root ./environments/messages-v3/data/scenario-workers
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
launcher tests. The preview verifier is `src/test/python/verify_preview_archive.py`.
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
Persistent Worker inventory and App Checks H2 configuration remain untouched.
A hard-killed launcher still requires explicit cleanup of its own run resources.
