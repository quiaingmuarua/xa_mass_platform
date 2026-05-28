# Server Bootstrap Classification

Status: SBE-0 inventory, updated after SBE-1 removal of server main-source
scenario seeding for
[`INTEGRATIONS_AND_SERVER_BOOTSTRAP_ROADMAP.md`](./INTEGRATIONS_AND_SERVER_BOOTSTRAP_ROADMAP.md).

Date: 2026-05-28.

This document classifies current server and sample bootstrap paths before
removing server-owned task and worker scenario seeding.

## Classification Summary

| Path | Scope | Current owner | Decision |
|---|---|---|---|
| `xa-mass-server/src/main/java/com/xa/mass/server/ControlConsoleScenarioBootstrapConfiguration.java` | dev-profile catalog, submitter, and scenario loader wiring | server dev shell | split in SBE-1: keep or replace metadata bootstrap only; remove task/worker data-load runner |
| `xa-mass-server/src/main/java/com/xa/mass/server/bootstrap/ControlConsoleScenarioBootstrapDataProvider.java` | generated WorkerGroups, workers, tasks, and task items | server main-source scenario seeding | removed in SBE-1 |
| `integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/api/SampleBootstrapController.java` | dev sample catalog and rule bootstrap API | integration/sample admin surface | keep as temporary dev-only sample/admin API until a public admin SDK exists |
| `integrations/samples/dev/scenario/launch-workers.mjs` | external launcher for sample catalog, rules, worker registration, worker processes, and seed tasks | external integration asset | keep; this is the preferred scenario entry path |
| `xa-mass-server/src/test/java/com/xa/mass/server/TestDevBootstrapConfiguration.java` | test-only dev bootstrap wiring | test fixture | keep in test scope |
| `xa-mass-server/src/test/java/com/xa/mass/server/bootstrap/MockRuntimeDataLoader.java` | JSON-driven test workers, rules, and tasks | test fixture | keep in test scope only |

## Metadata Decisions

| Bootstrap type | Current path | SBE-1 decision |
|---|---|---|
| Catalog events and projects | `ControlConsoleScenarioBootstrapConfiguration.registerProbeEvents(...)` plus three project registrations | may remain as dev metadata only if still required for local console shell; must not create workers or tasks |
| Submitters/API keys | `ControlConsoleScenarioBootstrapConfiguration.controlConsoleScenarioSubmitterBootstrapRunner(...)` | may remain as dev metadata only if documented as local credentials; external launcher already uses `bootstrap.json` and sample API credentials |
| Rules | `SampleBootstrapController.bootstrapRules(...)`; test fixtures via `MockRuntimeDataLoader.loadRules(...)` | keep sample API and test fixtures; do not promote server startup rule replacement as mainline behavior |
| WorkerGroups, adapter nodes, bindings, workers | removed `ControlConsoleScenarioBootstrapDataProvider.registerTopologyAndWorkers(...)` | create through external launcher or SDK/API calls |
| Tasks and items | removed `ControlConsoleScenarioBootstrapDataProvider.createTasks(...)` | create through external launcher or SDK/API calls |

## Credential Sources

The external dev launcher has two credential sources:

| Credential | Default | Used by | Source |
|---|---|---|---|
| `SAMPLE_BOOTSTRAP_KEY` | `dev-bootstrap-key` | `/sample-api/bootstrap/catalog`, `/sample-api/bootstrap/rules` | `SampleBootstrapController`, property `sample.bootstrap.api-key` |
| `MASS_TASK_SUBMITTER_KEY` | `crawler-submitter-key` | `/api/v1/tasks`, task item append, task commands | `integrations/samples/dev/scenario/bootstrap.json` submitter seed |

Worker registration credentials are defined per worker in
`integrations/samples/dev/scenario/workers.json` through `workerKey`.

The server-side control-console scenario credentials are currently hard-coded
dev credentials in `ControlConsoleScenarioBootstrapConfiguration`. They may
remain only as explicit dev metadata if SBE-1 keeps a dev catalog/submitter
bootstrap path.

## `ControlConsoleScenarioBootstrapDataProvider` Ownership Table

| Method / data | Behavior | Classification | SBE-1 action |
|---|---|---|---|
| constructor and config fields | normalizes profile, counts, batch size, retry count, approval mode | scenario config | remove from server main source with provider |
| `loadInto(...)` | invokes topology/worker registration and task creation | scenario seeding | remove from server startup |
| `registerTopologyAndWorkers(...)` | registers adapter nodes, WorkerGroups, node-group bindings, and workers | task/worker scenario data | externalize |
| `registerWorkers(...)` | creates 100+ generated polling/realtime workers | worker scenario data | externalize |
| `declareGroup(...)` | declares WorkerGroups and node bindings | worker topology scenario data | externalize |
| `registerWorker(...)` | registers workers and marks polling workers online | worker scenario data | externalize |
| `deviceAttributes(...)` | generates fingerprint-like worker attributes | worker matching scenario data | externalize with worker fixtures |
| `createTasks(...)` | creates task shells, appends items, seals/optionally approves | task scenario data | externalize |
| `buildSharedConfig(...)`, `executionOptionsFor(...)`, `buildItems(...)`, `applyEventPayload(...)` | generates realistic task payloads | task scenario data | externalize or move to launcher/client fixture code |
| fixture templates and helper records | deterministic realistic payload templates | scenario fixture data | externalize if still needed |
| accessors `isLocalOnlyProfile`, `configuredTaskCount`, `configuredItemsPerTask`, `fingerprintProfiles` | provider diagnostics/test access | scenario/test support | remove or move to test fixture only |

No method in this provider is platform-kernel initialization. The only reusable
value is its realistic probe scenario shape; ownership should move to an
external launcher/client fixture if still needed.

## `integrations/samples/dev/scenario` Consumer Table

| File | Current consumer | Classification |
|---|---|---|
| `launch-workers.mjs` | manually launched dev process; default launched by `SampleWorkerProcessStarter` | external launcher |
| `bootstrap.json` | `launch-workers.mjs` posts it to `/sample-api/bootstrap/catalog` | external launcher catalog/submitter input |
| `rules.json` | `launch-workers.mjs` posts it to `/sample-api/bootstrap/rules` | external launcher rule input |
| `workers.json` | `launch-workers.mjs` reads it, declares WorkerGroups, registers adapter nodes, binds groups, registers workers, starts Node worker processes | external launcher worker input |
| `tasks.json` | `launch-workers.mjs` creates task shells, appends items, seals, and optionally approves through public task APIs | external launcher task input |

Current search evidence shows no tracked server main-source class reads
`integrations/samples/dev/scenario/*.json` directly.

## Test Fixture Dependencies

| Test fixture path | Dependency | Classification |
|---|---|---|
| `TestDevBootstrapConfiguration` | creates `MockRuntimeDataLoader` and calls `loadInto(app)` when `mass.mock.bootstrap.enabled=true` or missing | test-only fixture bootstrap |
| `MockRuntimeDataLoader` | reads `mock/*.json`, declares WorkerGroups, workers, rules, tasks | test-only fixture loader |
| `ControlConsoleScenarioBootstrapDataProviderTest` | instantiates `ControlConsoleScenarioBootstrapDataProvider` directly | test coverage for main-source scenario provider; remove or replace when provider leaves main source |
| E2E classes with `mass.mock.data.*` properties | depend on test-scope mock fixture JSON, not control-console scenario JSON | test-only fixture data |

SBE-1 removed `ControlConsoleScenarioBootstrapDataProvider` and its unit test.
Any retained scenario generator must live in test or external integration
ownership, not server main source.

## Effective Defaults

`ControlConsoleScenarioBootstrapConfiguration` is active under the Spring
`dev` profile because `application-dev.yml` sets
`mass.control-console.scenario.enabled=true`.

After SBE-1 this default dev wiring registers catalog events, projects, and
submitters only. It does not register adapter nodes, declare WorkerGroups,
register workers, create task shells, append task items, seal tasks, or approve
tasks. The previous count/profile properties were removed from
`application-dev.yml` because server main source no longer owns scenario data
generation.

`TestDevBootstrapConfiguration` is test-scope only and defaults
`mass.mock.bootstrap.enabled` to active in tests through
`matchIfMissing=true`; it must stay separated from server main-source cleanup.
