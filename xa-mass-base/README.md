# xa-mass-base

Status: current base module owner README.

## Role

- shared base models, enums, and infrastructure
- messaging and event bus infrastructure
- legacy JSON DSL and related support code while
  [JSON_DSL_BOUNDARY_CONVERGENCE_ROADMAP.md](JSON_DSL_BOUNDARY_CONVERGENCE_ROADMAP.md)
  decides whether to remove or move it out of base
- Maven module name is `xa-mass-base`; Java packages remain under `com.xa.mass.base` for compatibility

## Current Status

- contains the active EventBus implementation under `channel/eventbus/core` and `channel/eventbus/event`
- the legacy EventBus compatibility package has been removed from the active source tree
- JSON DSL currently keeps `qlexpress4` in this module; treat that as boundary
  residue, not as base model truth
- do not assume every README under `src/main/java/**` describes current production behavior

## Start Here

- `src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`
- `src/main/java/com/xa/mass/base/model/Task.java`
- `src/main/java/com/xa/mass/base/model/TaskMsg.java`

## Boundaries

- when debugging lifecycle truth, trust `TaskStatus` and mainline call sites over infra design docs
- when debugging event bus behavior, inspect actual call sites before reading architecture READMEs
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../xa-mass-testing/VERIFIED_RUNBOOK.md`](../xa-mass-testing/VERIFIED_RUNBOOK.md)

