# xa-mass-engine

## Role

- mainline business logic
- task lifecycle and progress tracking
- worker assignment and rule management
- core library surface for state-machine correctness and pluggable matching behavior

## Current Status

- this is the active production path
- active production code lives under `src/main/java/com/xa/mass/engine`
- historical `v2` / archive engine generations are not part of the current repository snapshot
- mainline regression work should target current engine tests, not historical notes or removed archive tests

## Start Here

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/WorkerManager.java`
- `src/main/java/com/xa/mass/engine/rules/RuleManager.java`

## Boundaries

- do not reconstruct removed `v2` / archive code as current regression
- do not assume scheduler stubs represent the current runtime path for `READY -> RUNNING`
- prefer extending assignment through engine strategy interfaces instead of hard-coding API or demo-layer behavior
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`../doc/engine/TASK_EXECUTION_FLOW.md`](../doc/engine/TASK_EXECUTION_FLOW.md)
  - [`STORAGE_BASELINE.md`](STORAGE_BASELINE.md)
