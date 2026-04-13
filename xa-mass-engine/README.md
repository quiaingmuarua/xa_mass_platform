# xa-mass-engine

## Role

- mainline business logic
- task lifecycle and progress tracking
- device assignment and rule management

## Current Status

- this is the active production path
- `v2` exists in this module but is not the current mainline
- mainline regression work should target current engine tests, not historical `v2` tests

## Start Here

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/DeviceManager.java`
- `src/main/java/com/xa/mass/engine/rules/RuleManager.java`

## Boundaries

- do not treat `src/test/java/com/xa/mass/engine/v2/**` as current regression
- do not assume scheduler stubs represent the current runtime path for `READY -> RUNNING`
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`../doc/engine/任务执行流.md`](../doc/engine/任务执行流.md)
