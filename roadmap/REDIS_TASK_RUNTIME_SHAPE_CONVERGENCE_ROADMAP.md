# Redis Task Runtime Shape Convergence Roadmap

Status: proposed direction document.

This roadmap converges the Redis physical layout behind task runtime truth:
`TaskWorkRuntime` and `TaskResultRuntime`. It follows the same lesson as the
worker-runtime cleanup: first keep upper runtime and engine callers independent
of physical Redis shape, then replace the Redis-internal layout. The first
concrete targets are the current per-message key families:

```text
xa:mass:runtime:v1:task:{taskId}:work:{messageId}
xa:mass:runtime:v1:result:task:{taskId}:visible:{messageId}
```

Read with:

- [INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [platform_infra/README.md](../platform_infra/README.md)
- [mass-runtime-redis/README.md](../platform_infra/mass-runtime-redis/README.md)
- [REDIS_RUNTIME_BASELINE.md](../platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md)
- [HIGH_VOLUME_MODEL_BASELINE.md](../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md)
- [REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_INVENTORY.md](REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_INVENTORY.md)

## Current Code Observations

- `TaskWorkRuntime` is already the semantic runtime contract consumed by
  engine: enqueue, ready task discovery, claim, result apply with context,
  expiry polling, active lease reads, stats, recent final receipts, and task
  discard.
- `TaskResultRuntime` is already the semantic runtime contract for stable-final
  public result rows, task-local result windows, staged callback repair anchors,
  and result-side attempt-closed/logical-final/progress barriers.
- Engine mainline does not need Redis key names to perform scheduling,
  assignment, result convergence, or lifecycle transitions.
- `RedisTaskWorkRuntime` currently owns physical Redis mutation through Lua
  scripts and direct Redis reads.
- `RedisTaskResultRuntime` currently owns result-stage, visible-row, pending
  barrier, and repair-state Redis mutation through Lua scripts and direct Redis
  reads.
- `RedisTaskWorkKeyspace` currently exposes per-message work and lease key
  builders: `taskWorkHash(taskId, messageId)` and
  `taskLeaseHash(taskId, messageId)`.
- `RedisTaskResultKeyspace` currently exposes per-message visible result row
  keys through `taskVisibleRow(taskId, messageId)`, while
  `taskVisibleZset(taskId)` stores the task-local result order.
- `RedisTaskWorkRuntimeTest` and `RedisTaskWorkKeyspaceTest` intentionally
  assert current Redis key shape and must move with Redis implementation
  changes.
- `RedisTaskResultRuntimeTest` and `RedisTaskResultRuntimeContractTest`
  intentionally prove result runtime behavior and must be extended before
  changing result visible-row shape.
- Live Redis evidence from the user-provided key shows one ready work hash with
  8 fields and `MEMORY USAGE=772`; the sampled task had 50 ready work hashes
  plus 3 task-level keys, so key count grows linearly with ready work count.
- Live Redis evidence for `xa:mass:runtime:v1:result:task:*` shows the same
  pattern for stable-final result rows: the sampled tasks each had
  `visible_zcard=50` and `visible_row_keys=50`; one visible row string used
  `MEMORY USAGE=1960`.
- Current work hashes persist empty optional fields such as `shardKey`,
  `payloadRef`, and `nextVisibleAtMillis`.
- Current result rows are encoded with `GsonBuilder.serializeNulls()` and carry
  worker/attempt/context fields. That is a fat runtime row. Narrowing it into
  a transparent public result shape requires a separate runtime-api/server/SDK
  contract slice; this Redis shape roadmap should not silently change that
  contract while removing per-message keys.
- `eventCode` currently appears in server append APIs, runtime work values,
  engine dispatch binding, transport/SDK worker invocation, and result
  snapshots. That is historical API design residue. The target owner is worker
  handler dispatch identity, but removing it from server API / engine /
  transport surfaces is deferred to a later boundary roadmap.
- `shardKey` is stored and loaded by runtime values, but current production
  engine creation passes `null` and no Redis claim/apply path uses it.
- Historical lock-reduction notes already identified task-local work/lease
  hashes and task-local final result hashes as future migrations, but that
  archived roadmap explicitly deferred key-shape migration.

## Owner Review

`TaskWorkRuntime` and `TaskResultRuntime` belong to
`platform_infra/mass-runtime-api`. They define the semantic contracts that
engine and other runtime callers may consume.

`RedisTaskWorkRuntime` and `RedisTaskResultRuntime` belong to
`platform_infra/mass-runtime-redis`. They own Redis keys, Redis value encoding,
Lua scripts, and Redis-specific proof.

Engine may consume `TaskWorkRuntime` values such as `TaskWorkEnvelope`,
`ClaimedTaskWork`, `ActiveLeaseRecord`, `TaskWorkResult`,
`RuntimeResultApplyContext`, `RecentFinalWorkReceipt`, and `TaskWorkStats`.
Engine may also consume `TaskResultRuntime` values such as
`TaskResultCallbackDraft`, `TaskResultFinalDraft`, `TaskResultRuntimeRow`,
`TaskResultWindow`, and result repair/barrier outcomes. Engine must not
consume Redis key families, Redis field names, encoded payload formats,
task-local hash layout, or migration details.

Storage, server, SDK, transport, trace, and worker-runtime must not become
owners of task runtime Redis physical shape. They may observe behavior through
the shared runtime contracts, public APIs, trace, or diagnostics.

`eventCode` belongs to worker handler selection/dispatch identity in the long
term. Current server API, engine, transport, and SDK exposure is historical
surface residue. This roadmap records that residue but does not remove those
surfaces.

## Boundary Decision

Separate the work into two explicit layers:

```text
semantic runtime surface
  TaskWorkRuntime, TaskResultRuntime, and runtime-api values
  engine-facing behavior and shared memory/Redis contract tests

Redis physical shape
  RedisTaskWorkRuntime, RedisTaskWorkKeyspace
  RedisTaskResultRuntime, RedisTaskResultKeyspace
  Redis value codecs, Lua scripts
  no engine/server/sdk/transport dependency on key or value layout
```

Do not add a new engine bridge just to hide Redis. The existing
runtime-api contracts are the hiding boundary. The first slice should make that
boundary executable through guards and caller inventory, then later slices can
safely change Redis internals.

Keep Redis physical-shape convergence separate from public/server/SDK contract
convergence. If a slice changes `TaskResultRuntimeRow`, public result
snapshots, server append contracts, or worker invocation event identity, it is
no longer just a Redis key-shape slice and needs its own owner decision.

## Target Shape

Preferred staged Redis physical direction for work runtime:

```text
...:task:{taskId}:work
  HASH messageId -> encoded work dispatch envelope

...:task:{taskId}:leases
  HASH messageId -> encoded active lease payload

...:task:{taskId}:recent-final
  HASH messageId -> encoded bounded recent-final receipt
```

Task work is not shaped like worker lifecycle state. Worker bucket fan-out is
useful for long-lived worker registry/candidate dimensions; task work is
normally consumed quickly and can be bounded by a task-level maximum length.
Use a single task-local hash first, backed by an explicit max length across
ready, delayed, and inflight runtime-owned work.

Keep these discovery and correctness indexes unless a slice proves a safe
replacement:

- `...:ready:tasks`
- `...:delayed:work`
- `...:lease:expiry`
- `...:task:{taskId}:ready`
- `...:task:{taskId}:delayed` until task-local delayed lookup is removed
- `...:task:{taskId}:stats`
- `...:worker:{workerId}:active` until worker occupancy/resource-release proof
  replaces it

Preferred staged Redis physical direction for result runtime:

```text
...:result:task:{taskId}:visible
  ZSET messageId -> seq

...:result:task:{taskId}:final
  HASH messageId -> encoded stable-final result value

...:result:task:{taskId}:seq
  STRING task-local final sequence counter
```

This removes one Redis key per stable-final result row while keeping the
task-local ordered result window. The `visible` ZSET remains the ordering
index; the `final` HASH owns the row payload. The first result-row slice should
preserve current `TaskResultRuntime` semantics while changing only Redis
physical shape. Stage and barrier repair keys are not folded into this first
result-row slice because they protect repair and idempotency behavior, not
result window payload storage.

Keep these result discovery and repair indexes unless a slice proves a safe
replacement:

- `...:result:stages`
- `...:result:task:{taskId}:stages`
- `...:result:task:{taskId}:message:{messageId}:stages`
- `...:result:pending:attempt-closed`
- `...:result:pending:logical-final`
- `...:result:pending:progress`
- barrier claim keys until fully converged barrier cleanup is proven against
  duplicate callback and repair tests

Value encoding rules:

- encode optional fields only when nonblank or non-default
- remove `shardKey` from Redis work values unless a current production caller
  is proven
- do not store `eventCode` as a separate Redis field and do not put it in the
  task shell; while current runtime/engine/transport contracts still carry it,
  keep it only inside the encoded work dispatch envelope or existing runtime
  values. Removing it from server API, engine dispatch binding, transport
  frames, and SDK worker invocation is a later roadmap.
- keep inline input support, but keep user input distinct from platform
  envelope metadata; allow `payloadRef` to be the preferred large-input
  carrier
- keep retry/max-retry and timestamps needed by retry, delayed visibility,
  result context, and ready score repair
- for result rows, prefer reconstructing `taskId` and `messageId` from the
  task-local key and hash field instead of repeating them in every value
- for result rows, omit null optional fields instead of using
  `serializeNulls()` where this can be done without changing current
  `TaskResultRuntimeRow` semantics
- transparent public result rows are the target direction, but not part of the
  Redis key-shape slice. A later runtime-api/server/SDK convergence should
  decide whether public final results keep only `seq`, status, completed time,
  and opaque output/error payloads.
- keep repair/barrier progress outside any future public final result value
  unless a later slice proves a compact internal marker can be kept without
  exposing or bloating the result row
- keep encoding package-private inside `mass-runtime-redis`
- enforce a task-level maximum runtime work length so the single task hash
  cannot grow without bound

## Do Not Start With

Do not start by changing engine assignment, result, query, or lifecycle callers to
know about Redis task-local hashes, hash fields, or encoded values.

Do not start by removing `eventCode` from server APIs, engine dispatch binding,
transport frames, or SDK worker invocation. That is real boundary work, but it
is not required to remove one-key-per-item Redis shapes.

Do not start by narrowing `TaskResultRuntimeRow` or public SDK/server result
snapshots while moving Redis visible rows into a task-local hash. Preserve the
current semantic contract first; narrow it in a later contract slice.

Do not start by deleting current Redis keys before the semantic contract,
keyspace tests, and Redis implementation tests have a replacement shape.

Do not fold result barrier/stage repair state into the visible-result row
migration before stable-final row cardinality is fixed and result repair tests
still pass.

Do not keep old and new Redis physical paths as two long-lived live tracks.
This repo has no compatibility obligation for superseded internal runtime
key layouts. If a clean Redis runtime recreation or namespace bump is needed,
make that explicit in the slice.

## Non-Goals

- No change to task lifecycle semantics, terminal policy, scheduling policy, or
  worker selection.
- No change to the public server/SDK task APIs.
- No removal of `eventCode` from server append APIs, engine dispatch binding,
  transport payloads, SDK worker invocation, or public result snapshots.
- No narrowing of `TaskResultRuntimeRow`, server result DTOs, or SDK result
  snapshots in the Redis physical-shape slices.
- No result-history retention policy in this roadmap. Task result rows are not
  intended as long-term history; a later terminal-task cleanup slice may retain
  terminal tasks for a bounded duration and then remove their runtime keys.
- No storage/JDBC task-message migration.
- No trace/audit materialization for runtime queue truth.
- No transport dispatch handoff redesign.
- No replacement of `TaskWorkRuntime` or `TaskResultRuntime` with an
  engine-owned facade.
- No Redis Streams/pubsub ownership for queue or lease truth.
- No in-place migration obligation for pre-release Redis runtime keys unless a
  separate operator requirement is added.

## RTR-0 Inventory And Field Classification

Goal: freeze current callers, key families, and value fields before changing
physical shape.

Scope:

- Maintain
  `REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_INVENTORY.md`.
- Classify every task-work and task-result Redis key family as semantic truth,
  discovery index, repair support, cleanup residue, diagnostic support, or
  migration target.
- Classify every work/lease/recent-final/result-row field as claim hot-read,
  apply hot-read, result-window read, recovery read, optional field, or
  residue.
- Record live Redis shape evidence for at least one representative task.
- Separate engine/runtime contract callers from Redis implementation tests.

Acceptance:

- Inventory names all current per-message key families:
  `work:{messageId}`, `lease:{messageId}`, `recent-final:{messageId}`, and
  `result:task:{taskId}:visible:{messageId}`.
- Inventory states which fields may be omitted or removed from encoded Redis
  values.
- Inventory names the engine-facing callers and shows they do not need Redis
  physical shape.
- No code behavior changes are required in this slice.

## RTR-1 Protect Engine And Runtime-API Boundary

Goal: make Redis physical shape unobservable to engine and other upper
runtime callers before changing it.

Scope:

- Add or extend an architecture guard that fails if upper runtime callers
  reference task-runtime Redis physical layout: `RedisTaskWorkKeyspace`,
  `RedisTaskResultKeyspace`, task-work/task-result key family strings, Redis
  field constants, or package-private value codecs.
- Do not ban legitimate assembly-only instantiation of
  `RedisTaskWorkRuntime` / `RedisTaskResultRuntime` by server or SDK bootstrap
  wiring. Bootstrap may choose implementations; controllers, services,
  engine, SDK public surfaces, transport dispatch/result code, and
  worker-runtime hot paths must not depend on task-runtime Redis key/value
  layout.
- Allow transport to depend on its own Redis delivery/queue infrastructure
  without treating `com.xa.mass.runtime.redis.queue.*` as task-runtime shape
  leakage.
- Keep legitimate Redis implementation and Redis tests allowlisted under
  `platform_infra/mass-runtime-redis`.
- Confirm engine calls only `TaskWorkRuntime` / narrow engine ports for task
  work runtime behavior.
- Confirm engine/result services call only `TaskResultRuntime` for stable-final
  result rows and result-side repair barriers.
- Confirm `TaskWorkRuntimeContractTest` and `TaskResultRuntimeContractTest`
  cover the engine-visible invariants required by later Redis shape changes.

Acceptance:

- Cross-module production code cannot reference Redis task runtime key or value
  layout outside explicit bootstrap assembly and `mass-runtime-redis`.
- No engine production change is needed for later Redis physical shape slices.
- Contract tests remain the primary semantic proof for memory and Redis.
- Redis implementation tests remain free to assert physical shape.

## RTR-2 Introduce Redis-Internal Record Codecs And Task-Length Plan

Goal: introduce package-private Redis value encoding and task-local hash keys
inside `mass-runtime-redis` without changing behavior yet.

Scope:

- Add package-private encoded value helpers for work, lease, and recent-final
  records, or equivalent internal methods if a class is unnecessary.
- Add package-private encoded value helpers for stable-final result values, or
  equivalent internal methods if a class is unnecessary.
- Add target task-local hash key builders in `RedisTaskWorkKeyspace` without
  retargeting scripts yet.
- Add target task-local result row hash builders in `RedisTaskResultKeyspace`
  without retargeting scripts yet.
- Make the task-level maximum length owner decision before RTR-3. The decision
  must choose one of these paths and document why:
  - shared runtime-api semantics, with `WorkEnqueueOptions` or a successor
    runtime value updated and memory/Redis contract tests kept consistent
  - Redis-only physical safety configuration, explicitly not treated as a
    shared runtime contract
  - resolved task scheduling policy input, if the current engine caller and
    policy owner are named in the same slice
- Define the exact count that the limit applies to before a new write:
  ready + delayed + inflight not-yet-final work, or a narrower count with an
  explicit reason. Do not reuse the existing `maxReadyItemsPerTask` name for a
  broader semantic without renaming or documenting the difference.
- Define defaults for omitted fields: empty payload/ref, retry count `0`,
  no next-visible timestamp, no shard key.
- Add focused unit tests for codec round-trip, omitted optional fields, and
  target task-local key names.

Acceptance:

- Existing Redis runtime behavior and physical key tests still pass.
- New codec tests prove compact value round-trip for current work, lease, and
  recent-final fields.
- New codec tests prove compact value round-trip for current stable-final
  result semantics, including `taskId`/`messageId` reconstruction from key and
  hash field. Null optional field omission may be added only where it does not
  change `TaskResultRuntimeRow` behavior.
- No external module can access the codec or task-local hash plan.
- `shardKey` has an explicit keep/remove decision before RTR-3.
- RTR-2 is not complete until the per-task length limit owner, scope, default,
  and test surface are decided. RTR-3 must not start while this is still open.
- If the cap is shared runtime-api semantics, both memory and Redis contract
  tests prove the same behavior. If it is Redis-only physical safety, tests
  must stay Redis-implementation-scoped and the roadmap must not describe it as
  an engine/runtime contract.

## RTR-3 Replace Per-Work Keys With A Task-Local Work Hash

Goal: remove the main key-cardinality problem for ready/delayed work payloads.

Scope:

- Replace `task:{taskId}:work:{messageId}` with one task-local work hash:
  `task:{taskId}:work`, with `messageId -> encoded work value`.
- Update enqueue, claim, delayed promotion, ready-head cleanup, result apply,
  retry, `getWork(...)`, and discard logic to read/write work records by
  `messageId`.
- Enforce the task-level maximum length before writing a new work record.
- Retarget the work-script key layout explicitly for enqueue, claim, delayed
  promotion, result apply, `applyResultWithContext(...)`, retry scheduling,
  ready-head cleanup, `getWork(...)`, and discard. The implementation slice
  should leave a short checklist in code review or tests showing each old
  `taskWorkHash(taskId, messageId)` path was moved.
- Keep lease and recent-final key families unchanged in this slice unless the
  change is trivial and already proven.
- Keep ready/delayed/global indexes semantically unchanged.
- Retarget `RedisTaskWorkKeyspaceTest` and `RedisTaskWorkRuntimeTest` from
  per-work keys to the task-local work hash.

Acceptance:

- No Redis key is created per ready work payload.
- A high-volume single-task Redis test proves task-local key count does not
  grow with ready work count.
- A task-level max-length test proves enqueue rejection or backpressure when
  not-yet-final task work reaches the configured limit.
- `TaskWorkRuntimeContractTest` passes for Redis.
- `RedisTaskWorkRuntimeTest` still proves concurrent enqueue idempotence,
  delayed promotion, exclusive claim, retry, discard, and namespace isolation.
- Redis implementation proof covers old work-key absence after enqueue, delayed
  promotion, claim, retry, result apply, `getWork(...)`, and task discard.
- Live or test memory evidence shows the 50-work sample class no longer creates
  50 work keys.

## RTR-4 Replace Per-Lease Keys With A Task-Local Lease Hash

Goal: converge active lease payload storage after the work payload shape is
stable.

Scope:

- Replace `task:{taskId}:lease:{messageId}` with one task-local lease hash:
  `task:{taskId}:leases`, with `messageId -> encoded lease value`.
- Update claim, apply result, `applyResultWithContext(...)`,
  `getActiveLease(...)`, `activeLeases(taskId)`, `pollExpiredLeases(...)`,
  and discard logic.
- Retarget lease-script and lease-read layout explicitly for claim,
  result-apply, `applyResultWithContext(...)`, stale lease rejection,
  lease-expiry polling, active-lease reads, and discard. The slice should prove
  each old `taskLeaseHash(taskId, messageId)` path has either moved or remains
  intentionally deferred.
- Decide whether `task:{taskId}:active` remains as a task-active listing index
  or is replaced by enumerating the task-local lease hash.
- Keep `worker:{workerId}:active` unless a separate worker occupancy/resource
  release proof removes it.

Acceptance:

- No Redis key is created per active lease payload.
- Result apply with context remains one atomic Redis mutation for accepted
  callbacks.
- Expired lease polling reports each lease once across competing Redis
  clients.
- Redis implementation proof covers old lease-key absence after claim,
  result-apply, lease expiry, active-lease reads, and task discard.
- Engine result convergence and resource-release tests still pass.

## RTR-5 Replace Per-Result Visible Row Keys With A Task-Local Result Hash

Goal: remove the key-cardinality problem for stable-final public result rows.

Scope:

- Replace `result:task:{taskId}:visible:{messageId}` string keys with one
  task-local result row hash: `result:task:{taskId}:final`, with
  `messageId -> encoded stable-final result value`.
- Keep `result:task:{taskId}:visible` as the task-local order ZSET unless a
  separate slice proves a better ordered-window index.
- Update `commitVisibleFinal(...)`, `readWindow(...)`,
  `getVisibleByMessageId(...)`, result repair candidate scanning, barrier
  claim/mark scripts, and `discardTask(...)` to use the task-local result row
  hash.
- Preserve current `TaskResultRuntime` semantic behavior in this slice. Do not
  narrow `TaskResultRuntimeRow`, public result DTOs, SDK snapshots, or result
  archive output while changing Redis physical shape.
- Keep a field-disposition note for the later transparent-result contract
  convergence, but do not require that contract move here.
- Keep staged callback keys, pending barrier ZSETs, and barrier claim keys
  unchanged in this slice unless tests prove the compaction is independent.
- Add or retarget `RedisTaskResultKeyspaceTest` so result key names have
  explicit coverage instead of being protected only through runtime behavior
  tests.
- Retarget `RedisTaskResultRuntimeTest` and `RedisTaskResultKeyspace` coverage
  from per-message visible row keys to the task-local result row hash.
- Retarget result Lua scripts and direct Redis reads explicitly for visible
  final commit, duplicate final commit, ordered window reads, visible-by-id,
  repair candidate scanning, barrier claim/mark, and task discard.

Acceptance:

- No Redis key is created per stable-final visible result row.
- A high-volume single-task Redis test proves result task-local key count does
  not grow with visible result count.
- `TaskResultRuntimeContractTest` passes for Redis without forcing public
  result-row transparency in this slice.
- `RedisTaskResultRuntimeTest` still proves duplicate final commit
  idempotence, ordered result windows, repair candidate scanning, barrier
  claim/mark behavior, and task discard.
- `RedisTaskResultKeyspaceTest` proves the retained result keys and the new
  `result:task:{taskId}:final` hash key name.
- Redis implementation proof covers old visible-row-key absence after final
  commit, duplicate commit, barrier claim/mark, read window, visible-by-id, and
  task discard.
- Live or test memory evidence shows the 50-result sample class no longer
  creates 50 `result:task:{taskId}:visible:{messageId}` keys.

## RTR-6 Compact Or Retire Recent-Final Per-Message Keys

Goal: reduce duplicate/late callback receipt key cardinality without weakening
runtime-first callback classification.

Scope:

- Either move `recent-final:{messageId}` into one task-local receipt hash, or
  replace it with `TaskResultRuntime` stable-final evidence only if
  duplicate/late callback proof remains runtime-first.
- Preserve bounded trim semantics or replace them with explicit TTL/cleanup
  semantics.
- Keep this separate from RTR-3/RTR-4/RTR-5 because duplicate/late callback
  behavior is result-convergence sensitive.

Acceptance:

- Duplicate callback replay, late stale replay, and no-active-lease paths still
  classify correctly without falling back to review/projection rows.
- Recent-final storage no longer creates one Redis key per receipt, or the
  roadmap records why a bounded per-message receipt key remains justified.
- Result runtime and engine convergence tests pass.

## Deferred Follow-Ups

These are related, but intentionally not part of the Redis key-shape execution
slices above:

- **Task item event identity convergence**: remove historical `eventCode`
  exposure from server append APIs, engine dispatch binding, transport frames,
  public result snapshots, and SDK worker invocation after a new handler
  identity boundary is designed. Until then, Redis shape slices must preserve
  existing behavior and only avoid making `eventCode` a separate Redis field.
- **Transparent result contract convergence**: narrow `TaskResultRuntimeRow`
  and public server/SDK result DTOs so final results are opaque worker outputs
  plus minimal status/error/timing facts. This has a different caller set and
  proof set from the Redis physical shape migration.
- **Terminal task runtime cleanup**: after a task reaches terminal state,
  retain task runtime/result keys for a bounded duration, then remove them.
  Result rows are not long-term history, so the result hash does not need a
  separate length cap inside this roadmap.

## RTR-7 Baseline, Guards, And Residue Cleanup

Goal: make the implemented Redis shape the documented current truth and block
regression.

Scope:

- Update `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` from
  per-message work/lease/result key wording to the implemented task-local hash
  shapes.
- Update `platform_infra/mass-runtime-redis/README.md` only if role or guardrail
  wording changes.
- Remove stale tests that preserve old key names as a hidden second API.
- Add focused guard coverage against reintroducing one key per work or visible
  result item where a task-local hash record is expected.
- Run a residue scan for old key builders, old field constants, and stale docs.

Acceptance:

- `rg "taskWorkHash\\(|taskLeaseHash\\(|taskVisibleRow\\(|:work:\\{messageId\\}|:lease:\\{messageId\\}|:visible:\\{messageId\\}"` has no stale production hits except intentionally retained migration notes or tests for old-shape absence.
- Baseline docs match current Redis implementation.
- Architecture guards prevent upper modules from depending on Redis physical
  task runtime shape.
- Roadmap completion criteria below are satisfied before archive.

## Suggested Implementation Order

1. Complete RTR-0 inventory and field decisions.
2. Implement RTR-1 guards before touching Redis shape.
3. Add RTR-2 internal codecs and task-length plan.
4. Implement RTR-3 task-local work hash migration.
5. Implement RTR-4 task-local lease hash migration.
6. Implement RTR-5 task-local result row hash migration.
7. Implement RTR-6 recent-final migration or justified retention.
8. Update baseline docs and residue guards in RTR-7.
9. Handle deferred event identity, transparent result, and terminal cleanup
   roadmaps separately.

## Verification Candidates

Correct exact commands during implementation if module names or suite names
change.

```bash
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test -Dtest=TaskWorkRuntimeContractTest,TaskResultRuntimeContractTest,RedisTaskWorkRuntimeContractTest,RedisTaskWorkRuntimeTest,RedisTaskWorkKeyspaceTest,RedisTaskResultRuntimeContractTest,RedisTaskResultRuntimeTest,RedisTaskResultKeyspaceTest
```

```bash
mvn -pl xa-mass-engine -am test -Dtest=TaskKernelLifecycleTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest,TaskRedispatchCompetitionTest,SimpleTaskDispatchBinderTest,EngineSchedulingCoreArchitectureGuardTest
```

Redis shape spot checks:

```bash
redis-cli --raw --scan --pattern 'xa:mass:runtime:v1:task:<taskId>:work:*' | wc -l
redis-cli --raw MEMORY USAGE 'xa:mass:runtime:v1:task:<taskId>:work'
redis-cli --raw HLEN 'xa:mass:runtime:v1:task:<taskId>:work'
redis-cli --raw --scan --pattern 'xa:mass:runtime:v1:result:task:<taskId>:visible:*' | wc -l
redis-cli --raw ZCARD 'xa:mass:runtime:v1:result:task:<taskId>:visible'
redis-cli --raw HLEN 'xa:mass:runtime:v1:result:task:<taskId>:final'
```

Residue checks:

```bash
rg -n "taskWorkHash\\(|taskLeaseHash\\(|taskRecentFinalReceiptHash\\(|taskVisibleRow\\(|:work:\\{messageId\\}|:lease:\\{messageId\\}|:visible:\\{messageId\\}|FIELD_SHARD_KEY|shardKey" platform_infra/mass-runtime-redis platform_infra/mass-runtime-api xa-mass-engine xa-mass-server sdk transport xa-mass-worker-runtime doc roadmap
```

## Roadmap Completion Criteria

- Engine, server controllers/services, SDK public surfaces, transport
  dispatch/result code, and worker-runtime mainline do not reference Redis task
  runtime key/value layout. Explicit bootstrap assembly may instantiate Redis
  runtime implementations.
- `TaskWorkRuntime` and `TaskResultRuntime` semantic contracts remain stable
  for engine-visible behavior.
- Ready/delayed work payloads no longer create one Redis key per work item.
- Active lease payloads no longer create one Redis key per active lease item,
  or a documented later-phase decision explains why the lease shape remains.
- Stable-final visible result rows no longer create one Redis key per result
  item.
- A task-level max runtime work length prevents a single task work hash from
  growing without bound.
- Empty optional fields are not persisted in Redis encoded values.
- `shardKey` is removed from Redis work values or has a current production
  caller and proof.
- Result apply, duplicate callback idempotence, stale lease rejection,
  retry scheduling, lease expiry, result window ordering, result repair, and
  precise task discard proofs still pass.
- Public/server/SDK result behavior and current `eventCode` API behavior remain
  unchanged unless a separate approved follow-up roadmap changes them.
- `REDIS_RUNTIME_BASELINE.md` matches the implemented key/value shape.
- Residue scan is run before marking this roadmap complete or archiving.

## Open Decisions

- Whether RTR-3/RTR-5 should use a clean namespace bump, explicit namespace cleanup,
  or same-namespace replacement in local/dev runtime.
- Exact compact encoding format: compact JSON, delimiter encoding, or another
  package-private codec.
- Whether a Redis-only task-level safety cap should later be promoted into
  shared runtime-api semantics or resolved task scheduling policy after real
  caller demand exists. The initial owner/scope/default must be decided in
  RTR-2 before RTR-3 starts.
- Whether `createdAtMillis` stays inside the work value or moves into a ready
  queue member/side index.
- Whether `task:{taskId}:delayed` is still required after global delayed work
  promotion is stable.
- Whether `task:{taskId}:active` can be replaced by task-local lease hash
  enumeration.
- Whether result row encoding should keep `seq` in the hash value or
  reconstruct it from the visible ZSET score.
- Whether result barrier keys should later be folded into the result row hash
  or a task-local barrier hash after the visible-row migration is stable.
- Whether `payloadJson` should be renamed to `inputJson` or
  `dispatchEnvelopeJson` so platform envelope metadata is not confused with
  user business payload.
- When to start the follow-up event identity convergence that removes
  `eventCode` from server API, engine, transport, and SDK surfaces.
- When to start the follow-up transparent result contract convergence for
  `TaskResultRuntimeRow` and public result DTOs.
- What terminal retention duration and cleanup trigger should own eventual
  task runtime/result key deletion.
- Whether recent-final receipts can be folded into `TaskResultRuntime`
  stable-final rows without losing runtime-first duplicate/late callback
  classification.
