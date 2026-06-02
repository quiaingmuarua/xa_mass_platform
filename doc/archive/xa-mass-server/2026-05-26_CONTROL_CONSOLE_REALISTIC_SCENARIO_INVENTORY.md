# Control Console Realistic Scenario Inventory

Last updated: 2026-06-02

Status: completed and archived migration inventory.

Archived on 2026-06-02 with the completed control-console realistic scenario
roadmap. This inventory is historical context only.

This inventory records the old demo-shaped defaults before the control-console
scenario migration. It is a guardrail for the migration, not a compatibility
contract.

## Default Seed Paths To Replace

These call sites owned the old backend-hosted console defaults and must migrate
to the probe scenario in the same convergence path:

- `xa-mass-server/src/main/java/com/xa/mass/server/DevDemoBootstrapConfiguration.java`
  registered `demoApp`, `demoOps`, `demo.dispatch`, and `demo.dispatch.gb`.
- `xa-mass-server/src/main/java/com/xa/mass/server/bootstrap/DevDemoBootstrapDataProvider.java`
  generated `demo-worker-*` workers, lane WorkerGroups, demo task shells, and
  demo item payloads.
- `xa-mass-server/src/test/java/com/xa/mass/server/bootstrap/DevDemoBootstrapDataProviderTest.java`
  asserted old default seed names and lifecycle mix.
- Frontend mock catalog/task-starter data used `demoApp` and `demo.dispatch` as
  the first visible console story.

## Tests To Migrate With The Default Scenario

These tests describe the default console seed or console-visible catalog and
should assert probe names after migration:

- default bootstrap/provider tests
- catalog API smoke tests for the default project/event catalog
- frontend catalog/project/task-starter mocks and tests
- resource page tests whose fixtures represent the default console story

## Tests That May Keep Local Fixture Names

Some lifecycle, API auth, task controller, and transport E2E tests use
`demoApp` or `demo.dispatch` only as local fixture strings. They are not default
console seed data and do not require compatibility paths. They may keep local
fixture names until touched for their owning behavior, as long as they do not
claim to represent default bootstrap output.

## Removed Default Vocabulary

The default control-console scenario must not create these resources:

```text
demoApp
demoOps
demo.dispatch
demo.dispatch.gb
demo-worker-*
```

Use the probe scenario names from
[`2026-05-26_CONTROL_CONSOLE_REALISTIC_SCENARIO_ROADMAP.md`](./2026-05-26_CONTROL_CONSOLE_REALISTIC_SCENARIO_ROADMAP.md)
for historical context. Active worker capability hardening belongs to
[`../../../INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`](../../../INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md).
