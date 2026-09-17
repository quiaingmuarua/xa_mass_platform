# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel owns Task configuration, scheduling, claim, retry and finality. A descriptor
stores optional Pool supply declarations. Kernel validates immutable structure;
Matching interprets targets, qualifications and resource access without Task IDs.

```text
TaskDescriptor = taskId, workerGroupId, idleDisposition, config, refill
RefillTarget = poolName, target, count
TaskItem = messageId, eventCode, createdAtMillis, payload, priority,
           expireAtMillis, workerSelector
```

Task config contains exactly string `priority` and `maxRetryTimes`. Supply allows
0..100 declarations, each with nonblank poolName, required EligibilityQuery target
and count 1..1000. Empty target is `{}`; empty refill is no supply. Equality includes
all declarations. Kernel does not infer defaults, resolve Pools or interpret fields.

## Cross-Owner Creation

Server normalizes explicit supply through Matching before Kernel create. Ordinary
Task omission means `[]`, explicit null fails. Managed Call registration also saves no supply unless its Group has explicit
refill declarations configured. Saved declarations are a
creation-time snapshot. Re-registration compares the full expected descriptor;
normal lookup does not recompute current defaults.

Descriptor creation is create-only in one Lua. Score initialization and descriptor
writing retain separate commit and retry boundaries; they are not one transaction.
There is no separate Matching binding or configuration store.

All Item requests explicitly carry WorkerQuery(executorName,input). Server uses
Matching for admission, Kernel captures and stores bounded JSON without interpreting
it. Finite append retains per-item rejection; managed Call validates its whole batch
before writing. Supply does not constrain the Item function or confer query rights.

## Scheduling Handoff

Main shares immutable NORMAL Task descriptors. Refill concatenates declarations by
Group; Matching chooses Pools, normalizes and MAX-merges targets, and pages bounded
operations. Pacer acquires candidate leases before eligibility reads. Stock retains
the original fence and deadline. With no demand, maintenance still reaps expired
stock and inactive cursors, but no refill HOT read/acquire occurs.

Dispatch forwards explicit Group and messageId-to-WorkerQuery data. Matching returns
at most one candidate per Item. Query interpretation and grouping stay in Matching;
Kernel owns execution admission and Item claim. Pool results use original nonzero
fences; identity/index hints use zero only at Pacer, selecting Kernel's distinct
current-state acquisition method. Command and ResultContext use the new sealed fence.

Tasks share Group/Pool stock without quotas. Closing a supplier stops subsequent
supply hints, without clearing inventory or revoking another Task's query. Item
queries never create demand. No views, closures or Rule instances cross module boundaries.

Finite Tasks use CLOSE_WHEN_IDLE; managed Calls use PARK_WHEN_IDLE. Candidate
failure does not block Item exhaustion, expiry or idle settlement. Item terminal
outcomes remain terminal for scheduling while admitting later monotonic observations,
independently of Task closure. Stock invalidation never reopens scheduling.

## Redis Shape

The existing descriptor HASH has exactly `workerGroupId`, `idleDisposition`,
`configJson`, `refillJson`, written together by the create-only Lua. refillJson is an
array of `{poolName,target,count}`; an empty array is valid. Missing fields, retired
ruleId/refillTargetsJson, invalid targets/counts and corrupt JSON fail reading.
No old Matching HASH is consulted and no default is substituted.

TaskItem workerSelector remains the complete `{executorName,input}` WorkerQuery.
Root input is required and non-null; bounded immutable JSON and current encoding
remain unchanged. Old direct selectors remain unreadable, never converted to ANY.

Use a new scope to recreate Tasks. No dual reader, conversion, data migration or
cleanup is added. Facts, indexes and Worker scores remain under their existing
Owners. Matching storage belongs to [its Owner](../../../worker_matching_jvm/README.md).
