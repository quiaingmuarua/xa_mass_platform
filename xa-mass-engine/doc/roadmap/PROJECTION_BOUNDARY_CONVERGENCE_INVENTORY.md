# Projection Boundary Convergence Inventory

Status: PBC-0 inventory record.

This file is the working inventory for
[`PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md`](PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md).
It records current code facts and the PBC-3 read-model decision before
implementation slices change behavior.

## Summary Decision

Immediate PBC path: **Path A, server/read-model-side assembly**.

Engine runtime should stop writing compatibility task message/attempt
projection rows, but console review/export still needs a deterministic read
model that covers fields not currently available from trace events alone. The
first implementation path should therefore move projection assembly out of the
engine kernel and into a server/read-model owner.

Path B, trace/archive/runtime-derived review, remains the target direction once
the trace/archive read API can cover the complete review/export field set. It
is not the immediate PBC-3 path because current trace events do not carry
input, output, payload ref, max retry count, full timing fields, or aggregate
message stats.

No runtime correctness dependency on compatibility projection rows was found.
The current projection dependencies are residue writes, console/read-model
reads, offline audit, and test proof residue.

## Engine Storage Dependency Classification

| Dependency | Current caller | Classification | PBC action |
| --- | --- | --- | --- |
| `TaskShellStore` | `TaskManager` | Keep for now | Task shell/control-plane storage contract. Not a PBC target. |
| `TaskShellLifecycleQuery` | `TaskManager` | Keep for now | Lifecycle lookup contract. Not a PBC target. |
| `TaskDetailStore` | `TaskManager`, `TaskCompatibilityProjectionStore` | PBC target | Remove from engine runtime assembly after read-model replacement exists. |
| `com.xa.mass.storage.api.projection.*` | `TaskWorkProjectionState` | PBC target | Delete engine storage projection conversion after native lifecycle enums are split out. |
| `RuleStorage` | `StorageBackedMatchingRuleSetProvider` | Rule follow-up | Leave unchanged in PBC. Rule-domain boundary owns this later. |
| `com.xa.mass.storage.rule.*` | `rules/*`, `RuleBasedTaskWorkerMatchingStrategy`, `MonkeyGenerator` | Rule follow-up | Leave unchanged in PBC unless it directly blocks projection removal. |

No unexpected `mass-storage-api` production dependency was found in
`xa-mass-engine/src/main/java` during this inventory.

## Projection Caller Classification

| Caller | Current use | Classification | PBC action |
| --- | --- | --- | --- |
| `TaskCompatibilityProjectionStore` | Builds and updates `TaskDetailStore` message/attempt projection rows. | Result convergence residue write and ingress residue write. | Delete from engine runtime after PBC-3. |
| `TaskManager` | Constructs projection store/auditor and writes ingress-accepted projection residue. | Assembly and residue write. | Remove projection constructor requirements and ingress writes in PBC-4. |
| `TaskResultService` | Writes best-effort work/attempt projection residue after runtime convergence. | Result convergence residue write. | Remove best-effort projection writes in PBC-4. |
| `TaskProjectionStateAuditor` | Scans projection rows and compares them with runtime validation. | Explicit offline audit in engine today. | Delete or move outside engine kernel in PBC-2. |
| `TaskStateValidatorBoundaryTest` | Proves projection audit and validator separation. | Test-only projection/audit proof. | Update or delete with PBC-2. |
| `EngineProjectionResidueSuite` | Groups engine projection residue tests. | Test-only projection proof lane. | Retire or rename as read-model proof after PBC-1/PBC-4. |
| `EngineProjectionAuditSuite` | Groups engine projection audit tests. | Test-only audit proof lane. | Retire with PBC-2 if audit leaves engine. |
| `InternalTaskReviewController` | Reads `TaskDetailStore` projection rows for review summary, previews, and exports. | Console/review read model. | Replace with server/read-model owner in PBC-3 before PBC-4. |
| `TaskApiControllerTest` | Mocks projection rows for review behavior. | Server read-model test. | Move to selected PBC-3 read model. |
| `ProjectionSampleE2eTest` | Reads projection rows as E2E proof helper. | Server E2E proof residue. | Migrate helper to new read model or rename as explicit read-model proof in PBC-3. |
| `ServerProjectionResidueSuite` | Groups server projection residue E2E tests. | Server E2E proof residue. | Migrate/rename/delete according to PBC-3 read-model owner. |
| `ServerProjectionAuditSuite` | Groups server projection audit E2E tests. | Server E2E proof residue. | Migrate/rename/delete according to PBC-3 read-model owner. |

## TaskWorkProjectionState Consumers

| Consumer | Enum use | Classification | PBC action |
| --- | --- | --- | --- |
| `TaskWorkProjectionState` | Converts engine residue enums to storage projection enums. | Storage conversion. | Delete after native lifecycle enums exist and projection writes are removed. |
| `TaskCompatibilityProjectionStore` | Uses message/attempt enums for projection rows. | Storage conversion and residue write. | Delete with projection writes. |
| `TaskProjectionStateAuditor` | Compares projection enum state with runtime validation. | Projection audit. | Delete or move out of engine kernel in PBC-2. |
| `TaskResultService` | Uses enums for convergence residue and internal event creation. | Runtime event payload plus residue write. | Split native engine lifecycle enums before removing projection conversion. |
| `TaskWorkLogicallyFinalEvent` | Record fields use `MessageStatus` and `MessageFinalReason`. | Runtime event payload. | Move to engine-native lifecycle enum type before deleting `TaskWorkProjectionState`. |
| `TaskWorkAttemptClosedEvent` | Record fields use `AttemptStatus` and `AttemptFinalReason`. | Runtime event payload. | Move to engine-native lifecycle enum type before deleting `TaskWorkProjectionState`. |
| `SimpleTaskDispatchBinder` | Uses `AttemptStatus` for dispatch status trace/event behavior. | Runtime/trace payload. | Move to engine-native attempt lifecycle type. |
| `TraceEventLogger` | Uses message/attempt enums in trace event attributes. | Trace/logging payload. | Move to engine-native lifecycle type and keep trace payload names stable as strings. |
| `TaskResourceReleaseListenerTest` and projection tests | Imports attempt/message enums. | Test-only helper/proof. | Update with native lifecycle enum split or delete projection-specific assertions. |

## InternalTaskReviewController Field Coverage

| Review/export field | Current source | Existing trace coverage | Immediate replacement source |
| --- | --- | --- | --- |
| `taskId`, task name, project | `TaskQueryOperations.getTaskDetail` | Task id appears in trace identity; task metadata is not trace owned. | Keep task query path. |
| `messageId` | `TaskMessageProjection.messageId` | Present in work/attempt trace identity. | Read-model item identity. |
| `eventCode` | `TaskMessageProjection.input["eventCode"]` | Not reliably emitted as trace attribute. | Read-model item input. |
| `input` | `TaskMessageProjection.input` | Not emitted in current trace events. | Read-model item input from ingress/runtime append path. |
| `payloadRef` | `TaskMessageProjection.payloadRef` | Present in `TaskWorkLogicallyFinalEvent`, not in current trace payload. | Read-model item payload ref. |
| `status` | `TaskMessageProjection.status` | Work status transitions and final events cover status strings. | Read-model item lifecycle state. |
| `finalReason` | `TaskMessageProjection.finalReason` | Work trace attrs include final reason for final/transition events. | Read-model item lifecycle state. |
| `retryCount` | `TaskMessageProjection.retryCount` | Final trace attrs include retry count; transition coverage is partial. | Read-model item retry state. |
| `maxRetryCount` | `TaskMessageProjection.maxRetryCount` | Not emitted in current trace events. | Read-model item runtime contract snapshot. |
| `latestAttemptId` | `TaskMessageProjection.latestAttemptId` | Present in trace identity for attempt/final events. | Read-model latest attempt summary. |
| `latestAttemptWorkerId` | `TaskMessageProjection.latestAttemptWorkerId` | Present in trace identity for attempt/final events. | Read-model latest attempt summary. |
| `latestAttemptBatchId` | `TaskMessageProjection.latestAttemptBatchId` | Present in selected trace attrs. | Read-model latest attempt summary. |
| `createTime` | `TaskMessageProjection.createTime` | Not emitted as a dedicated review field. | Read-model ingress timestamp. |
| `assignedTime` | `TaskMessageProjection.assignedTime` | Dispatch trace can imply assignment, but no direct field. | Read-model assignment timestamp. |
| `startTime` | `TaskMessageProjection.startTime` | Attempt transition exists, but no direct review field. | Read-model attempt timestamp. |
| `completeTime` | `TaskMessageProjection.completeTime` | Final/attempt closed event time can imply it. | Read-model finality timestamp. |
| `updateTime` | `TaskMessageProjection.updateTime` | Event time can imply it. | Read-model last update timestamp. |
| `errorCode` | `TaskMessageProjection.errorCode` | Final trace outcome includes error code. | Read-model finality/error summary. |
| `errorMessage` | `TaskMessageProjection.errorMessage` | Present in `TaskWorkLogicallyFinalEvent`, not in current trace payload. | Read-model finality/error summary. |
| `output` | `TaskMessageProjection.output` | Present in `TaskWorkLogicallyFinalEvent`, not in current trace payload. | Read-model final output. |
| aggregate stats | `TaskMessageStats` | Not directly available from trace without an assembler. | Read-model aggregate maintained by server/read-model owner. |

Conclusion: existing trace event types are useful evidence, but current trace
payloads are insufficient as a drop-in replacement for review/export. PBC-3
must either add an assembler with richer inputs or use a server/read-model-side
projection writer. The immediate PBC path chooses the latter.

## Server Projection Proof Assets

| Asset | Current role | PBC owner decision |
| --- | --- | --- |
| `ServerProjectionResidueSuite` | E2E projection residue suite. | Rename or replace as server read-model proof in PBC-3. |
| `ServerProjectionAuditSuite` | E2E projection audit suite. | Delete or move to explicit admin/read-model audit if audit survives PBC-2. |
| `ProjectionSampleE2eTest` | Shared helper reading projection rows. | Migrate to new server/read-model query helper in PBC-3. |
| `CrawlerPullWorkerSdkRegistrationIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `DevSampleWorkerLauncherIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `H2ExternalWorkerPollingApiIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `JavaPollingWorkerBlackBoxIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `JavaSocketWorkerBlackBoxIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `JavaWebSocketWorkerBlackBoxIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `NodePollingWorkerBlackBoxIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `NodeSocketWorkerBlackBoxIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `NodeWebSocketWorkerBlackBoxIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `PostgresExternalWorkerPollingApiIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `RuntimeLateReplayE2eScenario` | Extends projection helper. | Migrate helper usage to runtime/result/read-model proof according to scenario intent. |
| `SdkTaskApiIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `TaskApiMultiRoundDispatchIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `TaskApiTargetedWorkerDebugIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |
| `TaskApiTerminateReuseIntegrationTest` | Extends projection helper. | Migrate helper usage to new read-model proof. |

## PBC-0 Acceptance Mapping

1. Every current projection caller has a classification in the tables above.
2. No projection row read was found on scheduling, terminal policy, dispatch
   eligibility, or result convergence correctness paths.
3. Console/read-model callers are isolated to server review/export and E2E
   proof helpers.
4. Engine production `mass-storage-api` dependencies are classified as
   keep-for-now, PBC target, or rule follow-up.
5. `TaskWorkProjectionState` enum consumers are classified before PBC-4.
6. PBC-3 selected Path A with Path B recorded as a follow-up direction.
7. `InternalTaskReviewController` field coverage is mapped above.
8. Server projection proof assets have owner decisions.
9. This inventory changes documentation only; no runtime behavior changes.

## Next Slice Constraints

- PBC-1 should update engine runtime correctness tests before deleting any
  projection writes.
- PBC-2 can remove the engine projection auditor independently if tests stop
  treating it as runtime truth.
- PBC-3 must provide the server/read-model-side assembler/writer before PBC-4
  removes engine projection writes.
- PBC-4 must split engine-native lifecycle enums out of
  `TaskWorkProjectionState` before deleting storage conversion logic.
- PBC-5 should use `@CompatibilityProjectionOnly` plus an engine storage
  dependency allowlist as guard inputs.
