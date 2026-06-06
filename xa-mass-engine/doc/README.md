# Engine Docs

Status: current engine-local documentation index.

The module root should stay small. Keep `xa-mass-engine/README.md` as the fast
entry and place detailed current/future owner docs under this directory.

## Layout

- `baseline/`: current truth documents and executable-owner expectations.
- `measurements/`: local validation records and measurement context; these are
  evidence notes, not performance guarantees.
- `notes/`: lightweight observations and brainstorms that are not accepted
  roadmap scope.
- `roadmap/`: future or deferred direction that is not current behavior.
- completed historical plans live outside the active engine read map and should
  be used only for changelog-style audits.

## Current Baselines

- [KERNEL_CONVERGENCE_MATRIX.md](baseline/KERNEL_CONVERGENCE_MATRIX.md)
- [PLATFORM_SCHEDULING_PLANE_BEHAVIOR_NEUTRAL_AUDIT.md](baseline/PLATFORM_SCHEDULING_PLANE_BEHAVIOR_NEUTRAL_AUDIT.md)
- [PLATFORM_SCHEDULING_PLANE_PUBLIC_VOCABULARY_CHECKPOINT.md](baseline/PLATFORM_SCHEDULING_PLANE_PUBLIC_VOCABULARY_CHECKPOINT.md)
- [PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md](baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md)
- [PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md](baseline/PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md)
- [SCHEDULING_CORRECTNESS_MATRIX.md](baseline/SCHEDULING_CORRECTNESS_MATRIX.md)
- [SCHEDULING_KERNEL_BASELINE.md](baseline/SCHEDULING_KERNEL_BASELINE.md)
- [RUNTIME_BOUNDARY_BASELINE.md](baseline/RUNTIME_BOUNDARY_BASELINE.md)
- [STORAGE_BASELINE.md](baseline/STORAGE_BASELINE.md)
- [EVENT_OWNER_BOUNDARY.md](baseline/EVENT_OWNER_BOUNDARY.md)

## Measurements

- [MATCH_THROUGHPUT_NOTE.md](measurements/MATCH_THROUGHPUT_NOTE.md)

## Notes

- [ENGINE_MAINLINE_CONVERGENCE_NOTES.md](notes/ENGINE_MAINLINE_CONVERGENCE_NOTES.md)

## Roadmaps

Active or future direction only. Completed convergence records are archived
below and must not stay in this list.

- [PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md](roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md)
- [TASK_RUNTIME_PROFILE_DESIGN.md](roadmap/TASK_RUNTIME_PROFILE_DESIGN.md)
- [TASK_POLICY_PRESET_CONVERGENCE_ROADMAP.md](roadmap/TASK_POLICY_PRESET_CONVERGENCE_ROADMAP.md)
- [WORKER_MATCH_UPGRADE_ROADMAP.md](roadmap/WORKER_MATCH_UPGRADE_ROADMAP.md)
- [WORKER_SLOT_REGISTRY_ROADMAP.md](roadmap/WORKER_SLOT_REGISTRY_ROADMAP.md)

Historical archive entries are deliberately omitted from this README. Current
engine work should start from baselines and active roadmaps above.
