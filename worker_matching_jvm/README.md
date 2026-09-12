# XA Mass Worker Matching JVM

Status: current Worker facts, named country Rule, shared DSL definitions and Task binding owner.

`worker_matching_jvm` is a Java 21 internal runtime module. It owns Worker and
Platform facts, shared Rules, Task-to-Rule bindings, interpretation of those rules, and the bounded country index.
It does not observe or change Worker score, choose scheduling priority, own
ANY/explicit-ID scheduling decisions, claim a TaskItem, or publish a Delivery Command.

## Owner Boundary

```text
PRECOMPUTED (explicit allocationRule DSL path)
Kernel Pacer sorts Task needs and exact-holds a bounded due Worker pool
  -> WorkerMatchingRuntime resolves Task bindings to shared Rules and reads Worker facts
  -> evaluates held Workers in the supplied order
  -> appends accepted workerId + opaque held score to CandidateWorkerCache
  -> Kernel Dispatch consumes the Candidate bucket and performs exact confirmation,
     Item claim, and Command publication

INDEXED_TASK
Worker facts -> the fixed worker.country Handler maintains the Group index
  -> Dispatch resolves its Task binding through WorkerCandidateIndex.prepareTaskQuery
  -> TaskQuery takes a bounded batch of Worker IDs
  -> Kernel observes HOT and acquires initial holds
  -> TaskQuery rechecks membership; Kernel exact-confirms and claims

ON_DEMAND
Server passes the finite workerSelector to the Kernel parser
  -> Kernel captures one immutable selector and handles ANY/explicit ID mechanics
  -> Server asks the Catalog to validate binding/parameters and enabled Group before Item writes
  -> Kernel Dispatch takes indexed identities through WorkerCandidateIndex when requested
  -> observes due HOT scores, exact-holds, and rechecks indexed membership after hold
  -> exact confirmation, Item claim and delivery remain Kernel operations
```

| Owner | Owns |
| --- | --- |
| Worker Matching | Worker and Platform Properties, shared Rules and Task bindings, property selector/index binding, constraint interpretation, ordered filtering of a supplied held pool |
| Kernel | Task/Item/Worker score, finite Item selector capture and ANY/ID mechanics, Task ordering and deficit, Worker observation and exact hold, Candidate Cache truth, round uniqueness, Item claim, retry and finality |
| Server | public validation, ordered cross-owner writes, Runtime View composition and lifecycle assembly |
| Transport | Identity preparation, best-effort Properties observations, and execution of an already-targeted Command |

`WorkerMatchQueue` lives in `kernel_jvm` as the complete PRECOMPUTED handoff
contract: Kernel Pacer offers Demands, the resident Matching runtime consumes
them, and health observation reads the same Queue size. The current Server
assembly selects `InMemoryWorkerMatchQueue`; neither producer nor consumer
depends on that implementation, so a future distributed Queue does not change
their policy contracts. One
`TaskRuleMatchDemand` contains a WorkerGroup, an ordered list of opaque
Task IDs and static candidate limits, the exact held scores for at
most 100 Workers, and the common hold deadline. It contains no Rule,
Properties, endpoint, Item, or Delivery DTO. Matching carries held scores
unchanged into Candidate Cache; it must not decode, compare, calculate, renew,
or release them.

`taskId` addresses the Kernel Candidate Cache and the Matching-owned binding.
Matching resolves that binding to an internal `ruleId`; Kernel/Pacer never receives
the Rule ID or reads a Rule. Sharing a definition does not share a Task's Cache,
capacity or Worker hold. Matching owns only the association, not Task metadata or
lifecycle, and does not read Kernel Task resources.

## Persistent Catalog

`RedisWorkerMatchingCatalog` owns these keys under the configured
`xa_mass:<scope>` base:

```text
:matching:worker:facts:<workerGroupId>
:matching:worker:platform-properties:<workerGroupId>
:matching:candidate:rules
:matching:task:rules
:matching:worker:index:country:<workerGroupId> (enabled Groups only)
```

Server-admitted Adapter observations create or replace the complete Worker
Properties value and preserve the independently written Platform Properties
value. Prepare does not write this Catalog. Platform patch requires an existing
Worker facts row, including a genuinely observed empty Map; an identity with no
facts does not satisfy that precondition. Missing Kernel resources leave inert
orphan facts or Task bindings; no background repair or Rule lifecycle is owned here.

### Shared Rule Binding

`bindTaskRule(taskId, workerGroupId, ruleId)` binds the fixed `worker.country`
Handler after validating that the Group index is enabled. It does not persist a
Handler definition. `bindTaskAllocationRule(taskId, workerGroupId, allocationRule)`
retains the separate DSL path and its shared content-addressed definitions.

The Task bindings HASH stores exactly `{"workerGroupId":"...","ruleId":"..."}`
under taskId. The definitions HASH retains DSL values with exactly workerGroupId
and allocationRule. DSL IDs remain rule- plus lowercase SHA-256 of canonical
UTF-8 definition bytes, including Group. Object key sorting preserves arrays,
numeric encodings and operator names; it does not merge logically equivalent Rules.

Each binding uses one Lua. Exact retries are UNCHANGED, missing stages are filled
with APPLIED, and different or damaged values conflict without being overwritten.
Invalid input is INVALID and infrastructure errors propagate. Kernel Task creation
commits separately: no rollback, binding cleanup or Rule lifecycle is installed.

`loadTaskRules` resolves at most 100 unique Tasks in one Lua, returning each DSL
definition once. Named Rules return ID and Group with null allocationRule.
Missing, corrupt, unknown or Group-inconsistent records return null. Legacy string
bindings are not read; this format uses the explicitly scoped stop-and-rebuild
cutover. No non-test scope is cleaned automatically.

### Named Rule Queries

CountryRuleHandler owns facts/index materialization and the matching query pair.
Task creation and closure neither build nor delete a Group index. Existing
configured startup rebuilding remains the reconstruction path.

`prepareTaskQuery(taskId, group)` uses one HMGET. Its dispatch-local query retains
the immutable binding for take and post-hold retain, exposes no Rule ID or Redis
key to Kernel, and owns no cache, thread, retry or close lifecycle. Missing,
corrupt, wrong-Group, unknown and disabled bindings return null, never ANY or DSL.

One take Lua handles at most 100 queries requesting at most 100 identities total.
One retain Lua checks at most 100 held identities. Empty batches make no Redis
call. The Matching budget is one binding HMGET, one take EVAL and zero or one
retain EVAL, independent of Item count; it is not a throughput claim.
Country queries merge bounded heads by low take time, avoiding fixed country
prefix preference. Explicit IDs use bounded membership reads and take-time
rotation. Empty queries sample indexed members with ZRANDMEMBER. Take touches
without removing members or reading Worker Score; retain checks membership
without loading facts. A named Rule constrains ANY and explicit IDs too: only
valid indexed country members qualify. Unbound ANY/IDs retain identity-only meaning.

INDEXED_TASK uses no Match Demand, DSL consumer or Candidate Cache. Index errors
reach the existing dispatch failure boundary. Rejected and unused holds expire
naturally; there is no compensation, replay or repair queue.

### Properties Writes

Live ingestion uses `upsertWorkerFactsBatch(groupId, propertiesByWorkerId)` for
1..100 unique Worker IDs in one Group. Each value is a complete flat string KV
Map (non-blank keys, non-null strings, empty strings allowed). The Catalog
canonically encodes complete values. Unindexed Groups compare with one `HMGET`
and issue one multi-field `HSET` for changed values; indexed Groups atomically
replace facts and maintain membership in one bounded Lua call. Unchanged content returns
UNCHANGED; invalid Properties return INVALID for that Worker. Empty Maps clear
all Worker Properties; omitted keys, including any previously stored registration
properties, are not retained. Independent Identity, Binding, Kernel resource
and Platform Properties records are unaffected.

The bounded batch is the sole Worker facts write operation, including for one
Worker and the first observation. Existing stored values remain readable without
migration. No observation version or late-snapshot rejection is introduced. Concurrent batches follow their
effective Redis writes, not observation time or HTTP arrival order. Each stored
JSON Map is replaced whole. Only indexed Groups serialize facts and index membership;
the unindexed compare followed by write does not promise cross-request serialization.

The upstream SYSTEM path is one-shot best-effort: a queue or HTTP failure can
leave no facts or old facts until new Host input or a later connection baseline arrives.
One Server delivery reception use case owns producer/Binding/Group admission
and complete batch writes, not Rule interpretation. Server never reads old
Worker facts to apply an Adapter delta. Independently managed Platform
Properties are a separate Map and are not part of this observation replacement.
Complete upstream observations avoid old-fact merging at Server, but cannot
repair input lost before reaching the Adapter cache. Every
new Demand loads the current Catalog facts; there is no refresh notification,
Matching facts cache or Candidate Cache cleanup. String comparison remains lexical;
numeric-string coercion and new constraint semantics are not part of this path.

A Worker without usable facts is skipped even for an unrestricted Rule or a
Worker-ID-only Rule. ON_DEMAND ANY and explicit IDs do not require facts;
indexed selection requires current index membership. The current Catalog returns null for both absent and
undecodable facts; Runtime Preview may display the identity with empty Maps,
but that display must not be reused as matching evidence.

PRECOMPUTED Rules use the finite constraint language. Roots are
`workerId`, `worker.*`, and `platform.*`; operators are `$eq`/`$equal`, `$ne`,
`$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$exists`, and `$range`. All conditions
are ANDed and `{}` is unrestricted.

Named Rule and indexed ON_DEMAND Items use explicit query objects:

```json
{}
{"workerId": ["worker-a", "worker-b"]}
{"worker.country": {"op": "eq", "values": ["CN"]}}
{"worker.country": {"op": "in", "values": ["CN", "US"]}}
```

There is at most one condition. eq takes one country; in takes 1..100 values,
deduplicated in first-appearance order. Matching rejects unknown fields,
operators, old country parameter lists and unsupported property bindings.
Kernel stores immutable bounded structure and owns only ANY/explicit-ID
mechanics. Queries are never expanded to IDs at submission. The same
interpretation governs take and post-hold retain.

## Country Index

Enable Groups with `xa.mass.worker-matching.country-index.worker-groups`; the
ordinary default is empty. Country is the literal Worker `country` property,
strict `[A-Z]{2}` without trimming, case conversion or ISO registry validation.
`code=(first-'A')*26+(second-'A')`: AA=0, CN=65, GB=157, US=538, ZZ=675.
All 676 inputs map uniquely; no configured numbering or app index exists.

The Group ZSET member is workerId. Its exact integer score is
`code * 2^43 + (lastTakenMillis - 946684800000)`. The epoch is 2000-01-01 UTC;
the 43-bit time range ends at 2278-09-26T15:10:22.207Z. All coordinates fit the
exact integer range of Redis doubles. Lua formats decimal integers explicitly;
time outside the range fails rather than wrapping into another country.

One facts-write Lua call handles at most 100 Workers. Country unchanged or
changed preserves the existing low time bits; first insertion uses zero.
Missing, empty or invalid country removes membership without rejecting other
valid string Properties. Platform Properties never affect this index.

Unbound ON_DEMAND takeWorkerIds/retainWorkerIds delegate to the same country
Handler. A take reads Redis TIME once inside Lua and formats exact integer scores
explicitly. Rotation is approximate: same-millisecond repeats are allowed and no
strict fairness, lease or scheduling eligibility is implied.

Before exposing the Catalog bean, Server assembly rebuilds configured derived
indexes by streaming existing facts with HSCAN and Lua batches of at most 100.
It may reset take times, modifies no facts/Binding/Score/Cache, and fails startup
on rebuild failure. There is no background rebuild, persistent cursor or new thread.
Facts/index replacement and subsequent Server dirty invalidation remain separate
commits: an already confirmed execution is not revoked.

## PRECOMPUTED Runtime

One resident virtual thread consumes whole Group demands:

```text
take one TaskRuleMatchDemand
  -> resolve at most 100 Task bindings to shared Rules in one batch
  -> normalize each distinct Rule once for this Demand
  -> load facts only for the at most 100 held Worker IDs
  -> process Candidate needs in Pacer order
  -> append matching candidates atomically up to each address's static maximum
  -> remove only Cache-accepted Workers from this demand's available pool
```

The current in-memory Demand Queue capacity is 10,000. Queue capacity is the
only admission condition; there is no separate pending-Group registry. A held
Worker may enter at most one Candidate bucket in one Demand. A missing,
wrong-Group, or invalid Rule skips that Task and leaves the
Worker pool available to later Candidate needs.

Candidate Cache remains a Kernel mechanical owner. Matching is only a bounded
writer through `appendCandidateWorkers`; it cannot read Cache state or consume
candidates. The atomic Cache operation removes expired entries, observes the
address's current candidate count, accepts only the remaining capacity, and
returns the Worker IDs it actually stored.

Queue rejection, an unaccepted candidate, a missing match, or a Matching
failure does not release a Worker hold. The score lease expires naturally.
Partial Cache writes completed before a later Candidate failure remain valid,
and a later Pacer round computes the remaining deficit from current Cache
counts.
Unexpected `Error` ends the runtime in `FAILED`; Server readiness reports the
failure and does not silently restart it.

## Candidate Semantics

A Candidate entry proves only that a Worker matched the facts read before the
supplied hold deadline and carries the exact opaque score produced by Kernel's
hold. It is not current availability or a completed scheduling decision.
After an APPLIED Worker or Platform facts write, Server requests best-effort
Score dirty invalidation. Cache entries and in-flight Match Demands retain the
original held score. Kernel's final exact confirmation rejects that fence once
invalidation succeeds, including an entry appended later by an old Demand.
Matching performs no Score reads and no Cache revocation. Facts-write and dirty
invalidation are separate commits: an intervening confirmation may succeed,
and an invalidation failure leaves existing deadlines as the fallback. An
UNCHANGED write does not request invalidation. Only a new initial hold restores
candidate eligibility before another PRECOMPUTED Matching round.

## Non-Owners

This module must not own:

```text
Worker Score interpretation or transitions
Candidate Cache state or consumption
Task ordering, deficit, priority, or round uniqueness
Kernel Task metadata or lifecycle
TaskItem claim, retry, or finality
DeliveryCommand / DeliveryReport
HTTP, Spring, Netty, or Worker lifecycle
```

Build and owner tests:

```text
./gradlew :worker_matching_jvm:test
./gradlew :server_jvm:redisOwnerIntegrationTest
./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```
