# Task Contract Policy Internalization Roadmap

Status: active implementation roadmap.

## Summary

External task APIs may continue to expose `TaskContract` as a stable task-shape
preset:

```text
SESSION
BATCH
```

That vocabulary is useful for SDK callers, server DTOs, console display, and
operator reasoning.

The engine kernel should not consume `SESSION` or `BATCH` as behavior truth.
Internally, contract must resolve once into explicit task scheduling policy
values, then all runtime owners consume those policy values:

```text
TaskContract / execution spec / workload inputs
  -> TaskPolicyPresetResolver
  -> ResolvedTaskSchedulingPolicy
  -> terminal / dispatch cadence / claim / retry / result finality /
     backpressure / resource mode
```

The goal is kernel transparency and future independent optimization:

1. Make every contract-implied behavior visible as a named policy field.
2. Keep public API stable while preventing `SESSION/BATCH` from leaking back
   into runtime owners.
3. Allow later optimization of dispatch cadence, idle close, result finality,
   claim, retry, and backpressure without changing public task shape.

## Non-Goals

This roadmap does not:

1. Remove public `TaskContract`.
2. Rename public SDK/server fields in the first slice.
3. Add a persisted policy catalog or project-policy binding implementation.
4. Change default behavior for existing `SESSION` or `BATCH` tasks.
5. Introduce DB migration requirements.
6. Add a compatibility layer for old internal behavior paths.
7. Move task runtime ownership out of `TaskWorkRuntime` or `TaskResultRuntime`.

## Current State

Completed convergence already moved the main behavior consumers behind
`ResolvedTaskSchedulingPolicy`.

Current acceptable direct `TaskContract` reads are limited to:

1. task shell ingress/defaulting
2. public DTO/SDK/snapshot mapping
3. task name/read evidence
4. preset resolution
5. architecture guards and regression tests

Current risk is mostly semantic drift:

- tests and docs still named around `TaskContract` can make future agents think
  contract owns behavior
- `TaskPolicyPresetResolution` still maps `SESSION/BATCH` directly to policy
  values, but the mapping is not visible enough in diagnostics
- `ResolvedTaskSchedulingPolicy` still carries fallback defaults in its compact
  constructor; those defaults are useful safety rails today, but they can become
  a second default owner if preset resolution and resolved-policy construction
  diverge
- `ResolvedTaskSchedulingPolicy.from(Task, TaskRuntimeProfile)` remains a
  convenience path that internally calls preset resolution; it is not the
  `DefaultSchedulingPlaneResolver` mainline, but it should not become an
  alternate policy-resolution entry
- public contract vocabulary and internal policy vocabulary are close enough
  that new feature work can accidentally reintroduce direct contract branching

## Target Shape

Public input remains:

```text
TaskCreateRequest.contract = SESSION | BATCH
```

Kernel-facing resolved view becomes the only behavior contract:

```text
ResolvedTaskSchedulingPolicy
  taskPolicyPreset
  workloadClass
  dispatchCadence
  workerResourceMode
  idleClosePolicy
  claimPolicy
  retryPolicy
  resultFinalityPolicy
  backpressurePolicy
```

Runtime owners must consume only explicit policy fields:

| Owner | Must consume | Must not consume |
| --- | --- | --- |
| terminal policy | `IdleClosePolicy` | `TaskContract` |
| dispatch request / pump | `DispatchCadence` | `TaskContract` |
| worker resource policy | `WorkerResourceMode` | `foreground` direct branch |
| claim options | `ClaimPolicy` | runtime profile direct branch |
| retry options | `RetryPolicy` | runtime profile direct branch |
| result finality | `ResultFinalityPolicy` | `TaskContract` |
| enqueue/backpressure | `BackpressurePolicy` | runtime profile direct branch |

## Core Rules

1. `TaskContract` is public preset input and read evidence, not engine behavior
   truth.
2. Internal runtime owners branch on resolved policy fields, never directly on
   `SESSION/BATCH`.
3. `TaskIntakeStatus` remains the intake-window truth; policy must not redefine
   whether new items may be appended.
4. `eventCode` remains handler/capability evidence; policy must not turn it into
   worker-selection truth.
5. Preset resolution may read public contract fields, but only inside the preset
   resolver boundary.
6. New policy knobs must identify owner, runtime consumer, trace evidence, and
   proof before becoming mainline.
7. Internal tests should be named after the policy behavior they prove, not the
   public preset input, except public API/compatibility tests.
8. No hidden default may live only in comments or enum naming; every behavior
   default must be visible in `ResolvedTaskSchedulingPolicy` or its resolver.
9. Preset-level defaults and per-task overrides are different categories:
   `SESSION/BATCH` preset definitions own preset defaults; task fields such as
   `executionSpec.foreground` may still override derived policy values through
   the resolver layer.

## Phases

### TCPI-0: Inventory And Naming Audit

Goal: prove the current direct contract surface is bounded before further
changes.

Scope:

1. Inventory all production `TaskContract`, `getContract()`, and
   `TaskPolicyPresetSemantics` reads.
2. Classify each read as:
   - ingress/defaulting
   - public mapping/read evidence
   - preset resolution
   - task naming/diagnostic
   - forbidden runtime behavior
3. Inventory tests named `TaskContract*` and classify them as public contract
   proof or internal policy proof.
4. Record which internal behavior tests currently exist, which are missing and
   must be created in later phases, and which tests should remain public
   contract compatibility proof.
5. Inventory runtime owner methods that currently accept `Task` but only need
   policy-derived behavior; classify each as:
   - should receive a sub-policy directly
   - should receive `ResolvedTaskSchedulingPolicy`
   - should keep `Task` because it also needs task identity/status/sharedConfig
6. Record current allowed direct-read list in architecture guard comments or a
   focused baseline section.
7. Record convenience policy-resolution entry points and decide whether they
   stay as package-private/test support or get removed in TCPI-3/TCPI-4.

Acceptance:

1. No forbidden runtime owner directly branches on `SESSION/BATCH`.
2. Allowed direct reads are explicit and source-guarded.
3. Internal policy tests to rename or create are listed.
4. Runtime owner signature convergence targets are listed.
5. Convenience resolver entry points are classified and do not bypass the
   `DefaultSchedulingPlaneResolver -> TaskPolicyPresetResolver` mainline.
6. No behavior change.

### TCPI-1: Resolved Policy Diagnostics

Goal: make kernel behavior transparent without changing semantics.

Scope:

1. First-slice diagnostics land in engine/trace-owned evidence, not in public
   task create request shape.
2. Add or tighten trace/diagnostic evidence for resolved policy fields:
   - `taskPolicyPreset`
   - `dispatchCadence`
   - `workerResourceMode`
   - `idleClosePolicy`
   - `claimPolicy`
   - `retryPolicy`
   - `resultFinalityPolicy`
   - `backpressurePolicy`
3. Ensure task detail/debug/read surfaces can explain why a task behaves like
   `SESSION` or `BATCH` without requiring code inspection.
4. If server read surfaces expose resolved policy evidence, add it as optional
   read-side diagnostics and update server/frontend contract docs in the same
   change.
5. Keep public create/request contract unchanged.

Acceptance:

1. A task created with `contract=BATCH` exposes resolved policy evidence in the
   chosen engine/trace diagnostic surface.
2. A task created with `contract=SESSION` exposes resolved policy evidence in
   the chosen engine/trace diagnostic surface.
3. Diagnostics distinguish public preset input from engine-resolved policy.
4. No runtime owner starts consuming diagnostics as truth.
5. Any server read-surface exposure is additive, documented, and covered by
   server/API proof.

### TCPI-2: Test Vocabulary Convergence

Goal: prevent tests from preserving the old mental model.

Scope:

1. Rename or split internal behavior tests:
   - terminal behavior -> idle-close policy tests
   - dispatch behavior -> dispatch cadence tests
   - result expiry behavior -> result-finality policy tests
   - foreground resource behavior -> worker-resource-mode tests
2. Keep public `TaskContract` tests only where they prove API/default/preset
   mapping.
3. Update proof registry/testing docs if test ownership changes.

Acceptance:

1. Internal tests assert resolved policy behavior directly.
2. Public contract tests assert only public API/default mapping and preset
   compatibility.
3. Test names no longer imply `TaskContract` is the runtime owner.
4. No behavior change.

### TCPI-3: Internal Policy Entry Narrowing

Goal: make `ResolvedTaskSchedulingPolicy` the narrow internal runtime entry.

Scope:

1. Review runtime owner method signatures that still accept `Task` only because
   they need policy-derived behavior, using the TCPI-0 target list.
2. Pass `ResolvedTaskSchedulingPolicy` or a sub-policy value directly for the
   methods classified as policy-only in TCPI-0:
   - terminal evaluation
   - dispatch cadence checks
   - claim/retry/enqueue options
   - result finality
3. Keep methods classified as needing task context on `Task`, but make the
   policy use explicit at one approved boundary.
4. Remove or restrict convenience resolution paths such as
   `ResolvedTaskSchedulingPolicy.from(Task, TaskRuntimeProfile)` if they allow
   new callers to bypass `TaskPolicyPresetResolver`.
5. Avoid pass-through bridge/facade layers; update direct internal callers.
6. Preserve lifecycle/resource owners and existing task/runtime truth owners.

Acceptance:

1. Runtime owners that need policy receive explicit policy values or resolve at
   one approved boundary.
2. No new same-module pass-through abstraction is introduced.
3. Source guards block direct `TaskContract` behavior reads in runtime owners.
4. New callers cannot introduce an alternate preset-resolution mainline outside
   `TaskPolicyPresetResolver`.
5. Existing behavior tests remain green.

### TCPI-4: Preset Mapping As Explicit Table

Goal: make the `SESSION/BATCH -> policy` mapping a visible table-like
mechanism, not scattered conditionals.

Scope:

1. Replace ad-hoc `contract == BATCH ? ... : ...` clusters inside preset
   resolution with a small explicit preset definition structure.
2. Each preset definition names:
   - default workload class
   - dispatch cadence
   - idle-close policy
   - result-finality policy
   - default claim/retry/backpressure profile
3. Keep per-task overrides outside the preset table. Examples:
   - `executionSpec.foreground -> workerResourceMode`
   - task-level retry/batch/min-worker fields that are not pure preset defaults
4. Move behavior defaults toward a single owner: preset definition/resolver owns
   policy defaults; `ResolvedTaskSchedulingPolicy` compact construction should
   retain only structural normalization and null-safety that cannot change
   behavior semantics.
5. Decide the exit path for `TaskPolicyPresetSemantics`:
   - `defaultWorkloadClassFor(...)` should be absorbed by preset definitions
   - `defaultContract(...)` should either move to task shell defaulting or stay
     as a narrowly named public-preset default helper
6. Keep custom policy catalog deferred; this is an internal table, not a product
   policy catalog.

Acceptance:

1. Adding a future preset requires adding one preset definition, not editing
   multiple runtime owners.
2. Existing `SESSION` and `BATCH` resolved policy values are unchanged.
3. Unit tests compare full resolved policy snapshots for both presets.
4. Guards still allow direct `TaskContract` branching only inside preset
   definition/resolution.
5. Per-task overrides continue to win over preset defaults where they already
   do today.
6. `ResolvedTaskSchedulingPolicy` no longer owns behavior defaults that belong
   to preset resolution.

### TCPI-5: Public Boundary Documentation

Goal: document external stability and internal ownership clearly.

Scope:

1. Update SDK/public contract docs:
   - `TaskContract` is public preset input
   - behavior is resolved into task scheduling policy
2. Update server/internal API docs:
   - create task accepts contract for preset selection
   - response may show both preset input and resolved policy evidence if exposed
3. Update engine docs:
   - runtime owners consume resolved policy
   - `SESSION/BATCH` is not kernel behavior truth
4. Update `AGENTS.md` only if the repo-root handoff changes.

Acceptance:

1. Docs do not say `TaskContract` owns terminal, dispatch, retry, or result
   semantics.
2. Docs do not say `TaskContract` is merely a read model while it still affects
   preset resolution.
3. New-agent path is unambiguous: public contract first, internal policy after
   resolver.

### TCPI-6: Residue Removal

Goal: delete old internal mental-model residue after proof is in place.

Scope:

1. Remove obsolete comments, test names, and docs that imply contract-owned
   behavior.
2. Remove helper methods if their only purpose was transitional contract
   behavior interpretation and the explicit preset table has replaced them.
3. Tighten architecture guards around:
   - terminal
   - dispatch cadence
   - resource mode
   - claim/retry/backpressure
   - result finality
4. Archive this roadmap when complete and move current facts into owner docs.

Acceptance:

1. Active docs and tests describe policy-owned behavior.
2. Direct `TaskContract` reads remain only in public boundary, task shell
   defaulting, task naming/read evidence, and preset definition/resolution.
3. No active roadmap or baseline describes target state as already implemented
   without proof.
4. Residue scan passes.

## Proof Plan

Required proof lanes:

1. Unit:
   - full preset resolution for `SESSION`
   - full preset resolution for `BATCH`
   - explicit sub-policy consumers
2. Architecture guards:
   - no direct `TaskContract` behavior reads outside allowed files
   - no direct `foreground` resource branch outside preset resolution
   - no direct runtime-profile branch in claim/retry/backpressure production
     consumers
3. Lifecycle:
   - session-style idle close disabled
   - batch-style sealed/all-final close enabled
   - session-style result finality behavior
   - batch-style retry/finality behavior
4. Integration/Boot-shell when server/API evidence is exposed:
   - task create still accepts existing `contract`
   - read surface shows stable public contract
   - optional resolved policy diagnostics match engine behavior

## Risks

Risk 1: public callers think `TaskContract` disappeared.

Mitigation:

Keep public API stable. State that `TaskContract` is preset input, not removed.

Risk 2: internal policy values become another hidden preset.

Mitigation:

Expose resolved policy evidence in diagnostics and test full resolved snapshots.

Risk 3: preset table becomes a product policy catalog by accident.

Mitigation:

Keep TCPI first slice internal and static. Persisted catalog/project binding
needs a separate roadmap proving caller, storage owner, runtime consumer, and
operator cost.

Risk 4: tests preserve old behavior names.

Mitigation:

Rename internal tests to policy concepts while keeping public contract tests for
API compatibility.

Risk 5: duplicated truth between `Task.contract` and resolved policy snapshots.

Mitigation:

Resolved policy is derived runtime input, not persisted task truth in this
roadmap. If persistence is proposed later, it must define invalidation,
versioning, and clean-DB behavior.

## Recommended First Slice

Implement TCPI-0 + TCPI-1 first.

That gives immediate kernel transparency and a bounded direct-read list without
changing public APIs or runtime behavior.

Second slice:

TCPI-2 + TCPI-3 to converge internal tests and runtime method boundaries.

Third slice:

TCPI-4 to make preset mapping table-like.

Final slice:

TCPI-5 + TCPI-6 for docs, guards, and residue removal.
