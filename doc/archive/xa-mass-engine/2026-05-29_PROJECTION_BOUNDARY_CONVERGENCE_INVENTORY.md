# Projection Boundary Convergence Inventory

Status: PBC-0 inventory record, updated with PBC-3 server read-model rename,
writer evidence wiring, PBC-4 engine projection-write removal, and PBC-5
production ownership guards.

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

Current PBC-3 implementation follows this path: item append acceptance records
message/input/event/max-retry/create evidence, while final runtime-result
visibility supplies worker, batch, attempt, retry, timing, output, and error
evidence for the review read model.

PBC-4 then removed engine runtime writes to compatibility projection rows.
Engine production code no longer contains `TaskCompatibilityProjectionStore`,
`TaskWorkProjectionState`, storage projection enum imports, or `TaskDetailStore`
projection write calls.

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
| `TaskDetailStore` | None in engine production | PBC target | Removed from engine runtime assembly in PBC-4. |
| `com.xa.mass.storage.api.projection.*` | None in engine production | PBC target | Removed from engine production in PBC-4 after `TaskWorkLifecycleState` split. |
| `RuleStorage` | `StorageBackedMatchingRuleSetProvider` | Rule follow-up | Leave unchanged in PBC. Rule-domain boundary owns this later. |
| `com.xa.mass.kernel.spi.rule.*` | `rules/*`, `RuleBasedTaskWorkerMatchingStrategy`, `MonkeyGenerator` | Rule follow-up | Leave unchanged in PBC unless it directly blocks projection removal. |

No unexpected `mass-storage-api` production dependency was found in
`xa-mass-engine/src/main/java` during this inventory. PBC-5 now enforces this
classification through `EngineProofOwnershipGuardTest`: engine production may
import only the current task-shell/control-plane contracts
(`TaskShellStore`, `TaskShellLifecycleQuery`) and rule-domain contracts
(`RuleStorage`, `RuleDefinition`, `RuleEvaluator`, `RuleType`).

## Projection Caller Classification

| Caller | Current use | Classification | PBC action |
| --- | --- | --- | --- |
| `TaskCompatibilityProjectionStore` | Deleted from engine production. | Removed residue writer. | Completed in PBC-4. |
| `TaskManager` | No longer constructs projection store/auditor and no longer writes ingress-accepted projection residue. | Runtime assembly only. | Completed in PBC-4. |
| `TaskResultService` | No longer writes best-effort work/attempt projection residue after runtime convergence. | Runtime result convergence owner. | Completed in PBC-4. |
| `TaskProjectionStateAuditor` | Scans projection rows and compares them with runtime validation. | Explicit offline audit in engine today. | Delete or move outside engine kernel in PBC-2. |
| `TaskStateValidatorBoundaryTest` | Proves projection audit and validator separation. | Test-only projection/audit proof. | Update or delete with PBC-2. |
| `EngineProjectionResidueSuite` | Groups engine projection residue tests. | Test-only projection proof lane. | Retire or rename as read-model proof after PBC-1/PBC-4. |
| `EngineProjectionAuditSuite` | Groups engine projection audit tests. | Test-only audit proof lane. | Retire with PBC-2 if audit leaves engine. |
| `InternalTaskReviewController` | Reads `TaskReviewReadModel` for review summary, previews, and exports. | Console/review read model. | PBC-3 controller contract migration landed. |
| `TaskApiControllerTest` | Uses transitional `TaskDetailStoreTaskReviewReadModel` for review behavior. | Server read-model test. | PBC-3 test contract migration landed. |
| `ReviewReadModelSampleE2eTest` | Reads server `TaskReviewReadModel` as E2E proof helper. | Server E2E read-model proof. | PBC-3 helper rename landed. |
| `ServerReviewReadModelResidueSuite` | Groups server review read-model E2E tests. | Server E2E read-model proof. | PBC-3 suite rename landed. |
| `ServerReviewReadModelAuditSuite` | Groups server review read-model audit E2E tests. | Server E2E read-model proof. | PBC-3 suite rename landed. |

## TaskWorkProjectionState Consumers

| Consumer | Enum use | Classification | PBC action |
| --- | --- | --- | --- |
| `TaskWorkProjectionState` | Deleted from engine production. | Removed storage conversion. | Completed in PBC-4. |
| `TaskCompatibilityProjectionStore` | Deleted from engine production. | Removed residue writer. | Completed in PBC-4. |
| `TaskProjectionStateAuditor` | Compares projection enum state with runtime validation. | Projection audit. | Delete or move out of engine kernel in PBC-2. |
| `TaskWorkLifecycleState` | Owns engine-native work/attempt lifecycle enums without storage projection conversions. | Runtime event payload. | Added in PBC-4. |
| `TaskResultService` | Uses `TaskWorkLifecycleState` enums for internal event creation. | Runtime event payload. | Completed in PBC-4. |
| `TaskWorkLogicallyFinalEvent` | Record fields use `TaskWorkLifecycleState.MessageStatus` and `MessageFinalReason`. | Runtime event payload. | Completed in PBC-4. |
| `TaskWorkAttemptClosedEvent` | Record fields use `TaskWorkLifecycleState.AttemptStatus` and `AttemptFinalReason`. | Runtime event payload. | Completed in PBC-4. |
| `SimpleTaskDispatchBinder` | Uses `TaskWorkLifecycleState.AttemptStatus` for dispatch status trace/event behavior. | Runtime/trace payload. | Completed in PBC-4. |
| `TraceEventLogger` | Uses lifecycle enum names in trace event attributes. | Trace/logging payload. | Completed in PBC-4. |
| `TaskResourceReleaseListenerTest` and SDK event tests | Import native lifecycle enums. | Test proof. | Updated in PBC-4. |

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
| `ServerReviewReadModelResidueSuite` | E2E review read-model suite. | Renamed in PBC-3. |
| `ServerReviewReadModelAuditSuite` | E2E review read-model audit suite. | Renamed in PBC-3. |
| `ReviewReadModelSampleE2eTest` | Shared helper reading server review read model. | Renamed and migrated in PBC-3. |
| E2E classes extending `ReviewReadModelSampleE2eTest` | Use read-model helper for support/debug assertions. | Helper usage migrated to the new read-model contract. |

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
- The PBC-3 writer must stay best-effort relative to item append and finality
  publication; read-model write failure must not roll back accepted runtime
  work or result convergence.
- PBC-4 split engine-native lifecycle enums out of `TaskWorkProjectionState`
  and deleted engine storage conversion logic.
- PBC-5 has production ownership guards for projection-write tokens and the
  engine storage dependency allowlist.
