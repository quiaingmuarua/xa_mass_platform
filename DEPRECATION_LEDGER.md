# Deprecation Ledger

Last updated: 2026-06-02

Status: current repo-level deprecation and compatibility index.

This is the single repo-level index for deprecated, compatibility, and legacy seams that still exist in active paths.

Use it to answer three questions quickly:

- is this seam still allowed to grow?
- what is the identified mainline replacement or source of truth?
- what has to happen before the old seam can be removed?

Rules:

- list only seams that are either explicitly deprecated or intentionally constrained compatibility paths
- update this ledger when a seam is newly deprecated, gains a clearer replacement, materially drops in usage, or is removed
- use this ledger for staged breaking refactors immediately when `@Deprecated` is introduced; deprecated seams should move toward caller migration and removal, not remain as long-lived parallel mainlines
- websocket-adapter-local boundary truth now lives in [transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](./transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) and [transport/TRANSPORT_BOUNDARY_BASELINE.md](./transport/TRANSPORT_BOUNDARY_BASELINE.md)
- do not use this ledger as a license to extend compatibility seams; each row is a removal/migration constraint

| Symbol or seam | Current location | Mainline replacement / source of truth | Constraint | Removal condition |
| --- | --- | --- | --- | --- |
| `WorkerAdminOperations.updateWorkerSupportedProjects(...)` | `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/WorkerAdminOperations.java` | worker capability update through owner-backed capability report / event-binding registration flow | Do not add sibling coarse mutation methods such as `updateSupportedEventCodes`. | Capability self-report / registration update flow covers current callers. |
| `MassRuntimeControl` task shortcut methods (`approveTask`, `rejectTask`, `blockTask`, `pauseTask`, `resumeTask`, `cancelTask`, `terminateTask`, `sealTask`) | `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassRuntimeControl.java` | `executeTaskCommand(taskId, MassTaskCommandRequest)` | Keep for transitional bootstrap/dev code only. Do not add new lifecycle shortcut methods. | Bootstrap/dev callers migrate to `executeTaskCommand(...)`. |
| `DefaultProjectEventCatalogFactory.createDefaultCatalog()` | `sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/catalog/DefaultProjectEventCatalogFactory.java` | `createDefaultProjectRegistry()` | Naming compatibility only. Do not use in new code. | Existing callers migrate to the registry-named factory. |

Current tracked compatibility residue: none beyond the table above.
