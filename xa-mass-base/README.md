# xa-mass-base

## Role

- shared models, enums, and core abstractions
- messaging and event bus infrastructure
- JSON DSL and related support code

## Current Status

- contains both active and historical infrastructure paths
- `old.eventbus` still appears in the verified runtime path
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
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
