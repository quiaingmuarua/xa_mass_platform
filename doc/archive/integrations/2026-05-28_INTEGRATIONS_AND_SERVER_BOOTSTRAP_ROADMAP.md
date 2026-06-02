# Integrations Layout And Server Bootstrap Roadmap

Status: implemented mainline direction. ILC-0 layout inventory is complete in
[`INTEGRATIONS_LAYOUT_INVENTORY.md`](./2026-05-28_INTEGRATIONS_LAYOUT_INVENTORY.md).
ILC-1 sample path convergence, ILC-2 worker-pack movement, SBE-0 server
bootstrap classification, and SBE-1 server main-source scenario seeding removal
are complete. SBE-2 test fixture preservation and SBE-3 external scenario
documentation are complete for the current public-API dev launcher.

This roadmap covers two related but independently implementable tracks:

1. Move external integration assets out of the repository root and make samples
   language-first under `integrations/`.
2. Remove server-owned demo task and worker generation from normal server
   startup, so realistic scenarios enter through public API or SDK calls.

These tracks should share one roadmap because they define the same product
boundary: the server is the platform host, while SDKs, workers, launchers, and
samples are external integration assets. They should not be implemented as one
large code change. Keep phase-sized commits so build/path regressions and
startup-behavior regressions can be isolated.

## Current Facts

- `sdk/xa-mass-java-sdk` already exists and is the pure external Java
  client SDK.
- external worker samples have converged under `integrations/samples`; Java
  samples were later retired by
  [`INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md`](./2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md),
  while Node samples and `dev/scenario` remain adapter/dev fixtures.
- `integrations/xa-mass-worker-pack` is the official worker reference and
  sample/dev capability module; Maven artifactId remains `xa-mass-worker-pack`.
- The dev sample launcher already uses public task and worker APIs for many
  operations, and ILC-1 moves its path assumptions under
  `integrations/samples/dev/scenario`.
- `xa-mass-server` still has main-source dev metadata bootstrap code that can
  register catalog and submitters when explicitly enabled by dev profile
  properties, but main-source task, worker, and WorkerGroup scenario seeding has
  been removed.
- Test sources also contain fixture bootstraps. Those are not the same concern
  as server main-source startup and should be classified before removal.

## Boundary Decision

Use `integrations/` as the repository home for caller-facing integration
artifacts:

```text
integrations/
  xa-mass-java-sdk/
  xa-mass-worker-pack/
  xa-mass-scenario-launcher/
  samples/
    node/
      worker-polling/
      worker-websocket/
      worker-socket/
    dev/
      scenario/
```

Java sample leaves shown in older ILC inventory text were transitional and are
now retired. Java proof lives in `xa-mass-java-sdk` plus
`xa-mass-scenario-launcher`; Node samples remain language-first adapter
fixtures. Transport is an implementation dimension inside each language sample,
not the top-level repository story.

`integrations/` is an ownership boundary, not a claim that every module under it
has identical dependency purity. `sdk/xa-mass-java-sdk` must remain a
pure remote client. `integrations/xa-mass-worker-pack` may temporarily keep
embedded SDK or transport dependencies where it still hosts reference realtime
worker paths.

## Server Startup Decision

The server should start as a clean platform host:

- no default task creation from server main sources
- no default worker registration from server main sources
- no default WorkerGroup registration from server main sources
- no in-process demo data path that bypasses public task or worker APIs

Realistic local/demo scenarios should be external launchers or SDK clients:

- WorkerGroup declaration goes through `/worker-api/v1/worker-groups` or an
  external SDK wrapper.
- adapter-node, node/group binding, worker registration, online, heartbeat, and
  result submission go through worker API or worker session SDK code.
- task shell creation, item append, seal, approve, and control commands go
  through task API or external SDK code using API keys.
- catalog, submitter, and rule bootstrap may remain behind sample/admin
  bootstrap APIs until a public admin SDK exists, but those APIs must not create
  tasks, workers, or WorkerGroups on behalf of server startup.

This keeps the local control-console scenario realistic: the platform is empty
until an external actor registers capabilities and submits work.

## Non-Goals

- Do not change engine matching, assignment, lease, or result semantics.
- Do not introduce compatibility aliases for old root `samples/` or
  `xa-mass-worker-pack` paths. Update in-repo callers.
- Do not make `xa-mass-server` call `xa-mass-java-sdk` internally to simulate an
  external client. The server should simply stop owning scenario task/worker
  generation.
- Do not delete test-only fixture bootstrap until each fixture has been
  classified and an explicit replacement exists.
- Do not publish integration artifacts to Maven Central as part of this
  roadmap.
- Do not design a public catalog/rule/admin SDK unless the lack of one blocks
  removing server-owned task or worker generation.
- Do not use old path-preserving wrapper scripts as a migration strategy.

## Track A: Integration Layout Convergence

### ILC-0 Inventory

Status: complete. See
[`INTEGRATIONS_LAYOUT_INVENTORY.md`](./2026-05-28_INTEGRATIONS_LAYOUT_INVENTORY.md).

Scope:

- Inventory all root `samples/` references in Maven modules, server E2E support,
  launch scripts, READMEs, runbooks, and sample configs.
- Inventory all root `xa-mass-worker-pack` references in Maven modules,
  docs, CI commands, and test helpers.
- Produce an authoritative target path table for every moved directory.
- Split the inventory into Maven module path changes versus plain filesystem
  reference changes. During ILC-1 the Java polling sample was a root reactor
  module, while Node samples and Java realtime samples were launched or built
  by explicit file paths. Those Java sample paths are now retired by the Java
  SDK adoption roadmap.
- Decide whether Java websocket/socket samples remain standalone POM samples or
  become root reactor modules after the move. This was an ILC-time decision;
  both Java realtime samples are now removed instead of promoted.

Acceptance:

- The roadmap or a linked inventory lists each move from old path to new path.
- Path-sensitive tests and scripts are identified before moving files.
- Maven module moves and plain file-reference moves are listed separately.
- Java websocket/socket sample POMs have an explicit reactor decision:
  standalone remains standalone, or reactor inclusion is added as deliberate
  scope.
- The target sample layout is language-first and does not preserve the old
  transport-first directory as a parallel tree.

### ILC-1 Move Samples Under `integrations/samples`

Status: complete, then superseded for Java samples. Root `samples/` has been
removed from tracked files. Node samples live under `integrations/samples/node`,
and the dev scenario launcher/configs live under
`integrations/samples/dev/scenario`. The transitional Java samples that lived
under `integrations/samples/java` were later retired by the Java SDK adoption
roadmap.

Scope:

- Move Java samples to `integrations/samples/java/...` during ILC-1, then
  retire them once scenario-launcher provides the Java SDK proof.
- Move Node samples to `integrations/samples/node/...`.
- Move dev scenario launcher/configs to `integrations/samples/dev/scenario/...`
  or a similarly explicit dev scenario path.
- Update root Maven modules, sample POM relative paths, server black-box process
  helpers, sample README commands, and launcher path resolution.
- Update `sdk/xa-mass-java-sdk` docs to point at the new Java sample
  path.
- Treat Node samples as filesystem/runtime assets, not Maven reactor modules:
  update launcher configs, process-helper paths, and README commands, but do
  not invent Maven wrappers for Node-only samples.
- Before moving `samples/dev/*`, verify whether those JSON files are consumed
  only by the external Node launcher or also by server-side bootstrap code. If a
  server-side consumer exists, cut that dependency in SBE-0/SBE-1 before the
  move rather than preserving a root-path fallback.

Acceptance:

- No root-level `samples/` directory remains in tracked files.
- Root reactor built the Java polling sample from its new path during ILC-1;
  that module is now removed.
- Node worker black-box tests resolve their sample paths from the new layout.
  Java worker black-box proof now uses `JavaScenarioLauncherBlackBoxIntegrationTest`.
- Node sample movement does not require POM updates; it requires runtime path,
  launcher config, and documentation updates.
- `rg "samples/"` has no stale root-path references, except historical notes
  explicitly marked as old paths if any are retained.

### ILC-2 Move Worker Pack Under `integrations/xa-mass-worker-pack`

Status: complete. The module now lives at `integrations/xa-mass-worker-pack`,
with the Maven artifactId unchanged.

Scope:

- Move `xa-mass-worker-pack` from repo root to `integrations/xa-mass-worker-pack`.
- Keep Maven artifactId unchanged.
- Update root reactor module path, docs, CI commands, and references in server
  E2E helpers.
- Keep dependency cleanup separate from the directory move unless a dependency
  break is directly caused by the move.
- Audit server E2E process helpers that build or execute worker-pack artifacts
  and update them to resolve the new integration path.

Acceptance:

- No root-level `xa-mass-worker-pack/` directory remains in tracked files.
- Reactor build and impacted worker-pack tests pass from the new path.
- Server E2E tests that reference worker-pack build or execute it from
  `integrations/xa-mass-worker-pack`, not from a stale root path.
- Documentation describes worker-pack as an integration/reference worker asset,
  not a platform kernel module.

## Track B: Server Bootstrap Extraction

### SBE-0 Bootstrap Classification

Status: complete. See
[`SERVER_BOOTSTRAP_CLASSIFICATION.md`](./2026-05-28_SERVER_BOOTSTRAP_CLASSIFICATION.md).

Scope:

- Classify every server startup/bootstrap path into:
  - main-source server platform initialization
  - dev/demo scenario data
  - sample/admin catalog or rule bootstrap
  - test-only fixture bootstrap
- Specifically audit `ControlConsoleScenarioBootstrapConfiguration`,
  `ControlConsoleScenarioBootstrapDataProvider`, `MockRuntimeDataLoader`, and
  E2E support code.
- Treat `ControlConsoleScenarioBootstrapDataProvider` as a likely mixed owner:
  classify method-level responsibilities instead of deciding whether to keep or
  delete the class as a whole. At minimum, separate catalog/event/submitter/rule
  metadata initialization from task creation, item append, worker registration,
  and WorkerGroup declaration.
- Classify catalog, submitter, and rule bootstrap separately:
  - catalog/event/project metadata may remain server-owned dev metadata if it is
    treated like platform initialization rather than workload creation
  - submitter/API-key bootstrap may remain server-owned dev metadata only if the
    external scenario has a documented credential source
  - rule bootstrap may remain behind sample/admin APIs until a public admin SDK
    exists, but it must not create tasks, workers, or WorkerGroups
- Classify `integrations/samples/dev/scenario/launch-workers.mjs` and
  `samples/dev/{bootstrap,rules,workers,tasks}.json` before moving them. The
  inventory must state whether each file is external-launcher input,
  server-startup input, or both.
- Audit test fixtures for direct or indirect dependencies on
  `ControlConsoleScenarioBootstrapConfiguration` and
  `ControlConsoleScenarioBootstrapDataProvider`. If tests rely on the provider,
  decide whether to keep a test-scope fixture copy/helper before deleting
  main-source scenario code.
- Confirm whether `mass.control-console.scenario.enabled` is ever set by repo
  config, launch scripts, or CI. If it is not set by default, SBE-1 is primarily
  dead-code removal plus external scenario replacement rather than a default
  startup behavior change.

Acceptance:

- Each path that creates tasks, appends items, registers workers, or declares
  WorkerGroups has an owner classification.
- The classification separates main-source runtime behavior from test fixtures.
- Catalog/event/project bootstrap, submitter/API-key bootstrap, and rule
  bootstrap each have an explicit keep/move/delete decision.
- The external scenario credential source is documented: hard-coded dev keys,
  environment-provided keys, or a future API-key creation path.
- `ControlConsoleScenarioBootstrapDataProvider` has a method-level ownership
  table for metadata bootstrap versus scenario task/worker seeding.
- `samples/dev/*` has a consumer table showing external launcher and server
  bootstrap dependencies before any path move.
- Test fixture dependencies on control-console scenario classes are listed, or
  the inventory states that none exist.
- The effective default for `mass.control-console.scenario.enabled` is recorded
  with evidence from repo config or launch scripts.
- Any remaining server-owned catalog/submitter/rule setup is explicitly
  justified as platform metadata bootstrap, not task/worker scenario seeding.

### SBE-1 Externalize Scenario Task And Worker Seeding

Status: complete. Server main-source task/worker seeding is removed. The
external dev scenario launcher uses public HTTP APIs, creates realistic
multi-project task data, registers realtime workers as external processes, and
registers a 100-worker polling phone-device group for matching review.

Scope:

- Remove main-source dev scenario task creation and item append from server
  startup.
- Remove main-source dev scenario worker and WorkerGroup registration from
  server startup.
- Move equivalent local/demo scenario creation into an external launcher or SDK
  client under `integrations/samples/dev/scenario`.
- The external scenario launcher must use public HTTP/API contracts or a pure
  external SDK client. The current Node launcher uses raw public HTTP APIs; a
  future Java launcher may use `xa-mass-java-sdk`. It must not import
  `xa-mass-embedded-sdk`, `MassSdkApplication`, `MassRuntimeControl`, or server
  bootstrap classes.
- Use API keys and public task/worker endpoints for scenario work and worker
  topology.
- Keep external scenario data deterministic and rich enough for console review:
  multiple projects, polling workers, realtime workers, at least one
  fingerprint-like worker attribute group, and task item batches large enough to
  exercise matching.
- Keep heavyweight matching-review tasks sealed but unapproved by default unless
  the scenario intentionally validates execution. This lets the console inspect
  realistic task/work/worker matching state without making dev startup run 1000+
  items immediately.

Acceptance:

- Starting `xa-mass-server` without running an external scenario does not
  create demo tasks, workers, or WorkerGroups.
- Running the external scenario creates tasks through `/api/v1/tasks` and task
  item append APIs.
- Running the external scenario declares WorkerGroups and workers through
  `/worker-api/v1/*`.
- The external scenario launcher compiles/runs as an external integration
  client and has no dependency on embedded runtime control APIs.
- Submitter/API-key usage is explicit in the scenario docs and can be supplied
  from environment variables or documented dev credentials.
- Control-console pages still have realistic data after the external scenario
  runs, but that data is no longer server-owned startup state.
- The external scenario contains a 100-worker polling group with
  `fingerprintProfile` attributes and a 1000-item `probe.phone.metadata` task
  whose `targetWorkerAttributes` match only the intended fingerprint subset.

### SBE-2 Preserve Explicit Test Fixtures

Status: complete. Test-only bootstrap remains in test sources and was not
converted into a server main-source fallback.

Scope:

- Keep test-only data loaders under test sources where tests need deterministic
  internal setup.
- Rename or document test fixture classes if their names imply server runtime
  bootstrap.
- Update tests that relied on main-source demo startup to explicitly create
  their own task/worker state through helper APIs.

Acceptance:

- Tests do not rely on hidden server startup demo tasks or workers.
- Test fixture code remains clearly scoped to `src/test` or test support.
- No failing test is fixed by reintroducing a server main-source demo path.

### SBE-3 Documentation And Runbook Cleanup

Status: complete for the current layout and external dev scenario.

Scope:

- Update server README and verified runbook to describe clean startup.
- Update sample quickstarts to run server first, then run an external scenario.
- Update Java SDK and external worker quickstarts with the new sample paths.
- Remove wording that says dev server startup seeds demo tasks/workers
  in-process.

Acceptance:

- Docs distinguish clean server startup from optional external scenario launch.
- Commands use `integrations/samples/...` paths.
- `mass.control-console.scenario.enabled` semantics are narrowed so they cannot
  imply server-owned task/worker generation.

## Suggested Implementation Order

1. ILC-0 inventory.
2. ILC-1 sample move.
3. ILC-2 worker-pack move.
4. SBE-0 bootstrap classification.
5. SBE-1 external scenario seeding.
6. SBE-2 test fixture cleanup.
7. SBE-3 documentation and runbook cleanup.

The layout work should happen first because it stabilizes the target location
for the external scenario launcher. The server startup cleanup should not be
mixed into the directory move commit.

## Verification Matrix

Minimum checks after layout phases:

```bash
mvn -pl sdk/xa-mass-java-sdk -am test
mvn -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
mvn -pl integrations/xa-mass-worker-pack -am test
```

Minimum checks after server bootstrap extraction:

```bash
mvn -pl xa-mass-server -am -Dtest=JavaScenarioLauncherBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl xa-mass-server -am -Dtest=DevSampleWorkerLauncherIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl xa-mass-server -am -Dtest=CleanServerStartupIntegrationTest,ServerMainSourceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Add a clean-start smoke proof when the implementation removes server-owned
scenario data:

- start server with the dev profile and sample/mock worker launch disabled
- query task list / worker capability read models
- verify no demo tasks, workers, or WorkerGroups exist until the external
  scenario launcher runs

## Risks

- Path fallout is broad: Maven reactor modules, README commands, E2E process
  helpers, Node launcher configs, and Java sample POM relative paths all need
  updates.
- Clean server startup can make the console look empty until a scenario is run.
  This is intentional, but docs and launcher commands must make the workflow
  explicit.
- Catalog/rule bootstrap is not yet a fully public SDK surface. Do not let that
  block removing task/worker generation from server startup; keep the narrower
  sample/admin bootstrap only where metadata setup still requires it.
- Submitter/API-key bootstrap is a boundary risk. If server dev metadata creates
  the credentials, the external scenario must document those dev credentials or
  accept them through environment variables. Hidden in-process credentials would
  make the scenario look external while still depending on server-owned state.
- Existing tests may implicitly rely on hidden startup data. Those tests should
  create their own fixtures or call an explicit external scenario helper.
- `samples/dev` is a sequencing risk: moving files before cutting server-side
  consumers can either break startup or lead to root-path compatibility shims.
  Do the consumer classification first.
- `ControlConsoleScenarioBootstrapDataProvider` is likely not all good or all
  bad. Treat it as a mixed implementation and move only the task/worker seeding
  responsibility out of server startup.

## Owner Review

This should be two implementation tracks under one roadmap. Combining both into
one code change would make failures hard to diagnose and would blur the owner
boundary being established.

The key architectural invariant is simple: server startup owns platform
availability, not demo workload existence. External workers, WorkerGroups, and
tasks should appear because an external caller registered or submitted them
through the same public path a real integration would use.
