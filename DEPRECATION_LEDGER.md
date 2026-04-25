# Deprecation Ledger

Last updated: 2026-04-25

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
| `GatewayConfig.createDispatcherContext(...)` and `GatewayConfig.createTransportServer(...)` | deprecated advanced embedding helpers in `xa-mass-sdk` | embedded-runtime mainline calls `GatewayEmbeddedRuntimeSupport` directly; custom adapter bootstrapping should use `transportServerFactory(...)` explicitly | 2 direct helper call sites in `MassSdkTest` only | remove after external embedders no longer need `GatewayConfig`-routed gateway bootstrap and repo verification stops asserting these helpers |
| `MassGateway.getConfig()` and `MassGateway.getMessageDispatcher()` | deprecated gateway runtime escape hatches in `xa-mass-sdk` | `MassGateway.start()`, `stop()`, and `isRunning()`; gateway config and dispatcher runtime stay outside the supported runtime control surface | 0 direct in-repo call sites | remove after external embedders no longer depend on direct gateway config or dispatcher access |
| `MassEngine.getRecordService()` and `MassEngine.getAssignWorker()` | deprecated engine runtime escape hatches in `xa-mass-sdk` | `MassEngine.start()`, `stop()`, `isRunning()`, and stable task/worker operations; record-service and assignment-loop internals stay engine-owned | 0 direct in-repo call sites | remove after external embedders no longer depend on direct engine-internal access |
| `MassSdkApplication.addWorker(Worker)` | deprecated SDK high-control worker API in `xa-mass-sdk` | `MassSdkApplication.registerWorker(WorkerRegistration)` | 2 direct `app.addWorker(...)` call sites | remove after remaining compatibility tests or callers migrate to registration models |
| `MassSdkApplication.addWorkerContext(WorkerContext)` | deprecated SDK high-control worker-context API in `xa-mass-sdk` | `MassSdkApplication.registerWorkerContext(WorkerContextRegistration)` | 0 direct `app.addWorkerContext(...)` call sites | remove after compatibility-only exposure is no longer needed in SDK surface and remaining callers, if any, move to registration models |
