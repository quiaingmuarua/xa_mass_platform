# Task Runtime Owner Module And Starter SDK Convergence Inventory

Status: TROM-0 required inventory scaffold for
[TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md).

This inventory is the guardable working artifact for the Old Port Closure
Matrix, ECSP temporary exception reclassification, and starter dependency graph.
It is not complete while any required row remains `pending`, `_TBD`, or
`later classify`.

Read with:

- [TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md)
- [ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md)

## Completion Rules

- Every old task-facing port method named by TROM-0 must appear in the Old Port
  Closure Matrix before TROM-1/TROM-2 implementation starts.
- Every matrix row must have exactly one target mechanism or be explicitly
  classified as engine-shell/internal residue.
- Every matrix row must name closure mode, proof, guard, and status.
- Every ECSP temporary exception marked for TROM-0, TROM, or TROM-4 must have a
  target owner/module, proof, guard, and removal/retention decision before the
  relevant TROM slice is complete.
- TROM-0 is not complete while this file contains `pending`, `_TBD`, or
  `later classify` in required rows.

## Old Port Closure Matrix

Allowed closure modes:

```text
delete
delegate to new runtime
split shell part from runtime part
keep engine-shell
engine-internal only
requires prerequisite old-mechanism convergence
```

| Old port | Method | Current callers | Truth touched | Target mechanism | Target command/outcome | Closure mode | Proof | Guard | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

## ECSP Temporary Exception Reclassification

Target classifications:

```text
xa-mass-task-runtime
engine-shell/internal
task-runtime starter contract
SDK public contract
delete
```

| ECSP symbol | Current caller | Current owner | Required slice | Target classification | Target owner/module | Removal or retention decision | Proof | Guard | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `TaskAppendReceipt` | `MassApplication`, `MassSdkApplication`, diagnostics operations | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `TaskDefinitionPatch` | `MassApplication`, `MassSdkApplication`, diagnostics operations | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `TaskResumeResult` | `MassApplication`, `MassSdkApplication`, diagnostics operations | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `TaskStateValidationResult` | `MassApplication`, `MassSdkApplication`, diagnostics operations | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `TaskStateResolutionResult` | `MassApplication`, `MassSdkApplication`, diagnostics operations | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `TaskResultWindow` | `MassApplication`, `MassSdkApplication` | `platform_infra/mass-runtime-api` | TROM-0/TROM-6 | pending | pending | pending | pending | pending | pending |
| `TaskResultRuntimeRow` | `MassApplication`, `MassSdkApplication`, result runtime callers | `platform_infra/mass-runtime-api` | TROM-0/TROM-6 | pending | pending | pending | pending | pending | pending |
| `TaskStageEvidenceResult` | `MassApplication`, `MassSdkApplication` | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `TaskStageProjection` | `MassApplication`, `MassSdkApplication` | `xa-mass-engine` | TROM-0 | pending | pending | pending | pending | pending | pending |
| `PollingIdleBackoffPolicy` | `MassSdk`, `MassApplicationBuilder`, tests | `xa-mass-engine` | TROM-0/TROM-4 | pending | pending | pending | pending | pending | pending |

## Module Dependency Graph

| Module | May depend on | Must not depend on | Startup responsibility | Status |
| --- | --- | --- | --- | --- |
| `xa-mass-task-runtime` | pending | Redis clients, Spring, SDK facade, engine implementation, transport implementation | none | pending |
| `platform_infra/mass-task-runtime-memory` | pending | engine implementation, SDK starter, server, transport implementation | none | pending |
| `platform_infra/mass-task-runtime-redis` | pending | engine implementation, SDK starter, server, transport implementation | none | pending |
| task-runtime starter SDK | pending | task lifecycle truth, Redis key layout, server HTTP contract | pending | pending |
| `xa-mass-engine-starter` | pending | task-runtime loop ownership unless TROM-0 explicitly assigns a host role | pending | pending |
| `sdk/xa-mass-embedded-sdk` | pending | direct `xa-mass-engine`, engine runtime internals, task-runtime internals | pending | pending |
| `xa-mass-engine` | pending | physical task-runtime storage/keyspace, task-runtime thread ownership | none after migrated responsibility moves | pending |

## Decisions

- The Old Port Closure Matrix is the source for first-path selection. BATCH is
  only the default candidate when its old port/method paths are closable.
- ECSP temporary exceptions are migration inputs, not preservation constraints.
- Module-internal field residue is tolerated only while it does not cross a
  module boundary, become a public contract, or re-open an old owner path.
- ECSP guards remain part of the TROM regression set whenever starter, embedded
  SDK, engine-starter, or approved starter surfaces are touched.
