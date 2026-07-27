# Result-Routing Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contracts:
[Task Item Score-Band Scheduling](task-item-score-band-scheduling.md) and
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Redis shape: [Seed Result Runtime Redis Shape](../runtime-redis/seed-result-runtime-redis-shape.md).

## Purpose

Result routing consumes three bounded evidence classes and invokes their real
owners:

```text
SUCCESS
  -> TaskRuntime last-success result HASH
  -> TaskItemScoreBandCore FINAL_SUCCESS
  -> WorkerScoreCore exact release

WORKER_FAILURE
  -> WorkerScoreCore exact release

ADAPTER_REJECTION
  -> WorkerScoreCore exact RECOVERY_RECHECK demotion
```

It does not select Workers, claim Items, actively retry failed Items, refresh
Task score, parse score internals, or own Worker scheduling-serviceability
truth.

## Protocol And Queues

```python
SeedResult(
    command_id: str,
    opaque_result_context: str,
    outcome_code: str,
    opaque_result_payload: str | None,
)
```

Outcome classification is exact:

```text
"200"  -> SUCCESS
"1xxx" -> WORKER_FAILURE
"3xxx" -> ADAPTER_REJECTION
```

`1xxx` and `3xxx` are exactly four ASCII digits. Result routing understands
only the class. A `200` must carry a non-empty opaque payload; JSON `"null"`
represents a successful handler with no business return value.
`commandId` is canonical trace correlation copied from the outbound command.
Result routing does not use it as an Item identity, deduplication key, outcome
winner, or Worker lease fence.

The Java Server Worker Delivery point result endpoint accepts Worker-originated
`200` and `1xxx` only. Its Adapter batch endpoint also accepts trusted `3xxx`
evidence that a delivery was rejected before entering Worker execution. A
WebSocket Adapter reaches that endpoint over HTTP in both embedded and
standalone deployments. The Server appends the corresponding outcome-class
Redis queue but does not consume or interpret routing policy. Authentication
of that Adapter role remains deferred. Result routing does not distinguish
producers and never derives `3xxx` from timeout, missing response, or mailbox
age.

Each class has an independent Redis LIST. The result context remains opaque to
the queue runtime and carries `taskId`, `messageId`, `workerId`,
`workerGroupId`, and opaque `workerLeaseScore`. `workerGroupId` is the
home-bucket coordinate of the opaque Worker lease fence; result routing does
not reread Task metadata to recover it. Item claim score and claim-until time
are not result-routing inputs. The claim-until time remains a top-level
`WorkerCommandEnvelope.executeBeforeMillis` cutoff used before Worker submit.

## Routing Round

One round calls the three class lanes in a fixed implementation order. Normal
protocol produces one logical outcome per DeliverSeed, although Worker Delivery
Dispatch may duplicate the same evidence. Duplicate queue records converge through
last-success storage and exact score fences; they do not create a second Item
or Worker owner. If contradictory classes for one exact Worker lease do arrive,
the first applicable exact CAS in that fixed order wins; this is unsupported
protocol-error behavior, not a result winner policy. Each lane may consume up
to `perOutcomeBatchLimit`, so a complete round handles at most three times that
limit.

Each lane decodes once and normalizes its bounded input into two private
indexes:

```text
taskId -> ordered TaskResultEvidence(
  taskId,
  messageId,
  opaqueResultPayload
)

workerGroupId -> ordered WorkerResultEvidence(
  workerId,
  workerLeaseScore
)
```

`SeedResult` and `ResultContext` do not leave this decode cutpoint. Only the
SUCCESS lane creates Task evidence; every valid outcome creates Worker
evidence. After consuming one outcome queue, routing enters two owner-local
paths: `_handle_task_results` receives only `resultsByTask`, while
`_handle_worker_results` receives only `resultsByWorkerGroup`. Each path
selects its own outcome policy and invokes leaf handlers with one `taskId`
batch or one `workerGroupId` batch. No leaf handler receives the mixed decoded
batch or a complete owner index. Normalization does not apply duplicate
precedence. The Task success handler applies last-result semantics by
`messageId` inside one Task. Worker disposition selects the last score observed
for one Worker in that outcome batch only when preparing the exact score-owner
call. Each WorkerGroup therefore produces at most one batch call to
WorkerScoreCore.

`TaskResultEvidence`, `WorkerResultEvidence`, `TaskResultHandler`, and
`WorkerResultHandler` are stable scheduling-policy contracts. Task handlers
receive one `taskId`, its immutable result tuple, and `resultTimeMillis`.
Worker handlers receive one `workerGroupId`, its immutable evidence tuple, and
the same action-neutral `resultTimeMillis`; the time parameter does not imply
that the replacement policy must release the Worker, and it is not a safe
Worker release coordinate after arbitrary handler work. The built-in release
policy reads a fresh clock value immediately before calling
`release_score_holds`; Task success promotion continues to use the stable
round time. `ResultRoutingPacer`
accepts owner-local outcome-to-handler mappings, copies them during
construction, and requires Task SUCCESS coverage plus Worker coverage for all
three outcome classes. It depends only on `SeedResultRuntime` plus those two
handler mappings; TaskItem score, Worker score, and Task runtime dependencies
belong to policy construction. `ResultRoutingBuiltinPolicies` is the single
container for built-in result-routing policies. It owns the default policy
dependencies and exposes each policy as a named callable method.
`default_task_result_handlers()` and `default_worker_result_handlers()` only
compose those methods into the standard mappings: SUCCESS stores/promotes Task
results, SUCCESS and WORKER_FAILURE release Worker score holds, and
ADAPTER_REJECTION demotes exact score holds to recovery. Assembly chooses
these defaults explicitly; callers may replace a whole mapping or compose a
custom mapping from individual built-in methods and custom handlers.

### Success

```text
consume SUCCESS
-> decode valid ResultContext values
-> group by taskId
-> collapse duplicate messageId to the last payload in queue order
-> HSET Task success result HASH
-> promote the same messageIds to FINAL_SUCCESS
-> exact-release each correlated Worker lease
```

Result storage precedes Item promotion. This guarantees `FINAL_SUCCESS` has a
stored successful payload. A crash may temporarily leave a result payload while
the Item is still ACTIVE or FINAL_FAILED; claim expiry and a later success can
converge it. Late success may overwrite both an earlier payload and
`FINAL_FAILED`.

### Worker Failure

```text
consume WORKER_FAILURE
-> decode valid ResultContext values
-> exact-release each correlated Worker lease
-> do not mutate Item score
```

The Item remains at its future claim coordinate. When that coordinate becomes
due, normal TaskItem acquisition retries it without a result-owned retry write.

### Adapter Rejection

```text
consume ADAPTER_REJECTION
-> decode valid ResultContext values
-> exact-demote each correlated Worker lease to RECOVERY_RECHECK
-> do not mutate Item score
```

The Item again becomes retryable through its existing claim coordinate.

## Worker Fence Semantics

`ResultContext.workerGroupId` supplies the Worker score home bucket. It is
carried with the opaque lease fence at dispatch time, so late result handling
does not depend on TaskDescriptor retention or a metadata lookup.

Within one outcome batch, repeated results for the same Worker collapse to the
last opaque `workerLeaseScore` in queue order. Queue arrival order does not
guarantee lease-coordinate recency, so this is only a bounded best-effort
selection. If the selected value is an older fence, exact CAS returns `STALE`;
the consequence is only that release or demotion is not immediate, and normal
lease expiry restores liveness. Older scores are not retried one by one because
one Worker has only one current score. There is no cross-class winner map.

## Failure Semantics

```text
malformed context or result in the wrong class queue
  -> consume and discard; no owner mutation

Worker STALE / NOOP
  -> does not roll back Item or result truth

Worker Delivery Dispatch or Worker produces no SeedResult
  -> UNKNOWN; Item claim and Worker lease expiry recover

process crash after queue pop
  -> ingress evidence may be lost; Item claim and Worker lease expiry recover

duplicate evidence from Adapter/Worker transport
  -> may be consumed again; owner-local monotonic transition or exact fence
     determines whether it changes truth
```

The class queues are deliberately best-effort. Reliable pending/ack or failure
history requires a separately justified owner and invariant.

## Application And Deferred Policy

`ResultRoutingApplication` owns one independently paced loop. Lifecycle order
is defined by [Kernel Application Assembly](../kernel-application-assembly.md).
The built-in interval is `100ms` and the per-class batch limit is `100`; only
the interval is public JSON.

Deferred policy is limited to cadence, per-class limit tuning, failure/history
projection, stronger ingress reliability, and a possible trusted
pre-execution-rejection fast retry. The current Java query reads only requested
Task-scoped last-success payloads. That fast path is not present: it requires
ResultContext to carry an opaque Item claim fence and TaskItemScoreBandCore to
provide an exact release primitive. Missing response, Adapter crash, timeout,
or any other `UNKNOWN` evidence must continue waiting for claim expiry.

## Guardrails

- Do not let Adapter or Worker mutate score directly.
- Do not interpret `SeedResult.commandId` as result truth or a fence.
- Do not carry Worker command `messageType` or `executeBeforeMillis` into
  result routing.
- Do not add a generic result envelope ahead of SeedResult outcome
  partitioning.
- Do not parse exact `1xxx` or `3xxx` subcodes in result routing.
- Do not partition queues by exact code, Task, WorkerGroup, or producer source.
- The current built-in `1xxx/3xxx` handlers do not rewrite Item retry time.
- Do not introduce a `3xxx` fast retry without an opaque Item claim fence and
  an exact TaskItem score-owner release operation.
- Never infer fast retry from `UNKNOWN` delivery evidence.
- Do not promote Item before its successful payload is stored.
- Do not reintroduce cross-class outcome precedence or winner aggregation.
