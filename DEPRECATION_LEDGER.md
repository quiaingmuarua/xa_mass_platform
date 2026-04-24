# Deprecation Ledger

Last updated: 2026-04-24

This is the single repo-level index for deprecated, compatibility, and legacy seams that still exist in active paths.

Use it to answer three questions quickly:

- is this seam still allowed to grow?
- what is the identified mainline replacement or source of truth?
- what has to happen before the old seam can be removed?

Rules:

- list only seams that are either explicitly deprecated or intentionally constrained compatibility paths
- update this ledger when a seam is newly deprecated, gains a clearer replacement, or materially drops in call-site count
- gateway-local class-by-class migration detail lives in [doc/refactor/GATEWAY_CURRENT_INVENTORY.md](./doc/refactor/GATEWAY_CURRENT_INVENTORY.md)
- call-site counts below are grep-based repo counts captured on 2026-04-24; treat them as migration-tracking numbers, not exact semantic reachability proofs

| Symbol or seam | Current location | Mainline replacement / source of truth | Current in-repo call-site count | Removal condition |
| --- | --- | --- | --- | --- |
| gateway tuple routing `msgType + subMsgType` such as `TASK/step` and `CONTROL/event` | gateway router, WebSocket bridges, mock workers, docs/tests | transport-neutral channels plus event/capability-based runtime dispatch; tuple identity may remain only as adapter-local frame classification | 30 explicit `TASK/step` / `CONTROL/event` references | remove after WebSocket task and control bridges stop routing by tuple identity |
| `MassSdkApplication.addWorker(Worker)` | deprecated SDK high-control worker API in `xa-mass-sdk` | `MassSdkApplication.registerWorker(WorkerRegistration)` | 2 direct `app.addWorker(...)` call sites | remove after remaining compatibility tests or callers migrate to registration models |
| `MassSdkApplication.addWorkerContext(WorkerContext)` | deprecated SDK high-control worker-context API in `xa-mass-sdk` | `MassSdkApplication.registerWorkerContext(WorkerContextRegistration)` | 0 direct `app.addWorkerContext(...)` call sites | remove after compatibility-only exposure is no longer needed in SDK surface and remaining callers, if any, move to registration models |
