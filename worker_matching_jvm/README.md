# XA Mass Worker Matching JVM

Status: current Worker facts, country index and PRECOMPUTED Candidate Rule owner.

`worker_matching_jvm` is a Java 21 internal runtime module. It owns Worker and
Platform facts, persistent Candidate Rules, interpretation of those rules, and the bounded country index.
It does not observe or change Worker score, choose scheduling priority, own
ANY/explicit-ID scheduling decisions, claim a TaskItem, or publish a Delivery Command.

## Owner Boundary

```text
PRECOMPUTED
Kernel Pacer sorts Task needs and exact-holds a bounded due Worker pool
  -> WorkerMatchingRuntime reads Candidate Rules and Worker facts
  -> evaluates held Workers in the supplied order
  -> appends accepted workerId + opaque held score to CandidateWorkerCache
  -> Kernel Dispatch consumes the Candidate bucket and performs exact confirmation,
     Item claim, and Command publication

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
| Worker Matching | Worker and Platform Properties, Candidate Rules, property selector/index binding, constraint interpretation, ordered filtering of a supplied held pool |
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
Candidate addresses and static candidate limits, the exact held scores for at
most 100 Workers, and the common hold deadline. It contains no Rule,
Properties, endpoint, Item, or Delivery DTO. Matching carries held scores
unchanged into Candidate Cache; it must not decode, compare, calculate, renew,
or release them.

`candidateId` is only the Candidate Rule and Cache address. Current Server and
Pacer use the Task ID value for that address, but Matching neither receives nor
interprets a Task identity and no second persistent ID is introduced.

## Persistent Catalog

`RedisWorkerMatchingCatalog` owns these keys under the configured
`xa_mass:<scope>` base:

```text
:matching:worker:facts:<workerGroupId>
:matching:worker:platform-properties:<workerGroupId>
:matching:candidate:rules
:matching:worker:index:country:<workerGroupId> (enabled Groups only)
```

Server-admitted Adapter observations create or replace the complete Worker
Properties value and preserve the independently written Platform Properties
value. Prepare does not write this Catalog. Platform patch requires an existing
Worker facts row, including a genuinely observed empty Map; an identity with no
facts does not satisfy that precondition. Candidate Rules are create-only: an
exact retry is unchanged and different content conflicts. Missing Kernel
resources leave inert orphan facts or rules.

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

PRECOMPUTED Candidate Rules use the finite constraint language. Roots are
`workerId`, `worker.*`, and `platform.*`; operators are `$eq`/`$equal`, `$ne`,
`$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$exists`, and `$range`. All conditions
are ANDed and `{}` is unrestricted.

ON_DEMAND does not store or interpret a Rule in this module. Its public
`workerSelector` is one immutable expression, captured by Kernel and passed unchanged:

```json
{}
{"workerId": ["worker-id"]}
{"workerId": ["worker-a", "worker-b"]}
{"worker.country": ["CN"]}
```

`{}` means ANY. Nonempty Maps have exactly one non-blank binding name and an
ordered List of 1..100 string parameters. Explicit Worker IDs must be unique and
non-blank. Country accepts exactly one value; an array is only a parameter
container, not a promise of multi-value matching or an operator slot.
Kernel persists the original expression with the TaskItem, not a
second ID list or query DTO, and never expands it at submission.

`validateWorkerSelector(groupId, selector)` owns binding/parameter and
index-enabled admission. The Catalog binds the full name `worker.country`
directly to its country implementation; there is no separate index identity or
property-to-index-name conversion. Unsupported bindings or parameter shapes are rejected,
not scanned or treated as ANY. The same selector reaches `takeWorkerIds` and
`retainWorkerIds`; the resident PRECOMPUTED runtime is not involved.

Adding another indexed property under the existing single-condition, bounded
identity/recheck contract changes Matching and its configuration/call guidance
only, not Kernel/Pacer production code. This does not introduce a dynamic
registry, composite DSL or new allocation mechanism.

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

`takeWorkerIds(group, selector, 1..100)` atomically range-reads the earliest members,
reads Redis TIME once, updates all selected scores through one multi-member
ZADD and returns the original order. `retainWorkerIds` reads at most 100 index
scores to recheck membership after Kernel initial hold, without loading facts.
Take never removes members and failed later leases do not undo the touch. This
is approximate rotation, not eligibility, a cooldown, a lock or strict fairness;
same-millisecond repeats are allowed.

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
  -> load at most 100 Candidate Rules
  -> load facts only for the at most 100 held Worker IDs
  -> process Candidate needs in Pacer order
  -> append matching candidates atomically up to each address's static maximum
  -> remove only Cache-accepted Workers from this demand's available pool
```

The current in-memory Demand Queue capacity is 10,000. Queue capacity is the
only admission condition; there is no separate pending-Group registry. A held
Worker may enter at most one Candidate bucket in one Demand. A missing,
wrong-Group, or invalid Candidate Rule skips that address and leaves the
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
