# Task Dispatch Policy

Status: active Kernel Task dispatch contract.

## Purpose

`TaskDispatchPolicy` handles a bounded Main-selected NORMAL Task set:

```text
observe due Item scores
  -> load minimal TaskItems
  -> settle expired or exhausted Items
  -> obtain held Worker candidates through the prepared Rule query
  -> exact-confirm Worker, claim Item, and publish Command
  -> pace, close, or park the Task
```

Kernel owns scheduling and finality; Matching supplies bounded opaque held identities through
the query/recheck port.

## Common Item Flow

For each Task, the policy:

1. observes a bounded ACTIVE Item score set;
2. loads the corresponding minimal TaskItems;
3. stores the fixed failed Result before promoting exhausted or expired Items
   to `TERMINAL(tag=5)`;
4. identifies claimable Items in observation order;
5. obtains Worker candidates through its prepared query;
6. delegates exact Worker confirmation, Item claim, and Command publication;
7. rewrites ordinary Task pacing in a `finally` boundary.

If no claimable Item remains, `TaskIdleSettlement` performs the complete ACTIVE
recheck and exact close or private idle park.

## Prepared Rule Query

Main supplies the once-prepared NORMAL Task binding batch to both refill and
dispatch. Each Task's view references shared Eligibility stock. All selectors,
including ANY and explicit IDs, use local destructive take. Only the separate
refill Producer accesses source indexes and obtains initial holds. A take miss
leaves the Item due without immediate supply. See [Candidate Selection](assignment-dispatch-scheduling.md#candidate-selection)
for shared-target aggregation, bounded refill and exact assignment fences.

## Round Uniqueness

The single-flight Dispatch Policy retains only the IDs of recently served Tasks
from the current Main-selected batch (at most 100). Unserved Tasks keep their
observation order and precede served peers; served Tasks run least recently
served first. A Task moves to the back only after at least one Command is
published. Empty candidate rounds, failed confirmation/claim and unsuccessful
publication do not advance its turn. This avoids synchronizing a blind per-round
rotation with Worker release cadence. Progress in another Group cannot reset a
waiting Task's turn.

The hint holds no Worker, Score or binding data and adds no Redis command.
Leaving the observed batch discards that Task's history; process restart clears all
history. It prevents fixed-order monopolization among continuously observed
competing Tasks, without promising equal throughput, weighted priority, Item
fairness, a completion deadline or progress without usable candidate evidence.
INITIAL priority and the Score Owner's bounded Task discovery remain unchanged.

One dispatch round keeps a Worker-ID set shared across Tasks. A Worker can back
at most one Item assignment in that round. Explicit targets are considered in
Item and target order; ANY Items use the Score Owner's bounded due order.
Exact-hold contention or a missing descriptor yields partial progress. The
policy does not refill or release inside the same assignment attempt; unused
holds recover through expiry.

## Assignment And Result Boundary

`TaskAssignmentDispatcher` alone constructs a Delivery Command after:

```text
exact Worker hold confirmation against the carried score
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
- A changed Worker score prevents exact hold or confirmation and therefore dispatch.
- Matching query failure leaves work for later due rounds without a fallback.
- Properties changes request best-effort dirty invalidation after facts commit;
  final confirmation rejects invalidated fences; unused holds expire naturally.

## Guardrails

- Keep one selector expression in `TaskItem`; do not add derived ID/query state,
  property-specific branches or Rule maps.
- Do not let Matching lease, rank, claim, or publish Commands.
- Do not infer Item failure from absent candidates.
- Do not add a per-Task cache, async matching job or compensating lease release.
- Do not treat Result observation as TaskItem finality.
