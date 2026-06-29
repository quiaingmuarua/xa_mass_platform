# Engine Caller Surface Pre-TROM Inventory

Status: current ECSP-0A/0B inventory for
[ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md).

This is the guardable source for temporary public-contract exceptions. It is
not an allowlist for engine implementation, service, config-owner,
runtime-owner, or lifecycle-object imports.

## Symbols

| Symbol | Current Caller | Import Lane | Owner | Why Temporary | Target Owner/Module | Removal Target | Slice |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `com.xa.mass.starter.MassEngine` | `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication`, tests | starter lifecycle and operation handle | `xa-mass-engine-starter` | Package name is retained for this slice to avoid broad rename churn; module ownership has moved. | `xa-mass-engine-starter` | Rename package only if TROM or public API cleanup proves value; no `getConfig()` backdoor may return. | ECSP-1/2 |
| `com.xa.mass.starter.config.EngineConfig` | `MassApplicationBuilder`, `MassApplication`, engine-starter internal tests | starter assembly config | `xa-mass-engine-starter` | Configuration is still needed to compose engine internals; it is not an SDK operation surface. | `xa-mass-engine-starter` internal config | Replace with narrower starter options during later TROM cleanup if needed. | ECSP-1/2 |
| `MassApplication.getEngine()` | old SDK tests and SDK internals | forbidden backdoor | none | Not temporary. Deleted from production surface. | none | Must remain absent. | ECSP-2/4 |
| `MassEngine.getConfig()` | old SDK tests and SDK internals | forbidden backdoor | none | Not temporary. Deleted from production surface. | none | Must remain absent. | ECSP-2/4 |
| `TaskCommandService`, `TaskQueryService`, `TaskEventService`, `WorkerControlRuntime`, `TaskStageEvidenceService`, `TaskResultRuntime` imports from `MassSdkApplication` | `MassSdkApplication` before ECSP | forbidden service/runtime owner import | engine, worker-runtime, task-result runtime | Not temporary. Replaced by behavior methods on `MassApplication` and `MassEngine`. | `xa-mass-engine-starter` containment only | Must remain absent from embedded SDK main source. | ECSP-2/4 |
| `com.xa.mass.engine.model.TaskAppendReceipt`, `TaskDefinitionPatch`, `TaskResumeResult`, `TaskStateValidationResult`, `TaskStateResolutionResult` | `MassApplication`, `MassSdkApplication`, diagnostics operations | temporary value-contract exception | `xa-mass-engine` today | These are result/command value shapes still used by SDK behavior methods; moving them is TROM/public-contract work, not ECSP core. | future task-runtime or public runtime contract module | Reclassify during TROM-0 module split inventory. | ECSP-0B/TROM-0 |
| `com.xa.mass.engine.stage.TaskStageEvidenceResult`, `TaskStageProjection` | `MassApplication`, `MassSdkApplication` | temporary value-contract exception | `xa-mass-engine` today | Stage evidence is SDK-facing but still engine-owned in current code. | future task/stage public contract or task-runtime contract | Reclassify during TROM-0 module split inventory. | ECSP-0B/TROM-0 |
| `com.xa.mass.engine.PollingIdleBackoffPolicy` | `MassSdk`, `MassApplicationBuilder`, tests | temporary configuration value exception | `xa-mass-engine` today | Engine runtime-ready loop tuning still uses this engine-owned functional interface. | future starter/public configuration contract | Reclassify during TROM starter-loop split. | ECSP-0B/TROM-4 |

## Dependencies

| Module | Dependency | Scope | Reason | Target | Slice |
| --- | --- | --- | --- | --- | --- |
| `sdk/xa-mass-embedded-sdk` | `xa-mass-engine` | direct compile | Old forbidden dependency; removed. | No direct dependency; access goes through `xa-mass-engine-starter`. | ECSP-2 |
| `sdk/xa-mass-embedded-sdk` | `xa-mass-engine-starter` | direct compile | Approved containment dependency for engine lifecycle and operation handles. | Keep until starter surfaces are further split by TROM. | ECSP-1/2 |
| `xa-mass-engine-starter` | `xa-mass-engine` | direct compile | Engine-starter is the only production containment module allowed to compose engine internals. | Keep. | ECSP-1 |
| `xa-mass-engine-starter` | `xa-mass-worker-runtime` | direct compile | Engine-starter composes worker-runtime evidence/control ports for engine-facing handles only. | Keep as containment; do not make engine-starter the worker evidence owner. | ECSP-1/3 |
| `sdk/xa-mass-embedded-sdk` | `xa-mass-worker-runtime`, transport modules, runtime/storage memory | direct compile | Embedded SDK remains application/transport assembly and SDK facade host. | Keep only where SDK/transport assembly needs the public runtime/transport contracts. | ECSP-1/3 |

## Approved Starter Surfaces

| Surface | External Caller | Behavior Preserved | Input Fields | Return Fields | Internal Owner | Why Crosses Boundary | Replaces Old Getter | Proof |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Lifecycle and engine availability | `MassApplication`, `MassSdkApplication`, server/bootstrap callers | start, stop, enabled/running checks | optional `TaskDispatchBatchListener` | running/enabled boolean | engine-starter owns engine process assembly | SDK must know whether engine operations are available without reading config. | raw engine/config backdoor | `MassSdkTest`, guard tests |
| Task shell and item operations | `MassSdkApplication`, diagnostics operations | create shell, load/list tasks, append items, patch task definition, approve/reject/block/pause/resume/cancel/terminate/seal | task id, stable request DTOs, `TaskDefinitionPatch`, item maps | `Task`, `TaskAppendReceipt`, `TaskResumeResult`, booleans | engine task shell/command/query owners | SDK core API needs task behavior without service getters. | task command/query service getters | `MassSdkTest` |
| Result read operations | `MassSdkApplication` | read visible results and archive counts | task id, message id, window cursor/limit | `TaskResultWindow`, optional result row, count | task result runtime behind engine-starter | SDK result APIs need final-result reads without owning result runtime. | task result runtime getter | `MassSdkTest` and focused compile |
| Worker topology and declaration operations | `MassSdkApplication` | register adapter node, bind node/group, declare worker group, add worker, list worker/group/topology records | worker/group/node records and ids | worker/group/node records | worker-runtime resource owner | SDK registration APIs need public worker declarations without `EngineConfig` worker-runtime getters. | worker resource declaration/query getters | `MassSdkTest` |
| Worker control, state, command, reachability operations | `MassSdkApplication`, diagnostics operations, `MassApplication` assembly | worker capability/state reports, command submit/ack/claim, reachability, exclusive lease diagnostics | worker ids, reports, commands, acknowledgements | worker projections, command records, reachability state, boolean | worker-runtime control/evidence owners | SDK/operator APIs need behavior-level access; engine-starter must only forward to worker-runtime. | worker control/heartbeat/dispatch getters | `MassSdkTest`, `EngineStarterWorkerTransportOwnershipGuardTest` |
| Transport handoff ports | `MassApplication` assembly | result ingress, selected-worker delivery target lookup, dispatch block on disconnect, pull-worker heartbeat access | selected worker id, worker id, delivery bucket id, disconnect signal | result ingest facade, delivery target evidence, heartbeat runtime | transport owns delivery/result channels; worker-runtime owns evidence | Embedded app must wire ports without making transport facts engine scheduling truth. | old config transport/worker runtime getters read directly by assembly | `TransportConvergenceArchitectureGuardTest`, `EngineStarterWorkerTransportOwnershipGuardTest` |
| Task stage and rule operations | `MassSdkApplication` | task stage evidence/projection, default rule list/replace, registered evaluator types | task/message/stage ids, stage status/detail, rules | stage projection/result, rule definitions/types | engine stage/rule owners today | SDK exposes operator operations while value contracts are still being split by TROM. | stage service and rule storage getters | `MassSdkTest` |
| Task work notification residue | `MassSdkApplication.addTaskWorkFinalListener`, `addTaskWorkAttemptClosedListener` | best-effort synchronous notification callbacks | listener callbacks | notification records | engine event service today; task-runtime outcome owner later | Existing SDK callbacks remain notification residue, not runtime correctness. | task event service getter | `MassSdkTest`; listener fanout is not used for dispatch/result correctness |

## Server Routes

| Route | Controller | Current Status | Caller | Auth/Permission | Classification | Action | API Reference Update | Frontend Adapter Action | Proof |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N/A for ECSP-1/2 | none | no server route touched | none | unchanged | not applicable | no route deletion or shape/auth/permission change in this slice | none | none | route guard applies before any future route change |

## Decisions

- ECSP-0A classified starter assembly symbols before ECSP-1 implementation.
- Inventory rows must distinguish cross-module kernel leaks from tolerated
  module-internal residue. Module-internal residue does not force ECSP
  implementation unless it can re-open a kernel boundary leak.
- Approved starter-facing surfaces must be listed in `Approved Starter
  Surfaces`; symbol rows alone are not enough to approve a new cross-boundary
  operation.
- Approved starter-facing surfaces may carry worker/transport ports, but they
  must not make `xa-mass-engine-starter` the owner of worker-runtime evidence,
  transport assigned delivery, transport result ingress, adapter lifecycle, or
  session/heartbeat lifecycle truth.
- `com.xa.mass.starter.*` advanced assembly classes are compatibility-breakable;
  keep only classes proven to be true public SDK facades.
- Public `MassApplication.getEngine()` and `MassEngine.getConfig()` are
  target-delete backdoors, not temporary-exception candidates.
- ECSP-0B classified broad SDK operation and engine-owned value/config
  imports before ECSP-2 implementation.
- ECSP-0C must classify a server route before any ECSP slice deletes it,
  changes response shape, changes auth/permission behavior, or re-owns the
  view. Core API routes are not deletion candidates for ECSP. Non-core route
  cleanup is a follow-up by default unless required to remove the current engine
  leak or keep the slice compiling.
- ECSP-4 guards may consult this inventory for temporary public-contract
  exceptions only.
- Forbidden implementation/service/config-owner imports are never valid even if
  they appear in this inventory.
- Non-core server view/control-console endpoints and frontend expectations are
  not preservation constraints for ECSP; core API behavior is the stability
  boundary.
- ECSP guards should freeze cross-module boundaries, not same-module internal
  class names or cleanup style.
- `EngineCallerSurfaceInventoryCompletenessGuardTest` must fail when a
  slice-required inventory section contains placeholder rows.
