# Shared Scenario Preview

Status: current shared scenario launch and archive owner.

This finite preview starts one Server and one Worker Simulator. SMS and Messages use
one Redis scope, one WebSocket Adapter and the same Worker pool. App Checks adds
two dedicated App Groups on that platform, with backend APIs only. It adds no product
framework or platform owner. The production Runtime ZIP continues to exclude Host.

From the checkout root, install Python prerequisites and launch:

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

From this directory the entry is `python run_preview.py --build`. Build requires
Java 21, Node 22.19+ below 25, Corepack/pnpm 11.9.0 and Gradle's existing wrapper.
Run requires external Java 21, Python 3.11+ and Redis 7. It does not start or stop Redis.
`XA_MASS_REDIS_URL` defaults to `redis://127.0.0.1:6379/15`.

The root command builds once through the Preview task graph and calls this same
launcher in-process. Its `--count`, `--app-count`, `--seed`, `--port` and `--sandbox-root` options
are Preview-only; the default Lab and AgentForge launch behavior is unchanged.

The fixed `preview` profile serves Messages at `http://127.0.0.1:18500/messages`,
SMS at `/sms`, Runtime at `/runtime/workers`, and the Host at `http://127.0.0.1:18504/lab`.
All three business scenarios are enabled together. `--count 100 --seed 712`
initializes a reproducible random demo-sim population: defaults are 60 demo Workers and
seed 0. Count accepts 1..10,000; seed is a signed 64-bit integer. Country choices
have equal weight, not exact quotas or guaranteed small-sample coverage.
The previous country-count argument is removed rather than reinterpreted.
`--app-count` defaults to 20 per App Group (100 total Workers with other defaults).
It accepts 0..15000, the existing Host Group bound. Zero removes both App Groups
from this run's Host configuration, even if those inventory directories already
exist. It neither deletes inventory nor removes the profile's Groups or APIs.
App Workers install only `extension.worker.app.registration.check`; `--count`
continues to control only demo-sim. Readiness observes all configured inventory
through the generic Lab Worker endpoint and verifies their actual Adapter routes.
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
`run.json` records count, appCount, seed and fixed `scenarios: ["sms", "messages", "app-checks"]`, not a
promised country distribution. The Host uses the existing combined capability
configuration; no Simulator runtime mode selects business deployment.
SMS and coexistence CI pre-materialize exact 1/1/1, 4/4/4 or 700/200/100 inventories
using the shared proof inventory utility before starting this launcher. Their
original coordinates, load thresholds and independent oracles remain unchanged;
the same inventory is reused for Host restarts. They explicitly use app_count=0,
retaining the original topology. No seed search or quota repair is used.
Existing inventories and historical run evidence are not migrated, scanned or
deleted when consolidating distribution code; pass `--sandbox-root` to reuse one.
`--port` accepts 1..65531 and sets Server base, Adapter +3 and Host +4. Occupied ports fail without
stopping the existing service. Ctrl+C stops only owned processes and cleans the
exact generated `test_products_<UUID>` scope through SCAN/UNLINK.

[The executable profile](../../server_boot_jvm/src/main/resources/application-preview.yaml)
owns Server/Adapter/Endpoint coordinates. It is packaged in the Boot JAR and
copied to source `build/preview/config` and archive `config` by the distribution. Preview
enables all three business libraries. SMS/Messages share one mixed-country
`demo-sim` Group and Host Manager. App Checks uses one Manager per App Group.
SMS queries shared country Pool stock; Messages uses the
`worker.messaging.available` function over messaging Pool stock. Their Task
supply declarations remain separate from Item queries.
All catalogs must initialize before the Host starts, then actual Adapter routes
must be observed. This is the sole source/ZIP launcher; scenario modules retain
their own APIs and acceptance oracles.
Archive verification checks all four host profiles, excludes application configuration
from nested platform/Scenario libraries, and compares the external preview file with
the resource inside the packaged Server JAR. Test configuration is never delivered.
The shared HTTP client reuses a connection per thread and endpoint while requests
remain less than five seconds apart. It closes an idle connection before the next
request; a failed or uncertain mutation is surfaced without automatic replay.

```powershell
.\gradlew.bat :distribution:server:previewZip
.\gradlew.bat :distribution:server:verifyPreviewArchive
```

The ZIP contains the current Server JAR, Host classpath in `worker-simulator/lib`,
the four standalone Simulator examples in `worker-simulator/config` (including the
minimal Lab configuration using built-in defaults), unified frontend, config,
Python entry and requirements. After extraction install `requirements.txt` with pip
and run `python run_preview.py`. No Node or
Gradle is needed. The manifest identifies versions, HEAD, the fixed scenarios and
SHA-256 fingerprints of binaries, frontend, launcher and deployment config.
Preview version `0.1.0-preview` is independent of the platform project version.
Its tasks do not require a release version or stage the Runtime ZIP.
Source staging uses `build/preview/server`, `build/preview/host`,
`build/preview/config` and `build/preview/frontend` in this distribution;
the frontend includes the current build's generated diagnostic dictionary. It does not reuse
a possibly running installation in another preview. All staging inputs come from
the same Gradle graph as the ZIP.

Run the external [proof runner](../../integrations/scenario-coexistence/README.md)
with `--root <extracted-directory>`; it loads the packaged launcher and starts the
packaged artifacts. Archive verification compares frontend bytes and all manifest
fingerprints. Runs store private process metadata/logs beneath `build/runs`; CI
publishes only safe summaries, never message bodies, replies or full Properties.
The private Server access log contains only HTTP method, path and status, enabling
proofs to count Prepare calls independently without logging request or result bodies.
The [SMS acceptance runner](../../scenarios/sms-reception-jvm/README.md#检查与验收)
retains its functional, lifecycle and fixed 1,000-Worker workload. It submits only SMS workload to the same combined preview and also accepts `--root` to load an extracted launcher:

```powershell
python scenarios/sms-reception-jvm/run_acceptance.py --build --scenario functional
python scenarios/sms-reception-jvm/run_acceptance.py --scenario functional --root <extracted-directory>
```

App Checks has a separate [finite acceptance oracle](../../scenarios/app-checks-jvm/README.md#装配与证明),
used against both source staging and a fresh ZIP. It does not alter the SMS or
Messages workload assertions. Acceptance scripts and run evidence stay outside the ZIP.
Launch lifecycle and
archive checks belong here; run them with
`.\gradlew.bat :distribution:server:previewLauncherTest`, which also runs the root
launcher tests. The preview verifier is `src/test/python/verify_preview_archive.py`.
Both the SMS Preview workflow and Scenario Coexistence lane run these checks before
their separate business proofs.
