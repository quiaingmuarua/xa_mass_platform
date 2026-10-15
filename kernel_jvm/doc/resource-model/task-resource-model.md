# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel owns Task configuration, scheduling, claim, retry and finality. A descriptor
stores optional Pool supply declarations. Kernel validates immutable structure;
Matching interprets targets, qualifications and resource access without Task IDs.

```text
TaskDescriptor = taskId, workerGroupId, idleDisposition, config, refill, name?, metadata
RefillTarget = poolName, target, count
TaskItem = messageId, eventCode, createdAtMillis, payload, priority,
           expireAtMillis, workerSelector
```

Task config contains exactly string `priority` and `maxRetryTimes`. Supply allows
0..100 declarations, each with nonblank poolName, required EligibilityQuery target
and count 1..1000. Empty target is `{}`; empty refill is no supply. Equality includes
all declarations. Kernel does not infer defaults, resolve Pools or interpret fields.

Optional name (nonblank, at most 128 characters) and metadata (at most 32 string
entries, nonblank keys up to 128 characters and values up to 4096) are immutable
application display data. Kernel never interprets business fields or stores counts
in them. Missing display fields mean unknown/empty, with no read-time write.

## Cross-Owner Creation

Server normalizes explicit supply through Matching before Kernel create. Ordinary
Task omission means `[]`, explicit null fails. Managed Call registration also saves no supply unless its Group has explicit
refill declarations configured. Saved declarations are a
creation-time snapshot. Re-registration compares the full expected descriptor;
normal lookup does not recompute current defaults.

Descriptor creation writes only the descriptor HASH in one create-only Lua. Score
initialization and descriptor writing retain separate commit/retry boundaries;
they are not one transaction. Kernel validates the generic string metadata shape
but does not interpret Project, User, role or application creation timestamps.
A pure Kernel Task needs none of these metadata fields and remains globally observable.

**Ownership change:** Project attribution and its lookup index belong to the
[Server task directory](../../../server_jvm/README.md#task-attribution-and-directory).
Kernel no longer has a dedicated Project field, Project listing/point-membership
operations or Project ZSET writes. Server saves its immutable attribution inside
ordinary metadata, then independently confirms its index. The global `task:score`,
Item mutation, execution and all scheduling/Pool ownership remain unchanged.

All Item requests explicitly carry WorkerQuery(executorName,input). Server uses
Matching for admission, Kernel captures and stores bounded JSON without interpreting
it. Finite append retains per-item rejection; managed Call validates its whole batch
before writing. Supply does not constrain the Item function or confer query rights.

## Scheduling Handoff

Main shares immutable NORMAL Task descriptors. Refill concatenates declarations by
Group; Matching chooses Pools, normalizes and MAX-merges targets, and pages bounded
operations. Pacer candidateizes before eligibility reads, preserving Worker time.
Stock keeps the opaque generation and a local admission TTL. Without demand,
maintenance expires stock/cursors and Pacer may recycle old candidates in Main
Groups, but it does not perform ordinary HOT supply reads/candidateization.

Dispatch forwards explicit Group and messageId-to-WorkerQuery data. Matching returns
at most one candidate per Item. Query interpretation and grouping stay in Matching;
Kernel owns execution admission and Item claim. Pool results use original nonzero
fences; identity/index hints use zero only at Pacer, selecting Kernel's distinct
current-state acquisition method. Command and ResultContext use the new execution fence.

Tasks share Group/Pool stock without quotas. Closing a supplier stops subsequent
supply hints, without clearing inventory or revoking another Task's query. Item
queries never create demand. No views, closures or Rule instances cross module boundaries.

Finite Tasks use CLOSE_WHEN_IDLE; managed Calls use PARK_WHEN_IDLE. Candidate
failure does not block Item exhaustion, expiry or idle settlement. Item terminal
outcomes remain terminal for scheduling while admitting later monotonic observations,
independently of Task closure. Stock invalidation never reopens scheduling.

## Redis Shape

The descriptor HASH has exactly `workerGroupId`, `idleDisposition`,
`configJson`, `refillJson`, `metadataJson`, `dataPhase`, plus optional `name` and
`frozenAtMillis`. Creation writes dataPhase=OPEN; business descriptor fields remain
create-only, while Kernel retention changes only dataPhase and frozenAtMillis. refillJson is an
array of `{poolName,target,count}`, with an empty refill array allowed.
metadataJson encodes only a string Map (absent means empty). Missing required scheduling fields, retired
ruleId/refillTargetsJson, invalid targets/counts and corrupt JSON fail reading.
No old Matching HASH is consulted and no default is substituted.

TaskItem workerSelector remains the complete `{executorName,input}` WorkerQuery.
Root input is required and non-null; bounded immutable JSON and current encoding
remain unchanged. Old direct selectors remain unreadable, never converted to ANY.

Use a new scope to recreate Tasks. No dual reader, conversion, data migration or
legacy-data cleanup is added. Fresh finite Tasks use the retention mechanism below.
Facts, indexes and Worker scores remain under their existing
Owners. Matching storage belongs to [its Owner](../../../worker_matching_jvm/README.md).

## Finite Task Data Retention

TaskRuntime owns `OPEN -> FROZEN -> RELEASED -> PURGING` in the existing descriptor
HASH. It exposes bounded phase reads and exact-terminal-observation operations:
freezeTaskData, releaseTaskPayloads, beginTaskPurge, purgeTaskData, finishTaskPurge.
Score interpretation stays with TaskScoreBandCore; each resource script then
exact-compares the admitted observation. Finite disposition is checked atomically.
The named cross-key fence is necessary to prevent delayed writes recreating data.

Every Item HASH write, Item Score initialization/CAS/promotion and Result commit
checks OPEN in the same Redis operation. FROZEN seals both independent projections;
RELEASED unlinks the Item and Result HASHes only after Server confirms its archive.
Item Score remains readable, including unfinished members. PURGING blocks further
application access and allows idempotent unlink of Item, Result and Item Score.
The final operation requires those resources absent, deletes descriptor metadata,
then removes only this Task's member from the global Score ZSET atomically.

Kernel owns no archive path, retention duration, scan loop, Project index, or
creation-request association. Server orchestrates those effects. RPC Tasks are
excluded from the retention operations. Missing Task data rejects late mutations
rather than creating a replacement resource. No Worker, Pool or shared queue is deleted.
