# Deprecation Ledger

Last updated: 2026-04-26

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
| `MassEngine.getRecordService()` and `MassEngine.getAssignWorker()` | deprecated engine runtime escape hatches in `xa-mass-sdk` | `MassEngine.start()`, `stop()`, `isRunning()`, and stable task/worker operations; record-service and assignment-loop internals stay engine-owned | 0 direct in-repo call sites | remove after external embedders no longer depend on direct engine-internal access |
| transport-family-only realtime registration (`transportHint=realtime` without `adapterId`) | compatibility input seam across SDK/API worker registration | explicit `adapterId + transportHint`, with `transportHint` retained only as family identity; current single-family fallback resolves through transport runtime registration metadata/descriptors rather than SDK-side websocket hardcoding | 0 direct in-repo registration call sites rely on the fallback once multiple realtime adapters are configured | remove the remaining single-realtime-family auto-resolution after external callers no longer depend on omitted `adapterId` |
| `MassSdkApplication.getEngine()`, `getTaskManager()`, and `getWorkerManager()` | deprecated SDK runtime escape hatches in `xa-mass-sdk` | stable `MassSdkApplication` task, worker, resource, and transport operations; engine and manager internals stay outside the supported SDK surface | 0 direct in-repo call sites | remove after external embedders no longer depend on direct engine or manager access |
| `MassSdkApplication.unwrap()` and SDK builder/option `unwrap()` methods | deprecated SDK-to-starter escape hatches in `xa-mass-sdk` | supported `MassSdkApplication` facade methods plus SDK builder option methods; lower-level starter builders/runtime stay advanced embedding-only | 0 direct in-repo call sites | remove after external embedders no longer need direct starter builder or runtime access |
| `MassApplication.getMessageTransporter()` | deprecated starter runtime escape hatch in `xa-mass-sdk` | `MassApplication.getTransportQueueDetail()` for diagnostics plus adapter/runtime-neutral transport surfaces for behavior | 0 direct in-repo production call sites after migrating `MassSdkApplication.getQueueDetail()` | remove after external embedders no longer depend on direct transporter access |
| `ResourceOperations.projectEventCatalog()` | deprecated SDK metadata compatibility read-model in `xa-mass-sdk` | `metadataCatalog()` | 0 direct `app.projectEventCatalog()` in-repo call sites | remove after external callers no longer depend on the old catalog naming |
| `MassSdk.Builder.projectEventCatalog(...)` | deprecated SDK bootstrap naming compatibility seam in `xa-mass-sdk` | `projectCatalogBootstrap(...)` | 0 direct in-repo call sites | remove after external embedders no longer depend on the old bootstrap naming |
| `MassSdkApplication.addWorker(Worker)` | deprecated SDK high-control worker API in `xa-mass-sdk` | `MassSdkApplication.registerWorker(WorkerRegistration)` | 0 direct `app.addWorker(...)` call sites | remove after external callers no longer depend on constructing core `Worker` models through the SDK surface |
| `MassSdkApplication.addWorkerContext(WorkerContext)` | deprecated SDK high-control worker-context API in `xa-mass-sdk` | `MassSdkApplication.registerWorkerContext(WorkerContextRegistration)` | 0 direct `app.addWorkerContext(...)` call sites | remove after compatibility-only exposure is no longer needed in SDK surface and remaining callers, if any, move to registration models |
