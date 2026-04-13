# xa-mass-engine

## Role

- mainline business logic
- task lifecycle and progress tracking
- device assignment and rule management
- core library surface for state-machine correctness and pluggable matching behavior

## Current Status

- this is the active production path
- active production code lives under `src/main/java/com/xa/mass/engine`
- historical `v2` material has been moved under `archive/v2/`
- mainline regression work should target current engine tests, not historical `v2` tests

## Start Here

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/DeviceManager.java`
- `src/main/java/com/xa/mass/engine/rules/RuleManager.java`

## Boundaries

- do not treat `archive/v2/**` as current regression
- do not assume scheduler stubs represent the current runtime path for `READY -> RUNNING`
- prefer extending assignment through engine strategy interfaces instead of hard-coding API or demo-layer behavior
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`../doc/engine/TASK_EXECUTION_FLOW.md`](../doc/engine/TASK_EXECUTION_FLOW.md)
