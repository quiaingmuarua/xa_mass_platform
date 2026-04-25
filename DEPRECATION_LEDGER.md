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
- websocket-adapter-local class-by-class migration detail lives in [doc/refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md](./doc/refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md)
- call-site counts below are grep-based repo counts captured on 2026-04-25; treat them as migration-tracking numbers, not exact semantic reachability proofs

| Symbol or seam | Current location | Mainline replacement / source of truth | Current in-repo call-site count | Removal condition |
| --- | --- | --- | --- | --- |
| `WebSocketConfig.createDispatcherContext(...)` and `WebSocketConfig.createTransportServer(...)` | deprecated advanced embedding helpers in `xa-mass-sdk` | embedded-runtime mainline calls `WebSocketEmbeddedRuntimeSupport` directly; custom adapter bootstrapping should use `transportServerFactory(...)` explicitly | 0 direct in-repo helper call sites | remove after external embedders no longer need `WebSocketConfig`-routed adapter bootstrap |
| `MassWebSocketAdapter.getWebSocketConfig()` and `MassWebSocketAdapter.getWebSocketMessageDispatcher()` | deprecated adapter runtime escape hatches in `xa-mass-sdk` | `MassWebSocketAdapter.start()`, `stop()`, and `isRunning()`; WebSocket config and dispatcher runtime stay outside the supported runtime control surface | 0 direct in-repo call sites | remove after external embedders no longer depend on direct WebSocket config or dispatcher access |
| `MassEngine.getRecordService()` and `MassEngine.getAssignWorker()` | deprecated engine runtime escape hatches in `xa-mass-sdk` | `MassEngine.start()`, `stop()`, `isRunning()`, and stable task/worker operations; record-service and assignment-loop internals stay engine-owned | 0 direct in-repo call sites | remove after external embedders no longer depend on direct engine-internal access |
| `MassApplicationBuilder.server(int, String)` and `MassSdk.Builder.server(int, String)` | deprecated transport endpoint naming compatibility seams in `xa-mass-sdk` | `transportServer(int, String)` | 0 direct in-repo call sites | remove after external embedders no longer depend on the old server naming |
| `MassSdkApplication.getEngine()`, `getTaskManager()`, and `getWorkerManager()` | deprecated SDK runtime escape hatches in `xa-mass-sdk` | stable `MassSdkApplication` task, worker, resource, and transport operations; engine and manager internals stay outside the supported SDK surface | 0 direct in-repo call sites | remove after external embedders no longer depend on direct engine or manager access |
| `MassSdkApplication.unwrap()` and SDK builder/option `unwrap()` methods | deprecated SDK-to-starter escape hatches in `xa-mass-sdk` | supported `MassSdkApplication` facade methods plus SDK builder option methods; lower-level starter builders/runtime stay advanced embedding-only | 0 direct in-repo call sites | remove after external embedders no longer need direct starter builder or runtime access |
| `ResourceOperations.projectEventCatalog()` | deprecated SDK metadata compatibility read-model in `xa-mass-sdk` | `metadataCatalog()` | 0 direct `app.projectEventCatalog()` in-repo call sites | remove after external callers no longer depend on the old catalog naming |
| `MassSdk.Builder.projectEventCatalog(...)` | deprecated SDK bootstrap naming compatibility seam in `xa-mass-sdk` | `projectCatalogBootstrap(...)` | 0 direct in-repo call sites | remove after external embedders no longer depend on the old bootstrap naming |
| `MassSdkApplication.addWorker(Worker)` | deprecated SDK high-control worker API in `xa-mass-sdk` | `MassSdkApplication.registerWorker(WorkerRegistration)` | 0 direct `app.addWorker(...)` call sites | remove after external callers no longer depend on constructing core `Worker` models through the SDK surface |
| `MassSdkApplication.addWorkerContext(WorkerContext)` | deprecated SDK high-control worker-context API in `xa-mass-sdk` | `MassSdkApplication.registerWorkerContext(WorkerContextRegistration)` | 0 direct `app.addWorkerContext(...)` call sites | remove after compatibility-only exposure is no longer needed in SDK surface and remaining callers, if any, move to registration models |
