# Task Policy Preset Convergence Roadmap

Status: active high-priority planning. This is not current implementation
truth.

Priority: high.

This roadmap converges task-level implicit presets into explicit scheduling
policy truth.

The goal is not to remove `TaskContract` first. The goal is:

```text
1. reduce old direct preset call sites
2. introduce explicit resolved policy truth
3. switch engine behavior to resolved policy
4. downgrade old fields to legacy preset input or read-model evidence
5. remove residue and block old behavior paths from returning
```

Core rule:

```text
TaskContract must not be removed before every behavior it currently implies has
an explicit policy owner.
```

First-slice rule:

```text
Reduce old direct preset call sites before adding or switching new behavior.
```

This roadmap is intentionally ordered as:

```text
converge scattered old reads -> modify one behavior owner at a time -> delete
residue and guards only after proof.
```

## Problem

Current task behavior is partly explicit and partly implied by several preset
fields:

- `TaskContract`
- `TaskIntakeStatus`
- `TaskWorkloadClass`
- `TaskExecutionProfile`
- `TaskExecutionSpec.foreground`
- runtime profile defaults
- retry / claim / enqueue / backpressure defaults
- `TaskSharedConfig` conventions

This worked as a cleanup boundary while removing older lifecycle/message
residue, but it is becoming a hidden coupling risk. Humans and agents can
forget that a simple field such as `contract=BATCH` currently implies terminal,
dispatch, retry, and pump behavior.

The target is explicit policy consumption:

```text
TaskIntakeStatus
  owns whether new work can be accepted.

ResolvedTaskSchedulingPolicy
  owns task-side scheduling, lifecycle, dispatch cadence, retry, claim,
  backpressure, and resource policy inputs.

TaskContract / workloadClass / foreground
  become legacy preset inputs or read-model evidence until retired.
```

Current code already has `ResolvedTaskSchedulingPolicy` and
`ResolvedWorkerSchedulingPolicy` as engine-facing scheduling value contracts.
The gap is that several task-runtime owners still consume raw preset fields
directly. This roadmap extends and converges the existing resolved view; it does
not introduce a public policy catalog or a same-module pass-through facade.

## Current Direct Preset Call Sites

Current `TaskContract` behavior call families:

| Family | Current examples | Hidden behavior |
| --- | --- | --- |
| creation/default | `TaskManager.resolveShellContract`, `Task.setContract`, task name derivation | null contract becomes `BATCH`; contract affects default naming |
| workload default | `TaskManager.resolveWorkloadClass` | `SESSION -> INTERACTIVE`, otherwise `BULK` |
| terminal | `ContractAwareTaskTerminalPolicy`, `AllWorkFinalTaskTerminalPolicy` | `BATCH` can auto-terminal after sealed/all-final; `SESSION` keeps running |
| dispatch cadence | `TaskDispatchRequestService`, `RuntimeReadyDispatchPump` | `BATCH` skips delayed wakeup and is pump-driven; `SESSION` uses signal/delayed wakeup |
| result/retry | `TaskResultService` | batch-specific retry/finality branches |
| public/read | SDK/server DTOs and snapshots | contract appears as external request/read model |

Other implicit preset call families:

| Field / owner | Current behavior | Risk |
| --- | --- | --- |
| `TaskExecutionSpec.foreground=true` | default exclusive worker lock through `DefaultWorkerDispatchResourcePolicy` | hidden worker concurrency policy |
| `TaskWorkloadClass` | resolves runtime lane, priority, batch policy, lease profile, backpressure class | hidden task scheduling policy preset |
| `TaskRuntimeClaimOptionsResolver` | interactive per-worker claim cap and short lease defaults | policy hidden behind resolver/system properties |
| `TaskRuntimeEnqueueOptionsResolver` | interactive ready backlog cap and bulk unlimited default | backpressure preset hidden behind workload class |
| `TaskRuntimeRetryPolicyResolver` | interactive/bulk retry cadence defaults | retry preset hidden behind workload class |
| `TaskSharedConfig` | worker group, route, target worker, event code conventions | map-shaped dispatch intent instead of first-class policy fields |

## Retirement Targets

These old mechanisms are not allowed to remain mainline behavior truth after
their replacement phase lands:

| Old mechanism | Replacement truth | Retirement condition |
| --- | --- | --- |
| `TaskContract -> workloadClass` implicit derivation | task policy preset resolution result | only the preset resolver may derive workload class from contract |
| `TaskContract` terminal branching | `IdleClosePolicy` or equivalent terminal policy | terminal owners do not branch on `TaskContract` |
| `TaskContract` dispatch branching | `DispatchCadence` | dispatch request and runtime-ready pump owners do not branch on `TaskContract` |
| `TaskContract` result/retry branching | explicit retry/finality policy values | result owner does not use contract as retry/finality truth |
| `TaskExecutionSpec.foreground -> exclusive lock` | `WorkerResourceMode` | resource policy reads resolved resource mode, not raw foreground |
| `TaskWorkloadClass` runtime profile behavior | resolved claim/retry/backpressure/lease/lane values | workload class is preset input/read evidence, not resolver truth |

The old field may still be visible in public DTOs, traces, and read models
during transition, but visibility is not behavior ownership.

## Non-Goals

- No immediate removal of `TaskContract`.
- No breaking SDK/server public task create contract in the first slices.
- No task lifecycle behavior change before existing behavior is represented as
  explicit policy.
- No broad public policy product or policy catalog in this roadmap.
- No worker matching redesign.
- No runtime queue/lease/result ownership change.
- No new bridge/facade layer that only forwards to existing owners.
- No fallback layer that preserves old and new behavior as two independent live
  tracks.

## Implementation Rules

1. Convergence phases must preserve behavior. If a phase changes behavior, it
   must be renamed to a modify phase with its own proof.
2. The temporary preset convergence seam may interpret old fields only until
   the equivalent resolved policy value exists.
3. After a behavior owner switches to explicit policy, do not keep the old
   direct preset branch beside it.
4. Public SDK/server `contract` handling remains request/read-model mapping
   until a separate public API decision is accepted.
5. `TaskIntakeStatus` must not be weakened: intake open/sealed remains the
   only truth for whether new work can be appended.
6. `eventCode` and worker group selector semantics are out of scope; do not use
   this roadmap to reopen worker matching truth.
7. Each modify phase must either delete the replaced old branch in the same
   change or add a precise source guard that makes the old branch impossible to
   reintroduce silently.
8. Do not add `@Deprecated` markers as a substitute for removing internal old
   paths. A marker is acceptable only when paired with a named follow-up phase
   and a guard or residue scan.

## Target Shape

First target is the existing engine-resolved policy view, extended where needed,
not a new public API:

```java
record ResolvedTaskSchedulingPolicy(
    String taskId,
    String taskPolicyPreset,
    TaskWorkloadClass workloadClass,
    DispatchCadence dispatchCadence,
    WorkerResourceMode workerResourceMode,
    IdleClosePolicy idleClosePolicy,
    ClaimPolicy claimPolicy,
    RetryPolicy retryPolicy,
    BackpressurePolicy backpressurePolicy,
    int batchSize,
    int defaultMaxRetryCount,
    int minRequiredWorkerCount
) {}
```

Names may change during implementation, but the resolved view must make the
currently hidden behavior explicit.

Example policy meanings:

```text
DispatchCadence.RUNTIME_READY_POLLING
  batch-style runtime-ready dispatch pump.

DispatchCadence.SIGNAL_DRIVEN_DELAYED
  session/interactive immediate or delayed wakeup.

WorkerResourceMode.EXCLUSIVE
  foreground-style worker exclusive lease.

WorkerResourceMode.CAPACITY
  background-style capacity reservation only.

IdleClosePolicy
  whether all-final runtime work can close the task, whether intake must be
  sealed first, and optional idle delay.
```

`TaskContract` can continue to select default preset behavior temporarily:

```text
BATCH
  -> default finite batch preset

SESSION
  -> default open interactive preset
```

But after convergence, engine behavior must consume resolved policy fields, not
direct `TaskContract` checks.

## Mainline After Convergence

Target flow:

```text
task shell fields / shared config
  -> TaskPolicyPresetResolver
  -> ResolvedTaskSchedulingPolicy
  -> terminal / dispatch cadence / claim / retry / backpressure / resource mode
```

Disallowed mainline:

```text
TaskContract / foreground / workloadClass
  -> terminal / dispatch / runtime profile / resource lock directly
```

The resolver is allowed to read legacy fields while public callers still send
them. Runtime owners should not.

## Phase Plan

Recommended first slice:

```text
TPC-0 + TPC-1 only.
```

This gives the next code change a narrow, testable goal: reduce the scattered
old reads without changing terminal, dispatch, retry, claim, backpressure, or
worker resource behavior. Do not start with new policy fields until the old
read sites are classified and narrowed.

### TPC-0: Inventory And Call-Site Classification

Goal: make current hidden preset usage explicit.

Scope:

1. Inventory all production `TaskContract`, `TaskWorkloadClass`,
   `TaskExecutionProfile`, `TaskExecutionSpec.foreground`, and task runtime
   profile resolver call sites.
2. Classify each call as:
   - preset resolution
   - explicit policy consumption
   - public/read model
   - suspicious hidden behavior
   - test-only compatibility proof
3. Record which tests prove each current behavior.
4. No behavior changes.

Acceptance:

1. Inventory exists beside this roadmap or inside it.
2. Every `TaskContract` production read has a category.
3. Suspicious hidden behavior is named before any code migration starts.
4. The inventory explicitly covers terminal, dispatch cadence, runtime-ready
   pump, result/retry, engine runtime kernel listener behavior, and worker
   resource policy.
5. No code behavior changes.

Verification:

```bash
rg -n "TaskContract|getContract\\(|TaskWorkloadClass|TaskExecutionProfile|isForeground\\(|foreground" \
  xa-mass-engine xa-mass-base sdk xa-mass-server integrations -S
```

### TPC-1: Reduce Old Direct Call Sites

Goal: shrink the number of places that directly interpret old preset fields.

Scope:

1. Add an engine-internal convergence seam for legacy preset interpretation.
   Candidate name:
   - `TaskPolicyPresetSemantics`
   This seam is a temporary owner of old-field interpretation, not a new public
   abstraction boundary.
2. Move direct `TaskContract` behavior interpretation behind named methods:
   - `defaultWorkloadClassFor(...)`
   - `usesRuntimeReadyDispatchPump(...)`
   - `usesSignalDelayedDispatch(...)`
   - `usesAllFinalAutoTerminal(...)`
   - `usesBatchRetryFinality(...)` if needed
   - `usesSessionRuntimeListeners(...)` if needed
3. Keep server/SDK/read-model contract exposure unchanged.
4. Mark the seam as temporary convergence-only.
5. Add a source comment or small guard test naming this seam as not eligible
   for new behavior after `TPC-2`.

Acceptance:

1. Engine behavior is unchanged.
2. Production direct `TaskContract` behavior reads are reduced to the
   convergence seam plus public/read/defaulting locations.
3. Tests proving batch/session terminal and scheduling behavior still pass.
4. No new public API.
5. No second live behavior path: migrated owners call the seam instead of
   retaining parallel direct `TaskContract` branches.
6. The seam has an explicit retirement note listing which later phase removes
   each method.

Verification candidates:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=TaskContractTerminalBehaviorTest,TaskContractSchedulingBehaviorTest,TaskKernelLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### TPC-2: Define Explicit Policy Values

Goal: model the hidden behavior as explicit resolved policy fields.

Scope:

1. Extend the existing `ResolvedTaskSchedulingPolicy` or add nested
   engine-local values for:
   - dispatch cadence
   - worker resource mode
   - idle close policy
   - claim policy
   - retry policy
   - backpressure policy
2. First implementation may derive these values from the old convergence seam.
3. Do not change behavior.
4. Do not expose these values as public SDK/server request fields yet.
5. Do not introduce `SchedulingPolicyCatalog`,
   `ProjectSchedulingBinding`, or writable policy storage in this roadmap.
6. Add a migration map from each temporary seam method to a resolved policy
   field.

Acceptance:

1. Resolved policy can represent current batch and session behavior.
2. Existing behavior is still derived identically.
3. Policy value tests cover at least:
   - batch idle close default
   - session idle close default
   - batch runtime-ready dispatch default
   - session signal/delayed dispatch default
   - foreground/exclusive resource default
4. No engine hot path behavior switch yet unless test-covered in the same
   slice.
5. Every new resolved policy field has a named consumer phase; unused fields
   are not added speculatively.

### TPC-3: Switch Terminal Policy To Resolved Policy

Goal: terminal behavior no longer directly depends on `TaskContract`.

Scope:

1. `TaskTerminalPolicy` consumes an explicit idle close policy or an equivalent
   resolved terminal policy.
2. Preserve current behavior:
   - batch requires sealed/all-final for automatic terminal
   - session keeps running unless explicit terminal/max-runtime/policy command
3. `TaskIntakeStatus` remains the intake truth.

Acceptance:

1. `ContractAwareTaskTerminalPolicy` either no longer exists or no longer
   switches directly on `TaskContract`.
2. Terminal behavior tests pass unchanged or are renamed around policy behavior.
3. Guard prevents new terminal logic from directly branching on
   `TaskContract`.
4. The old terminal preset method is removed from the convergence seam or made
   unused with a same-change residue scan.

### TPC-4: Switch Dispatch Cadence To Resolved Policy

Goal: dispatch wakeup and runtime-ready pump selection no longer directly read
`TaskContract`.

Scope:

1. `TaskDispatchRequestService` reads `dispatchCadence`.
2. `RuntimeReadyDispatchPump` reads `dispatchCadence`.
3. Preserve current behavior:
   - batch-style tasks are runtime-ready pump driven
   - session-style tasks use signal/delayed wakeup
4. Keep polling/backoff mechanism separate from the policy value.

Acceptance:

1. `TaskDispatchRequestService` does not branch on `TaskContract`.
2. `RuntimeReadyDispatchPump` does not branch on `TaskContract`.
3. Runtime-ready dispatch pump tests and contract scheduling tests pass.
4. Worker availability wakeup behavior remains unchanged.
5. The old dispatch preset methods are removed from the convergence seam or
   made unused with a same-change residue scan.

### TPC-5: Switch Worker Resource Mode To Resolved Policy

Goal: worker exclusive lock policy is explicit, not hidden behind
`executionSpec.foreground`.

Scope:

1. Add or resolve `WorkerResourceMode`.
2. `DefaultWorkerDispatchResourcePolicy` reads the resolved resource mode.
3. Preserve current default:
   - foreground/default tasks use exclusive worker lock
   - background tasks use capacity reservation only
4. Do not change worker-runtime admission semantics.

Acceptance:

1. Resource policy does not directly interpret `executionSpec.foreground`
   except through preset/policy resolution.
2. Existing resource release and single-worker reuse tests pass.
3. Tests cover foreground/exclusive and background/capacity behavior.
4. Public/read-model `foreground` exposure is clearly documented as legacy
   input/read evidence, not the resource-mode owner.

### TPC-6: Switch Retry / Claim / Backpressure To Resolved Policy

Goal: workload class no longer acts as an implicit policy owner across several
runtime resolvers.

Scope:

1. `TaskRuntimeClaimOptionsResolver` consumes claim policy.
2. `TaskRuntimeRetryPolicyResolver` consumes retry policy.
3. `TaskRuntimeEnqueueOptionsResolver` consumes backpressure policy.
4. Keep property defaults and current behavior unchanged.
5. Do not remove `TaskWorkloadClass` yet; it can remain preset input and
   read-model evidence.

Acceptance:

1. Claim/retry/enqueue behavior is represented by resolved policy fields.
2. Resolver tests pass and are renamed or supplemented around policy values.
3. `TaskWorkloadClass` direct behavior reads are limited to preset resolution
   and read model.
4. Runtime profile terminology is no longer the behavior owner in new code; it
   is either a resolved policy sub-value or a legacy preset derivation detail.

### TPC-7: Downgrade Public TaskContract Semantics

Goal: public callers can keep sending `contract`, but it is documented and
implemented as a preset input.

Scope:

1. Update SDK/server docs to describe `contract` as legacy preset input, not
   direct behavior truth.
2. Add public/read model language for future `taskPolicyPreset` or explicit
   task policy fields.
3. Do not break existing SDK/server requests.
4. Do not remove `TaskContract` yet.

Acceptance:

1. Public docs do not claim `TaskContract` directly owns terminal/dispatch
   behavior.
2. SDK quickstart remains valid.
3. Existing public contract tests pass.
4. Any new SDK/server wording points users toward explicit task policy naming
   when available.

### TPC-8: Guard Direct Behavior Reads

Goal: prevent implicit preset behavior from returning.

Scope:

1. Add source guards that allow direct `TaskContract` behavior reads only in:
   - preset resolution
   - DTO mapping / public contract
   - read model
   - tests explicitly proving compatibility behavior
2. Add similar guard or audit note for:
   - `executionSpec.foreground`
   - `TaskWorkloadClass`
   - runtime profile direct behavior consumption
3. Keep guards precise enough not to block harmless display/read uses.

Acceptance:

1. New terminal/dispatch/retry/resource behavior cannot branch directly on
   `TaskContract`.
2. Guard failure message points to the explicit policy owner.
3. No broad regex guard that blocks legitimate request/read mapping.
4. Guard allowlists are owner-specific and must shrink as old fields are
   retired.

### TPC-9: Delete Residue And Decide Public Removal

Goal: decide whether `TaskContract` should remain as a public preset input or
be replaced.

Prerequisites:

1. Engine behavior consumes explicit policy.
2. SDK/server/frontend can display and create tasks through explicit
   `taskPolicyPreset` or task policy values.
3. Compatibility cost of removing public `contract` is accepted.

Possible outcomes:

- keep `TaskContract` as a stable public preset input
- rename it to `taskPolicyPreset`
- add `taskPolicyPreset` first and deprecate `contract`
- remove `contract` only after all in-repo callers migrate

Acceptance:

1. Decision is recorded separately before public API removal.
2. No old and new public paths remain as two live mainlines indefinitely.
3. Internal engine behavior has no direct dependency on `TaskContract`,
   `foreground`, or `TaskWorkloadClass` outside accepted preset resolution and
   read-model paths.
4. Stale tests named around `TaskContract` are renamed, deleted, or explicitly
   kept as public compatibility proof.

## Testing Direction

Prefer behavior-preserving proof first:

- terminal policy behavior
- intake open/sealed append behavior
- batch runtime-ready dispatch pump behavior
- session delayed/signal dispatch behavior
- foreground/background worker resource behavior
- retry/claim/backpressure resolver behavior

Suggested starting tests:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=TaskContractTerminalBehaviorTest,TaskContractSchedulingBehaviorTest,TaskKernelLifecycleTest,RuntimeReadyDispatchPumpTest,DefaultWorkerDispatchResourcePolicyTest,TaskRuntimeClaimOptionsResolverTest,TaskRuntimeEnqueueOptionsResolverTest,TaskRuntimeRetryPolicyResolverTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Use server or SDK E2E only when the change affects public request mapping,
worker dispatch wiring, or frontend-visible read model behavior.

## Open Questions

1. Should `TaskExecutionProfile.STANDARD` remain after explicit policy preset
   exists, or is it redundant with `taskPolicyPreset`?
2. Should `foreground` become a public field named around worker resource
   semantics, such as `workerResourceMode`?
3. Should `TaskSharedConfig` continue to own dispatch intent keys, or should
   worker selector fields move into a typed public task-create object first?
4. Should `TaskContract` be kept indefinitely as a convenient public preset
   name even after behavior is explicit?
