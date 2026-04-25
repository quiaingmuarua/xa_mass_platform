# High-Volume Schema Plan

Last updated: 2026-04-25

This document defines the compressed field model for the high-volume mainline.

Use it when the task is about:

- deciding which current fields stay on `Task`
- deciding which `TaskMsg` fields leave the hot path
- defining the queue envelope and output envelope
- deciding which attempt details remain required vs optional

Use with:

- [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md)
- [./HIGH_VOLUME_MIGRATION_MAP.md](./HIGH_VOLUME_MIGRATION_MAP.md)
- [./HIGH_VOLUME_BATCHING_DECISION.md](./HIGH_VOLUME_BATCHING_DECISION.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

Important status note:

- this is the target compressed schema plan
- exact class names and field names may evolve during migration
- current code reality still lives in the existing model classes until migrated

## 1. Design Rule

Keep four layers distinct:

1. `Task shell`
   - control plane
   - lifecycle, ingest, counters, ownership
2. `Queue envelope`
   - hot runnable unit
   - minimal dispatch and retry state
3. `Output envelope`
   - hot result unit
   - minimal result convergence state
4. `Optional audit`
   - richer attempt and diagnostic history

Working rule:

- do not let one class act as all four layers at once

## 2. Target Task Shell

The task shell should stay small and frequently writable.

### 2.1 Keep On Task

Required task-shell fields:

- `taskId`
- `taskName`
- `project`
- `user`
- `status`
- `terminalReason`
- `sharedConfig`
- `sourceType`
- `ingestStatus`
- `intakeStatus`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `maxRuntimeSeconds`
- `taskTargetNumber`
- `enqueuedCount`
- `inflightCount`
- `successCount`
- `failedCount`
- `expiredCount`
- `createTime`
- `updateTime`
- `startTime`
- `endTime`

Optional task-shell fields:

- `holdReason`
- `sourceRef`
- `ingestCursor`
- `lastErrorCode`
- `lastErrorMessage`

### 2.2 Remove From Task Hot Path

Do not keep these as required hot-path task responsibilities:

- ownership of the full message collection
- repeated scan-based convergence logic
- any requirement to expose all item payloads on task detail reads

### 2.3 Current-To-Target Notes

Current `Task` fields with a clear future:

- keep:
  - `tid`
  - `taskName`
  - `project`
  - `status`
  - `sharedConfig`
  - `intakeStatus`
  - `user`
  - `terminalReason`
  - `maxRuntimeSeconds`
  - `createTime`
  - `updateTime`
  - `startTime`
  - `endTime`
  - `batchSize`
- likely keep but rename or reinterpret:
  - `taskTargetNumber`
  - `taskSuccessNumber`
  - `taskEligibleNumber`
  - `taskNonSuccessNumber`
- likely drop from default high-volume task shell:
  - `minRequiredWorkerCount`
  - `peakAssignedWorkerCount`
- compatibility projection only:
  - `openEnded`

## 3. Target Queue Envelope

This is the default runnable unit for ready and inflight queues.

### 3.1 Required Fields

- `taskId`
- `messageId`
- `eventCode`
- `payload` or `payloadRef`
- `retryCount`
- `maxRetryCount`
- `leaseToken`
- `workerHint`
- `workerContextHint`
- `shardKey`
- `nextVisibleAt`
- `createdAt`

### 3.2 Optional Fields

- `batchId`
- `project`
- `userId`
- `transportHint`
- `routingTags`
- `priority`

Working rules:

- `payload` should stay small
- when payload is large, use `payloadRef`
- do not embed full audit or UI projection fields in the queue envelope

### 3.3 Fields That Do Not Belong Here

- long `errorMessage`
- large `output`
- full attempt timeline
- multiple transport progress timestamps
- task-level counters
- UI-only presentation fields

## 4. Target Output Envelope

This is the minimal result unit written by worker/result adapters.

### 4.1 Required Fields

- `taskId`
- `messageId`
- `leaseToken`
- `success`
- `detail`
- `errorCode`
- `output` or `outputRef`
- `completedAt`

### 4.2 Optional Fields

- `workerId`
- `workerContextId`
- `batchId`
- `attemptNo`

Working rules:

- result convergence should only require the minimal fields needed for idempotent acceptance and counter updates
- richer diagnostics should be routed to optional audit or trace surfaces

## 5. Target Active Lease Record

The active lease record is the correctness-critical replacement for "full
attempt history on every message".

Required fields:

- `taskId`
- `messageId`
- `leaseToken`
- `workerId`
- `workerContextId`
- `batchId`
- `leaseExpireAt`
- `leasedAt`
- `retryCount`

Optional fields:

- `transportHint`
- `dispatcherId`
- `shardKey`

Working rules:

- there is at most one active lease record per runnable unit
- result acceptance must validate against the active lease record
- lease expiry recovery reads this record, not a whole-task message scan

## 6. Optional Audit Record

The audit record replaces the idea that `TaskMsgAttempt` must always be the
primary hot-path truth.

### 6.1 Recommended Modes

- `OFF`
- `FAILURE_ONLY`
- `SAMPLED`
- `SHORT_RETENTION`
- `FULL`

### 6.2 Suggested Fields

- `taskId`
- `messageId`
- `attemptNo`
- `leaseToken`
- `workerId`
- `workerContextId`
- `statusTimeline`
- `errorCode`
- `errorMessage`
- `output`
- `createTime`
- `finishTime`

Working rule:

- audit mode is a runtime/storage policy, not a reason to force the default hot path to stay thick

## 7. TaskMsg Compression Plan

Current `TaskMsg` is mixing three concerns:

- logical message identity
- hot execution state
- compatibility and UI projection

Target direction:

- replace the thick default `TaskMsg` role with:
  - queue envelope
  - active lease record
  - optional read-model projection

### 7.1 Keep As Hot Truth

- `taskId`
- `messageId`
- `retryCount`
- `maxRetryCount`
- small status truth
- minimal worker binding truth while inflight

### 7.2 Move Out Of Default Hot Truth

- `input`
  - keep only as small payload or `payloadRef`
- `output`
  - keep only in output envelope or optional result store
- `errorMessage`
  - move to bounded result/audit view
- `latestAttemptWorkerId`
- `latestAttemptWorkerContextId`
- `latestAttemptBatchId`
- `assignedTime`
- `startTime`
- `completeTime`
- `finalReason`

These can still exist as compatibility or read-model projections during the migration.

## 8. TaskMsgAttempt Compression Plan

Current `TaskMsgAttempt` bundles:

- active lease truth
- transition timeline
- callback snapshot
- long-lived audit semantics

Target direction:

- split it into:
  - `ActiveLeaseRecord` for correctness
  - optional `AttemptAuditRecord` for history

### 8.1 Must Stay On Hot Path

- lease owner
- lease timeout
- retry/attempt identity token
- minimal terminal acceptance state

### 8.2 Can Leave Hot Path

- ack timestamp
- running timestamp
- finish timestamp
- full callback payload snapshot
- long error strings
- full status timeline

## 9. Message State Compression

The default high-volume message state should be smaller than the current
`TaskMsgStatus` plus `TaskMsgAttemptStatus` combination.

### 9.1 Target Default State Intent

- `READY`
- `LEASED`
- `DONE`
- `FAILED`
- `DELAYED`

### 9.2 Mapping Guidance

- current `INIT` maps to `READY`
- current active attempt plus `ASSIGNED/RUNNING` maps to `LEASED`
- current final success maps to `DONE`
- current retry-exhausted failure maps to `FAILED`
- current backoff window maps to `DELAYED`

Richer transport phases can survive as:

- trace events
- adapter-local state
- optional audit state

## 10. Ingest State

Task ingest needs its own small state model.

Suggested states:

- `PENDING`
- `INGESTING`
- `READY`
- `FAILED`
- `SEALED`

Intent:

- `PENDING`: task created, source not yet consumed
- `INGESTING`: importer or stream append is active
- `READY`: source has produced runnable queue units
- `FAILED`: ingest failed before completion
- `SEALED`: no more new work units may enter from the source

Working rule:

- task execution status and ingest status are related but not identical

## 11. API Compatibility Guidance

### 11.1 Preserve First

Keep stable first:

- `POST /status/api/tasks`
- `appendTaskItems`
- `sealTask`
- worker polling dispatch contract
- worker result submission contract

### 11.2 Reinterpret Internally

These can be kept at the API edge while changing internals:

- `openEnded`
- `inputs`
- `batchSize`
- `defaultMsgMaxRetryCount`

### 11.3 Likely Future Additions

- `sourceType`
- `fileRef` or upload token
- ingest progress response fields
- task summary counters exposed explicitly

## 12. Recommended Class Split

Target class family:

- `Task`
- `TaskIngestState`
- `TaskQueueEnvelope`
- `TaskOutputEnvelope`
- `ActiveLeaseRecord`
- `AttemptAuditRecord`
- `TaskCountersView`
- `TaskMessageOperationalView`

Working rule:

- even if migration is incremental, the code should move toward this split instead of pushing new responsibility back into the current thick classes
