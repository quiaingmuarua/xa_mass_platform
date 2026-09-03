# XA Mass Worker Matching JVM

Status: current Worker facts and PRECOMPUTED Candidate Rule owner.

`worker_matching_jvm` is a Java 21 internal runtime module. It owns Worker and
Platform facts, persistent Candidate Rules, and interpretation of those rules.
It does not observe or change Worker score, choose scheduling priority, parse
ON_DEMAND Worker Selectors, claim a TaskItem, or publish a Delivery Command.

## Owner Boundary

```text
PRECOMPUTED
Kernel Pacer sorts Task needs and exact-holds a bounded due Worker pool
  -> WorkerMatchingRuntime reads Candidate Rules and Worker facts
  -> evaluates held Workers in the supplied order
  -> appends accepted workerId + opaque held score to CandidateWorkerCache
  -> Kernel Dispatch consumes the Candidate bucket and performs exact renewal,
     Item claim, and Command publication

ON_DEMAND
Server passes the finite workerSelector to the Kernel parser
  -> Kernel normalizes workerId targets, or an empty ANY target
  -> Kernel stores only those identities with the TaskItem
  -> Kernel Dispatch observes and exact-holds eligible target Workers directly
```

| Owner | Owns |
| --- | --- |
| Worker Matching | Worker and Platform Properties, Candidate Rules, constraint interpretation, ordered filtering of a supplied held pool |
| Kernel | Task/Item/Worker score, finite Item Worker Selector normalization, Task ordering and deficit, Worker observation and exact hold, Candidate Cache truth, round uniqueness, Item claim, retry and finality |
| Server | public validation, ordered cross-owner writes, Runtime View composition and lifecycle assembly |
| Transport | complete Properties upload during Prepare and execution of an already-targeted Command |

`WorkerMatchRuntime` lives in `kernel_jvm` as the narrow PRECOMPUTED handoff.
One `TaskRuleMatchDemand` contains a WorkerGroup, an ordered list of opaque
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
```

Worker Prepare replaces the complete Worker Properties value and preserves
the independently written Platform Properties value. Platform patch requires
an existing Worker facts row. Candidate Rules are create-only: an exact retry
is unchanged and different content conflicts. Missing Kernel resources leave
inert orphan facts or rules.

PRECOMPUTED Candidate Rules use the finite constraint language. Roots are
`workerId`, `worker.*`, and `platform.*`; operators are `$eq`/`$equal`, `$ne`,
`$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$exists`, and `$range`. All conditions
are ANDed and `{}` is unrestricted.

ON_DEMAND does not store or interpret a Rule in this module. Its public
`workerSelector` is a closed Kernel instruction:

```json
[]
["workerId", "$eq", "worker-id"]
["workerId", "$in", ["worker-a", "worker-b"]]
```

`[]` normalizes to ANY. `$in` contains 1..100 unique opaque non-blank Worker
IDs. Kernel persists only the normalized IDs with the TaskItem; the original
selector is not stored and the resident Matching runtime is not involved.

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

The default Demand queue capacity is 10,000. At most one Demand per
WorkerGroup may be pending, while unrelated Groups remain independent. A held
Worker may enter at most one Candidate bucket in one Demand. A missing, wrong-Group,
or invalid Candidate Rule skips that address and leaves the Worker pool
available to later Candidate needs.

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
Properties changes do not revoke an existing entry. Cache expiry and Kernel's
final exact score renewal bound stale candidates before Item claim.

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
