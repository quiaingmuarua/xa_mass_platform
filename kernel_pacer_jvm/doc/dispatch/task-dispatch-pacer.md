# Task Dispatch Policy

Status: active Kernel Task dispatch contract.

## Purpose

`TaskDispatchPolicy` handles a bounded Main-selected NORMAL Task set:

```text
observe due Item scores
  -> load minimal TaskItems
  -> settle expired or exhausted Items
  -> obtain held Worker candidates by the Task's fixed mechanism
  -> exact-renew Worker, claim Item, and publish Command
  -> pace, close, or park the Task
```

Kernel owns scheduling and finality. Worker Matching does not participate in
the ON_DEMAND dispatch loop.

## Common Item Flow

For each Task, the policy:

1. observes a bounded ACTIVE Item score set;
2. loads the corresponding minimal TaskItems;
3. stores the fixed failed Result before promoting exhausted or expired Items
   to `FINAL_FAILED`;
4. identifies claimable Items in observation order;
5. obtains Worker candidates through the Task's fixed allocation mechanism;
6. delegates exact Worker renewal, Item claim, and Command publication;
7. rewrites ordinary Task pacing in a `finally` boundary.

If no claimable Item remains, `TaskIdleSettlement` performs the complete ACTIVE
recheck and exact close or private idle park.

## PRECOMPUTED

```text
consume Candidate Cache entries for candidateId
  -> load current minimal Worker descriptors for the Task WorkerGroup
  -> pair candidates with claimable Items in bounded order
  -> final exact renewal through TaskAssignmentDispatcher
```

Each Cache entry carries the exact opaque score produced by the earlier
allocation hold. Candidate consumption does not renew early. A missing
descriptor, expired entry, changed score, or Cache miss is a bounded no-op.
Dispatch never falls back to ON_DEMAND acquisition.

## ON_DEMAND

Each ON_DEMAND TaskItem stores only a normalized target list:

```text
[]                         -> ANY due HOT Worker in the Task WorkerGroup
[worker-a]                 -> explicit $eq target
[worker-a, worker-b, ...]  -> ordered explicit $in targets, at most 100
```

For claimable Items in order, Kernel:

```text
explicit targets -> observe due HOT scores only for those Worker IDs
ANY targets      -> observe a bounded due HOT WorkerGroup pool
                  -> exclude Workers already used in this dispatch round
                  -> exact-hold selected Workers
                  -> load current minimal delivery descriptors
                  -> final exact renewal, Item claim, and Command publication
```

Kernel validates the public finite Worker Selector and persists only its
normalized Worker IDs. The raw Selector is not persisted or projected. There
is no Item Rule, Item Match Demand, Evidence queue, matching cursor, or
Candidate Cache path for ON_DEMAND.

## Round Uniqueness

One dispatch round keeps a Worker-ID set shared across Tasks. A Worker can back
at most one Item assignment in that round. Explicit targets are considered in
Item and target order; ANY Items use the Score Owner's bounded due order.
Exact-hold contention or a missing descriptor yields partial progress. The
policy does not refill or release inside the same assignment attempt; unused
holds recover through expiry.

## Assignment And Result Boundary

`TaskAssignmentDispatcher` alone constructs a Delivery Command after:

```text
exact Worker lease renewal against the carried score
  -> exact Item claim
  -> mailbox append
```

Result routing later owns success/failure interpretation and finality
transitions. Dispatch does not inspect Adapter connection state or Worker
Properties.

## Failure Semantics

- Missing Item records are ignored for the current observation.
- Invalid stored Kernel records fail at their owner boundary.
- Empty or stale candidate observations leave Items due.
- A changed Worker score prevents exact hold or renewal and therefore dispatch.
- Matching runtime failure blocks new PRECOMPUTED Cache fills but does not
  create an ON_DEMAND fallback.
- Properties changes do not revoke existing PRECOMPUTED Cache entries; Cache
  expiry and exact score renewal bound their stale window.

## Guardrails

- Do not add allocation Rule maps or raw Selector arrays to `TaskItem`; only
  normalized Worker IDs are permitted for ON_DEMAND.
- Do not let Matching lease, rank, claim, or publish Commands.
- Do not infer Item failure from absent candidates.
- Do not add an ON_DEMAND Candidate Cache or Matching runtime round trip.
- Do not treat Result observation as TaskItem finality.
