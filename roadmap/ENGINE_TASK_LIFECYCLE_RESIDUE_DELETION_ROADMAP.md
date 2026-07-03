# Engine Task Lifecycle Residue Deletion Roadmap

Status: proposed follow-up after
`TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md`.

## Purpose

Delete the old engine task lifecycle owner residue after the task-runtime
score-band lifecycle path is proven as the serving mainline.

This roadmap does not define a new lifecycle model. It consumes the TRLC target:
task lifecycle commands and score-band scheduling truth belong to
`xa-mass-task-runtime` plus `sdk/xa-mass-task-runtime-starter-sdk`; engine owns
worker selection, dispatch orchestration, result consumption, trace, resource
release, and projection around runtime outcomes.

## Preconditions

- Embedded SDK task lifecycle commands no longer call `MassEngine`, engine
  `TaskCommandPort`, `TaskManager`, or `TaskLifecycleService`.
- Server command routes reach the same embedded SDK command path.
- Engine scheduling consumes task-runtime score candidates and does not use
  `TaskStatus.READY` / `TaskStatus.RUNNING` as dispatch admission truth.
- Active lease creation, result apply, retry/finality, lease repair, and
  projected terminal read are proven on the same task-runtime owner path.
- TRLC guards are green and old engine lifecycle code is classified as frozen
  residue, not fallback production behavior.

## Deletion Candidates

Primary candidates:

- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskCommandPort.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskQueryPort.java`
- `EngineConfig.ensureTaskManager()`
- production `new TaskManager(...)`
- `TaskManager.syncRuntimeSchedulerEligibility(...)`
- `TaskRuntimeServingLane.syncSchedulerEligibility(...)`
- `TaskRuntimeServingLane.updateSchedulerEligibility(...)` and shell-status to
  runtime-score mapping helpers
- `MassEngine` task lifecycle command methods that delegate to engine
  `TaskCommandPort`
- old guards/tests whose only purpose is preserving engine lifecycle sync

Dependent candidates to classify before deletion:

- `TaskShellLifecycleMaintenancePort`
- `TaskStateRuntimePort`
- `TaskStateResolver`
- `TaskStateValidator`
- max-runtime deadline termination through `TaskShellLifecycleMaintenancePort`
- legacy tests that create tasks by constructing `TaskManager`

## Non-Goals

- Do not reintroduce a renamed `TaskManager`.
- Do not move `TaskLifecycleService` logic into a thinner engine service.
- Do not preserve compatibility adapters for old engine command/read ports.
- Do not redesign server HTTP DTOs or frontend views unless deletion exposes a
  real missing projection field.

## Slices

1. Inventory production and test callers of the deletion candidates.
2. Replace remaining production callers with task-runtime command/read-view,
   task-runtime maintenance, or engine orchestration-specific code.
3. Rewrite tests that still rely on `TaskManager` as a lifecycle owner.
4. Delete the candidates and remove stale guards that protect old lifecycle
   sync.
5. Add final guards rejecting renamed equivalents that combine command, query,
   lifecycle maintenance, state runtime, and serving-lane assembly.

## Completion Criteria

- No production source imports or constructs `TaskManager`.
- No production source imports engine `TaskCommandPort` / `TaskQueryPort` as a
  task lifecycle/read surface.
- No engine service owns approve/reject/block/pause/resume/append/cancel/
  terminate lifecycle commands.
- No scheduling code maps `TaskStatus` to task-runtime score.
- Owner docs describe engine as orchestration around task-runtime truth, not as
  the lifecycle command owner.
