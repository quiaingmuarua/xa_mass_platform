# Integrations Layout Inventory

Status: ILC-0 complete for
[`INTEGRATIONS_AND_SERVER_BOOTSTRAP_ROADMAP.md`](./2026-05-28_INTEGRATIONS_AND_SERVER_BOOTSTRAP_ROADMAP.md).
ILC-1 sample movement and ILC-2 worker-pack movement are implemented against
this inventory. The Java sample targets recorded here are historical ILC move
targets; they were later removed by
[`INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md`](./2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md).
Current Java integration proof lives in `sdk/xa-mass-java-sdk` and
`integrations/xa-mass-scenario-launcher`, not under `integrations/samples/java`.

Date: 2026-05-28.

This inventory records the pre-move root `samples/` and `xa-mass-worker-pack`
references and the target paths for layout convergence. It separates Maven
module changes from plain filesystem references so the implementation can move
files without changing build ownership by accident.

## Target Move Table

| Pre-move path | Target path | Kind | ILC slice | Decision |
| --- | --- | --- | --- | --- |
| `samples/README.md` | `integrations/samples/README.md` | docs | ILC-1 | move with sample tree |
| `samples/worker-polling/java` | `integrations/samples/java/worker-polling` | Java sample, historical root reactor module | ILC-1 | moved during ILC-1, later retired by Java SDK adoption |
| `samples/worker-websocket/java` | `integrations/samples/java/worker-websocket` | Java sample, historical standalone POM | ILC-1 | moved during ILC-1, later retired by Java SDK adoption |
| `samples/worker-socket/java` | `integrations/samples/java/worker-socket` | Java sample, historical standalone POM | ILC-1 | moved during ILC-1, later retired by Java SDK adoption |
| `samples/worker-polling/node` | `integrations/samples/node/worker-polling` | Node sample script | ILC-1 | move as filesystem/runtime asset |
| `samples/worker-websocket/node` | `integrations/samples/node/worker-websocket` | Node sample script | ILC-1 | move as filesystem/runtime asset |
| `samples/worker-socket/node` | `integrations/samples/node/worker-socket` | Node sample script | ILC-1 | move as filesystem/runtime asset |
| `samples/dev` | `integrations/samples/dev/scenario` | external launcher and scenario configs | ILC-1 | move after confirming no server-side JSON consumer |
| `xa-mass-worker-pack` | `integrations/xa-mass-worker-pack` | Maven module | ILC-2 | move after sample path convergence |

## Maven Module Changes

Pre-ILC-1 root reactor entries:

```xml
<module>sdk/xa-mass-java-sdk</module>
<module>samples/worker-polling/java</module>
<module>xa-mass-worker-pack</module>
```

ILC-1 changed only the Java polling sample module:

```xml
<module>integrations/samples/java/worker-polling</module>
```

ILC-2 should change worker-pack:

```xml
<module>integrations/xa-mass-worker-pack</module>
```

Historical standalone Java sample POMs, now removed:

- `integrations/samples/java/worker-websocket/pom.xml`
- `integrations/samples/java/worker-socket/pom.xml`

Decision during ILC-1: keep them standalone. They were built by
`ExternalJavaWorkerProcess` using `mvn -f ... package`, not by the root reactor.
The Java SDK adoption roadmap later removed those samples and the helper.

Node samples have no Maven ownership. ILC-1 should update script paths and docs
only.

## Path-Sensitive Code References

### Server E2E Java Sample Helper

File:
`xa-mass-server/src/test/java/com/xa/mass/server/e2e/support/ExternalJavaWorkerProcess.java`

Historical target references after ILC-1, before Java SDK adoption removed the
sample helper:

- `integrations/samples/java/worker-polling/target/worker-polling-java-sample.jar`
- `integrations/samples/java/worker-websocket/target/worker-websocket-java-sample.jar`
- `integrations/samples/java/worker-socket/target/worker-socket-java-sample.jar`
- `integrations/samples/java/worker-polling`
- `integrations/samples/java/worker-websocket/pom.xml`
- `integrations/samples/java/worker-socket/pom.xml`
- repo-root discovery checks `integrations/samples/java/worker-polling/pom.xml`

Historical ILC-1 action:

- update all paths to `integrations/samples/java/...`
- keep polling build as `-pl integrations/samples/java/worker-polling -am`
- keep websocket/socket builds as standalone `-f integrations/samples/java/.../pom.xml`
- update repo-root discovery to use the new polling sample POM path

Current action: use `ExternalJavaScenarioLauncherProcess` and
`JavaScenarioLauncherBlackBoxIntegrationTest` for Java SDK black-box proof.

### Server E2E Node Sample Helper

File:
`xa-mass-server/src/test/java/com/xa/mass/server/e2e/support/ExternalNodeWorkerProcess.java`

Target references after ILC-1:

- `integrations/samples/node/worker-websocket/worker.mjs`
- `integrations/samples/node/worker-socket/worker.mjs`
- `integrations/samples/node/worker-polling/worker.mjs`

ILC-1 action:

- update to `integrations/samples/node/.../worker.mjs`

### Dev Scenario Launcher

File: `integrations/samples/dev/scenario/launch-workers.mjs`

Target references after ILC-1:

- `integrations/samples/dev/scenario/bootstrap.json`
- `integrations/samples/dev/scenario/rules.json`
- `integrations/samples/dev/scenario/workers.json`
- `integrations/samples/dev/scenario/tasks.json`
- launcher attribute string `integrations/samples/dev/scenario/launch-workers.mjs`

ILC-1 action:

- move to `integrations/samples/dev/scenario/launch-workers.mjs`
- compute repo root from the new nested location
- read configs from `integrations/samples/dev/scenario/*.json`
- update launcher attribute string to the new path

### Dev Worker Config

File: `integrations/samples/dev/scenario/workers.json`

Target script references after ILC-1:

- `integrations/samples/node/worker-websocket/worker.mjs`

ILC-1 action:

- update script paths to `integrations/samples/node/worker-websocket/worker.mjs`

### Worker-Pack Process Starter

File:
`integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/starter/SampleWorkerProcessStarter.java`

Target references after ILC-1:

- Javadoc says child-process startup is under `integrations/samples`
- default `sample.worker.launcher-script` is `integrations/samples/dev/scenario/launch-workers.mjs`

ILC-1 action:

- update default launcher script to
  `integrations/samples/dev/scenario/launch-workers.mjs`
- update Javadoc wording to `integrations/samples`

ILC-2 action:

- after worker-pack moves, keep the same property default because it is
  repository-root relative

## Documentation References

ILC-1 updates:

- `README.md`: root sample link
- `README.zh-CN.md`: root sample link
- `samples/README.md`: move to `integrations/samples/README.md` and update
  all commands
- `integrations/samples/node/worker-*/README.md`: update `node .../worker.mjs` commands
- historical `integrations/samples/java/worker-*/README.md`: updated during
  ILC-1 and later removed by Java SDK adoption
- `sdk/xa-mass-java-sdk/README.md`: update Java polling sample link
- `sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md`: update sample matrix and per-sample
  path text
- archived `doc/JAVA_EXTERNAL_SDK_ROADMAP.md` and
  `doc/JAVA_EXTERNAL_SDK_INVENTORY.md`: update current facts once ILC-1 is
  complete

ILC-2 updates:

- `README.md`, `README.zh-CN.md`
- `doc/README.md`
- `xa-mass-testing/VERIFIED_RUNBOOK.md`
- `doc/WORKER_FAULT_MATRIX_ROADMAP.md`
- `xa-mass-server/README.md`
- `integrations/xa-mass-worker-pack/README.md` after it moves
- archived `doc/JAVA_EXTERNAL_SDK_ROADMAP.md` and
  `doc/JAVA_EXTERNAL_SDK_INVENTORY.md`

## `samples/dev` Consumer Classification

Current repo search shows these tracked consumers:

- `integrations/samples/dev/scenario/launch-workers.mjs` reads `bootstrap.json`, `rules.json`,
  `workers.json`, and `tasks.json`.
- `xa-mass-worker-pack` launches `integrations/samples/dev/scenario/launch-workers.mjs` through
  `SampleWorkerProcessStarter`.
- `integrations/samples/dev/scenario/workers.json` points to Node websocket worker scripts.

No tracked server main-source class reads `integrations/samples/dev/scenario/*.json` directly. The
server main-source control-console scenario uses
`ControlConsoleScenarioBootstrapDataProvider` and `MassSdkApplication` /
`MassRuntimeControl` directly. SBE-0 still needs method-level classification of
that provider before deleting or moving server-owned seeding logic.

## Server Bootstrap Switch Evidence

Current repo search found
`mass.control-console.scenario.enabled` in:

- `ControlConsoleScenarioBootstrapConfiguration`
- `xa-mass-server/src/main/resources/application-dev.yml`
- roadmap text

`application-dev.yml` sets it to `true`. After SBE-1 this dev switch only
controls catalog/project/submitter metadata bootstrap. It no longer creates
tasks, task items, WorkerGroups, adapter nodes, or workers from server main
source. SBE-1 therefore removed server-owned workload seeding while preserving
the narrower dev metadata path needed by external launchers and local console
flows.

`mass.mock.bootstrap.*` is test-source fixture configuration in
`TestDevBootstrapConfiguration` and related tests. It is not a main-source
server startup path.

## Implementation Notes For ILC-1

- Move tracked files only; ignore generated `target/` directories.
- Do not keep old root `samples/` wrappers or redirect scripts.
- Do not add Java websocket/socket samples to the root reactor in the same
  change.
- Update all in-repo references in the same commit as the file move.
- Run `rg "samples/"` after the move. Remaining matches must either be updated
  paths under `integrations/samples/...` or explicitly historical text.

## ILC-1 Verification

Historical ILC-1 commands, no longer current after Java SDK adoption removed
the Java samples:

```bash
mvn -pl integrations/samples/java/worker-polling -am -DskipTests package
mvn -q -f integrations/samples/java/worker-websocket/pom.xml -DskipTests package
mvn -q -f integrations/samples/java/worker-socket/pom.xml -DskipTests package
mvn -pl xa-mass-server -am -Dtest=JavaPollingWorkerBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Current Java SDK proof commands:

```bash
mvn -pl sdk/xa-mass-java-sdk test
mvn -pl integrations/xa-mass-scenario-launcher -am test
mvn -pl xa-mass-server -am -Dtest=JavaScenarioLauncherBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Run Node black-box tests if the environment has Node available and the change
touches Node process helpers:

```bash
mvn -pl xa-mass-server -am -Dtest=NodePollingWorkerBlackBoxIntegrationTest,NodeWebSocketWorkerBlackBoxIntegrationTest,NodeSocketWorkerBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```
