# Task Worker Trace Archive Direction

Status: design checkpoint for `roadmap/TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md`
TWH-5.

This note records the task/worker history events that should feed future
trace/archive read models. It does not make trace or archive authoritative for
runtime correctness, and it does not require durable task/worker history tables
under control-plane storage.

Canonical event-name vocabulary remains
`platform_infra/mass-trace-sink/.../ExecutionEventType.java`.

## Placement Rule

- Runtime hot paths may emit trace asynchronously, but must not wait for archive
  availability before completing queue, lease, assignment, result, or worker
  lifecycle work.
- Operator history, analytics, and cross-task timelines should be materialized
  from trace/archive read models.
- Control-plane storage should keep task shell and worker declaration truth,
  not task-item attempt timelines, worker heartbeat history, dispatch history,
  or candidate rejection analytics.

## Candidate Coverage

| Candidate history need | Current coverage | Direction |
| --- | --- | --- |
| task shell created | partial via `TASK_STATUS_TRANSITION` if emitted at creation | Keep as trace materialized view unless creation-specific audit becomes required. |
| task sealed / approved / blocked / resumed / terminal | `TASK_STATUS_TRANSITION`, `TASK_TERMINAL_CLOSED` | Existing event types are enough for state timeline. |
| item appended | partial via `TASK_WORK_STATUS_TRANSITION` when item enters runtime visibility | Treat item-created timeline as a materialized view from work status transition plus task payload metadata. Add a dedicated event only if append without runtime visibility needs audit. |
| item claimed / dispatched | `DISPATCH_REQUESTED`, `DISPATCH_BINDING_SUMMARY`, `TASK_WORK_ATTEMPT_STATUS_TRANSITION` | Existing event types are enough for dispatch timeline. |
| item retried after lease expiry | `LEASE_EXPIRED`, `TASK_WORK_RETRY_RESET`, `TASK_WORK_ATTEMPT_CLOSED` | Existing event types are enough. |
| item completed / logically final | `CALLBACK_ACCEPTED`, `TASK_WORK_LOGICALLY_FINAL`, `TASK_WORK_ATTEMPT_CLOSED` | Existing event types are enough. |
| stale / duplicate / late result rejected | `CALLBACK_IGNORED_DUPLICATE`, `CALLBACK_IGNORED_LATE`, `CALLBACK_REJECTED_NO_ACTIVE_LEASE`, `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT`, `CALLBACK_REJECTED_INVALID_STATE` | Existing event types are enough. |
| worker declared / declaration changed | gap | Future trace event needed if declaration history becomes an operator/archive requirement. Do not model this as JDBC worker history tables. |
| worker connected / disconnected | `WORKER_ONLINE`, `WORKER_OFFLINE` | Existing event types are enough for transport presence timeline. |
| heartbeat stale / recovered | partial via `WORKER_OFFLINE`, `WORKER_ONLINE` | Existing presence events are enough unless heartbeat-specific diagnostics need a separate reason code. Prefer payload reason before adding enum. |
| dispatch gate disabled / cleared | partial via `WORKER_STATE_REPORT_APPLIED` and command/status events | Prefer a materialized view from state report and command status. Add dedicated event only if gate changes become independent operator audit. |
| candidate selected | `WORKER_MATCH_ACCEPTED`, `WORKER_LOCK_ACQUIRED`, `ASSIGNMENT_SUMMARY` | Existing event types are enough. |
| candidate rejected with reason | `WORKER_MATCH_REJECTED` | Existing event type is enough if payload carries reason. |
| dispatch delivered / skipped / failed | `DISPATCH_BINDING_SUMMARY`, `DISPATCH_SKIPPED`, `RESOURCE_RELEASE_FAILED` | Existing event types cover current needs. Add a dedicated delivery-failed event only if transport ACK/failure becomes externally visible. |
| assignment queue pressure / delayed wakeup | `ASSIGNMENT_QUEUE_SNAPSHOT`, `ASSIGNMENT_RETRY_SCHEDULED`, `ASSIGNMENT_SUMMARY` | Existing event types are enough. |
| worker command delivery and state | `WORKER_COMMAND_STATUS_TRANSITION` | Existing event type is enough. |
| worker capability / state report accepted | `WORKER_CAPABILITY_REPORT_APPLIED`, `WORKER_STATE_REPORT_APPLIED` | Existing event types are enough. |

## Vocabulary Gaps

Current true gaps:

- worker declaration created / changed

Conditional gaps, not immediate enum work:

- task item appended before runtime visibility
- heartbeat stale/recovered as distinct from online/offline
- dispatch gate disabled/cleared as distinct audit
- transport delivery failed as distinct from binding summary/resource release

These should stay conditional until an operator/archive consumer requires a
query that cannot be derived from the current events plus payload reason fields.

## Archive Read Models

Likely future materialized views:

- task lifecycle timeline by `taskId`
- task work timeline by `taskId`, `messageId`, `attemptId`
- worker presence timeline by `workerId`
- worker declaration timeline by `workerId`
- assignment/candidate rejection timeline by `taskId` and `workerGroupId`

These views should be built from trace/archive ingestion. They are not runtime
stores and must not be introduced as `mass-storage-jdbc` control-plane tables.
