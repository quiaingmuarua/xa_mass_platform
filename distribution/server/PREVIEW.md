# Shared Scenario Preview

Status: current shared scenario launch and archive owner.

This finite Preview launches one Server and an optional independent Worker
Simulator. [Boot](../../server_boot_jvm/README.md#platform-and-preview) owns its
profile and Scenario composition. This document owns Preview paths, source/ZIP
delivery and cleanup; [local composition](README.md#source-launch) owns shared
argument resolution and process lifecycle. The
[production Runtime ZIP](README.md#runtime-archive) excludes Host.

## Source launch

From the checkout root:

> **Data retention warning:** the default launch uses a temporary Redis scope.
> Normal shutdown deletes its Tasks, Results, credit data and other scoped Redis
> state, plus this run's private Task archives. To retain runtime data, supply
> `--redis-scope <stable-name>` and reuse the same `--work-dir` from the first launch.
> A work directory alone does not disable cleanup. See the
> [retained launch example](../../doc/guide/operations/deploy.md#从源码启动).

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

From this directory, use `python run_preview.py --build`. Build requires Java 21,
Node 22.19+ below 25, Corepack/pnpm 11.9.0 and the existing Gradle wrapper. The
frontend build uses Corepack when available, otherwise pnpm on PATH, with the
versions declared in `frontend/package.json`; missing tools or a failed build
are reported without retrying through another package manager.

Run requires Java 21, Python 3.11+ and external Redis 7. The launcher does not
manage Redis. Its default URL is `redis://127.0.0.1:6379/15`; use `--redis-url`
to override it. Source and packaged entries share
[local options and precedence](README.md#source-launch). Their Java selection
uses quantity counts; coordinate and scheduled-stop plans belong to the
standalone [Simulator Host](../../worker_simulator_jvm/README.md#embedded-startup-plan)
or explicit proof fixtures. `run_preview.py` rejects positive `--android-count`;
use the checkout's `run_local_runtime.py` for Android or mixed composition.

The default Console is `http://127.0.0.1:18500`, documentation is `/docs/`, and
the Host is `http://127.0.0.1:18504/lab`. Use the
[quick start](../../doc/guide/quick-start.md) for login and user steps,
[Frontend](../../frontend/README.md#access-key-login-and-access) for page behavior,
and [Boot page mappings](../../server_boot_jvm/README.md#pages-and-configuration)
for serving configuration. Business contracts remain in
[SMS](../../scenarios/sms-reception-jvm/README.md),
[Messages](../../scenarios/message-campaigns-jvm/README.md) and
[App Checks](../../scenarios/app-checks-jvm/README.md).

## Inventory and process lifecycle

The default Java input is `worker-simulator/config/products.json` in the ZIP,
or `worker_simulator_jvm/config/products.json` in the checkout. The launcher
writes resolved Runtime URL, absolute inventory path, Host port and the selected
configuration to one run-local `worker-simulator.json`, then supplies only
`--config` to Simulator Main. It does not evaluate templates or repair country
quotas. Quantity inventory growth and subset selection belong to the
[Simulator](../../worker_simulator_jvm/README.md#persistent-worker-lab).

Default inventory is `<preview-root>/data/scenario-workers`. An explicit
`--work-dir` selects `<work-dir>/data/scenario-workers`; without it, an explicit
Java configuration may retain its file-relative inventory. App Checks defaults to
`<work-dir>/data/app-checks/configuration.json`, with the Preview directory as
the default work directory. An external Runtime YAML's absolute configuration
path takes precedence. Choosing a separate Java inventory does not move this
Scenario file. The launcher preserves configuration and inventory on shutdown;
the Scenario owns [configuration storage and restart activation](../../scenarios/app-checks-jvm/README.md#模块内配置存储).

`<run-output>/run.json` records `profile`, `scope`, `temporaryScope`, Server `url`,
Host `host`, `adapter`, `adapterPort`, `javaCounts`, `androidCount`, `seed`, process
`pids`, `workDir`, `sandboxRoot`, `runtimeConfig`, `groupsFile` and artifact
fingerprints. `javaCounts` is the resolved quantity map, `{}` when no Java Host
is selected, and `null` for a proof's non-quantity startup plan.
`appChecksConfiguration` records the launcher's default Scenario path; an explicit
Runtime YAML path takes precedence. This record does not certify country
distribution, installed business capabilities or Matching eligibility.

Public HTTP and Adapter ports are independent values in `1..65535`. Without an
explicit Java configuration, the Host port defaults to Server +4 and must be
valid and distinct. Occupied ports fail without stopping an existing service.
The Server readiness gate precedes Host launch; Worker readiness checks selected
RUNNING identities and actual Adapter routes. Shared
[HTTP client behavior](README.md#local-http-client) and the public-development-key
limitation apply equally to source and ZIP.

Scenario proofs use Python `Preview` with their own pre-materialized inventory,
explicit coordinate plans and Adapter +3 / Host +4 fixture offsets. They share
`LocalRun` staging, readiness and cleanup without converting proof counts or fault
timing into the public quantity interface. Each proof owns its exact fixture and
reuses its private inventory/configuration paths for restart.

Ctrl+C stops only owned processes. A child exit ends the run without automatic
recovery. After successful process cleanup, a generated `test_products_<UUID>`
scope is removed with SCAN/UNLINK of that exact scope; explicit scopes remain.
Both public entries print the temporary-scope warning before build/start and
describe retention in `--help`. A hard-killed launcher may leave owned processes
and data requiring explicit cleanup.

## Archive delivery

```powershell
.\gradlew.bat :distribution:server:previewZip
.\gradlew.bat :distribution:server:verifyPreviewArchive
```

The ZIP contains the Server JAR, Host classpath in `worker-simulator/lib`, the
four Simulator examples in `worker-simulator/config`, unified frontend with
`/docs/`, configuration, Python entry and requirements. The output is
`distribution/server/build/distributions/xa-mass-scenario-preview-0.1.0-preview.zip`.
After extraction, run from its root:

> **The packaged default also deletes runtime data on normal exit.** Supply
> `--redis-scope <stable-name>` and reuse `--work-dir` to retain it. Server retention
> still applies to retained scopes.

```text
python -m pip install -r requirements.txt
python run_preview.py
```

No Node or Gradle is needed. The manifest identifies versions, HEAD, the fixed
Scenarios and SHA-256 fingerprints of binaries, frontend, launcher and deployment
configuration. Preview version `0.1.0-preview` is independent of the platform
version; its tasks require no release version or Runtime ZIP staging.

Source staging uses `build/preview/server`, `build/preview/host`,
`build/preview/config` and `build/preview/frontend` in this distribution, including
the current documentation site and diagnostic dictionary. All inputs use the
same Gradle graph as the ZIP; a running installation is not reused or overwritten.

[Canonical Preview YAML](../../server_boot_jvm/src/main/resources/application-preview.yaml)
is packaged in the Boot JAR and copied to source `build/preview/config` and ZIP
`config`. Verification compares the external copy with the JAR resource, checks
all three Boot configurations, rejects application configuration in nested
platform/Scenario libraries and all test configuration, and compares frontend
bytes and manifest fingerprints. The archive includes seeds, never materialized
Scenario configuration, lock files or temporary state.

## Verification

Business runners remain in the checkout. `--build` uses source staging;
`--root <extracted-directory>` loads only the packaged launcher, HTTP client and
artifacts, without checkout fallback or ZIP-local builds. Proof scripts and run
evidence are not packaged. Private process metadata/logs live beneath `build/runs`;
CI publishes bounded safe summaries. The private Server access log records only
HTTP method, path and status for independent Prepare counts.

[SMS acceptance](../../scenarios/sms-reception-jvm/README.md#检查与验收),
[Scenario Coexistence](../../integrations/scenario-coexistence/README.md) and
[App Checks](../../scenarios/app-checks-jvm/README.md#装配与证明) own their workloads,
fixtures and assertions. [TESTING](../../TESTING.md) owns proof selection/commands.
Run launcher tests with `:distribution:server:previewLauncherTest`, including the
root entry tests; the archive verifier is `python -m distribution.archive_tools preview`.
SMS Preview and Scenario Coexistence run these delivery checks before their
independent business proofs.

## Task result history

For a generated temporary scope, the launcher supplies
`--xa.mass.task-retention.database-path=<run-output>/task-history.sqlite`.
An explicit scope defaults to `<work-dir>/data/task-history.sqlite`; external
Runtime YAML may supply its own absolute database path.
[Server retention](../../server_jvm/README.md#finite-task-retention) owns expiry,
history and Runtime/Storage semantics; the
[workbench guide](../../doc/guide/usage/workbench.md) explains user operations.

After successful owned-process shutdown, cleanup removes only the generated
scope's private database and its `-wal`/`-shm` sidecars. Explicit scopes and
external database paths remain. Worker inventory and Scenario configuration are
independent retained inputs.
