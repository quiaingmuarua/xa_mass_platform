# Kernel Convergence Matrix

Status: current engine kernel-convergence map.

This file is the engine-local entry for lifecycle and result-convergence tests.
It maps kernel invariants to the primary proof surface, current test lane, and
known extraction gap. It is not a roadmap for new lifecycle behavior.

## Core Rule

Kernel convergence must stay runtime-first:

```text
contract / intake truth
  + TaskWorkRuntime ready / delayed / lease / final truth
  + TaskResultRuntime stable-final truth
  -> task aggregate convergence
```

Compatibility projection may be validated as residue or audit, but it must not
be the mainline pass/fail truth for lifecycle, retry, expiry, finality, or
terminal closure.

## Test Lanes

| Lane | Owner | Intent |
| --- | --- | --- |
| `EngineKernelConvergenceSuite` | `xa-mass-engine` | Primary runtime-first kernel lifecycle and convergence gate |
| `EngineSchedulingCoreSuite` | `xa-mass-engine` | Cross-lane scenarios where scheduling competition also proves convergence |
| `EngineProjectionResidueSuite` | `xa-mass-engine` secondary | Compatibility/debug residue while older mixed tests are being retired or split |
| `EngineProjectionAuditSuite` | `xa-mass-engine` secondary | Explicit projection-audit boundary only |
| `xa-mass-testing` soak/chaos | `xa-mass-testing` | Runtime pressure, disconnect, replay, and distributed edge proof |
| `xa-mass-server` lifecycle/result E2E | `xa-mass-server` | Real host/API wiring proof |

## Physical Test Layout

Logical ownership is defined by this matrix and suite membership first.

- Root-package kernel tests may stay in `com.xa.mass.engine` when they need
  package-private lifecycle or runtime-port access.
- Owner-local helpers and guards should live in the nearest owner package when
  they can move without widening production visibility.
- Do not introduce public accessors, test-only bridges, or compatibility
  wrappers just to move a kernel test out of the root package.

## Invariant Matrix

| Invariant | Owner boundary | Primary tests | Proof surface | Current status |
| --- | --- | --- | --- | --- |
| Task shell creation preserves contract, workload, intake-window, and runtime-ready truth | task lifecycle + runtime admission | `TaskKernelLifecycleTest` | task aggregate, `TaskWorkRuntime` ready count | Covered |
| Intake commands obey task-state legality and terminal commands drain runtime-ready work | task lifecycle | `TaskKernelLifecycleTest` | task status, hold/terminal reason, runtime ready truth | Covered |
| Open batch intake does not auto-terminal until sealed; session seal closes append without auto-terminal | contract + intake + terminal policy | `TaskContractTerminalBehaviorTest` | task aggregate status, intake status, terminal reason | Covered |
| Runtime recovery advertises only dispatchable task shells backed by runtime-ready truth | recovery port + task shell truth | `TaskRuntimeRecoveryPortTest` | recovered task ids vs runtime ready-set ids | Covered |
| Retry expiry re-enters work or finalizes according to retry policy without double finalization | work runtime + retry/finality policy | `TaskRedispatchCompetitionTest` | ready/inflight/final counters, terminal reason, active lease truth | Covered through scheduling lane |
| Result finality releases worker resources and allows waiting work to continue | result convergence + release owner | `TaskRedispatchCompetitionTest`, `TaskResourceReleaseListenerTest` | terminal reason, worker unlock/load release, later dispatch | Covered through scheduling lane |
| Stable-final runtime result commit, repair barriers, and duplicate result idempotency converge without projection fallback | result runtime + engine repair pump | `TaskResultRuntimeConvergenceTest` | `TaskResultRuntime` visible rows, repair candidates, task aggregate | Covered |
| Callback / expiry races produce one logical final outcome and coalesced progress | result ingest + task concurrency owner | `TaskResultConcurrencyConvergenceTest` | task aggregate, event counts, runtime/result finality | Covered |
| Kernel mainline suite must stay free of compatibility projection proof | suite guard | `EngineKernelConvergenceArchitectureGuardTest` | selected-suite source scan | Covered |

## Known Gaps

These are testing-structure gaps, not known product-behavior gaps.

- `TaskManagerLifecycleTest` is still a broad projection-aware residue holder:
  lifecycle shell checks, payload-ref compatibility, retry/expiry residue, and
  terminal overlay checks remain physically mixed there. Split it only through
  small owner-specific passes; do not reopen the runtime-first kernel suite by
  moving projection proof back into mainline.
- Scheduling scenarios already prove some convergence invariants. Do not clone
  them into lifecycle-only tests unless the owner boundary changes or the
  scheduling scenario becomes too noisy to isolate the kernel fact.

## Add-Test Rule

When adding a kernel-convergence test:

1. Pick an invariant in this matrix or add a new one.
2. Prefer runtime truth, lease truth, result-runtime truth, and task aggregate
   truth as the pass/fail surface.
3. Put projection residue in the explicit residue/audit suites only when the
   residue itself is the subject.
4. Use scheduling tests when the real risk is worker choice or competition;
   use soak/chaos when the real risk is timing, disconnect, or replay.
