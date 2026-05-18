# Engine Docs

Status: current engine-local documentation index.

The module root should stay small. Keep `xa-mass-engine/README.md` as the fast
entry and place detailed current/future owner docs under this directory.

## Layout

- `baseline/`: current truth documents and executable-owner expectations.
- `roadmap/`: future or deferred direction that is not current behavior.
- repo-level `../../doc/archive/xa-mass-engine/`: completed historical plans
  kept for changelog-style reference only.

## Current Baselines

- [KERNEL_CONVERGENCE_MATRIX.md](baseline/KERNEL_CONVERGENCE_MATRIX.md)
- [SCHEDULING_CORRECTNESS_MATRIX.md](baseline/SCHEDULING_CORRECTNESS_MATRIX.md)
- [SCHEDULING_KERNEL_BASELINE.md](baseline/SCHEDULING_KERNEL_BASELINE.md)
- [RUNTIME_BOUNDARY_BASELINE.md](baseline/RUNTIME_BOUNDARY_BASELINE.md)
- [STORAGE_BASELINE.md](baseline/STORAGE_BASELINE.md)
- [EVENT_OWNER_BOUNDARY.md](baseline/EVENT_OWNER_BOUNDARY.md)

## Roadmaps

- [TASK_RUNTIME_PROFILE_DESIGN.md](roadmap/TASK_RUNTIME_PROFILE_DESIGN.md)

## Historical Archive

- [EVENT_AND_WORKER_CONTROL_ROADMAP.md](../../doc/archive/xa-mass-engine/EVENT_AND_WORKER_CONTROL_ROADMAP.md)
- [SCHEDULING_UPGRADE_ROADMAP.md](../../doc/archive/xa-mass-engine/SCHEDULING_UPGRADE_ROADMAP.md)
- [WORKER_CONTEXT_RETIREMENT_PLAN.md](../../doc/archive/xa-mass-engine/WORKER_CONTEXT_RETIREMENT_PLAN.md)
- [WORKER_GROUP_CAPABILITY_ROADMAP.md](../../doc/archive/xa-mass-engine/WORKER_GROUP_CAPABILITY_ROADMAP.md)
- [SCHEDULING_KERNEL_GUARDRAILS.md](../../doc/archive/xa-mass-engine/SCHEDULING_KERNEL_GUARDRAILS.md)
- [WORKER_SCHEDULING_VIEW_BASELINE.md](../../doc/archive/xa-mass-engine/WORKER_SCHEDULING_VIEW_BASELINE.md)
- [POLICY_INTERACTION_BASELINE.md](../../doc/archive/xa-mass-engine/POLICY_INTERACTION_BASELINE.md)
- [EVENT_METADATA_OWNER_BOUNDARY.md](../../doc/archive/xa-mass-engine/EVENT_METADATA_OWNER_BOUNDARY.md)
- [SYSTEM_EVENT_OWNER_BASELINE.md](../../doc/archive/xa-mass-engine/SYSTEM_EVENT_OWNER_BASELINE.md)
- [UNIFIED_EVENT_ENVELOPE_ROADMAP.md](../../doc/archive/xa-mass-engine/UNIFIED_EVENT_ENVELOPE_ROADMAP.md)
- [WORKER_COMMAND_LIFECYCLE_ROADMAP.md](../../doc/archive/xa-mass-engine/WORKER_COMMAND_LIFECYCLE_ROADMAP.md)

Archived documents are historical context only. Do not use them as proof of
current implementation behavior; verify against current code, tests, and
baseline docs.
