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

Kernel owns scheduling and finality; Matching supplies bounded identities through
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

At round entry, Dispatch resolves all Task IDs/Groups in one bounded Matching
read. Each Task reuses its prepared query for grouped take and post-hold retain.
Default identity selectors take the bounded Kernel HOT path; named Rules apply
index membership to ANY and explicit IDs too. Property operators and index
coordinates remain private to Matching. See [Candidate Selection](assignment-dispatch-scheduling.md#candidate-selection)
for bounds, ordering and client-command budgets.

Binding failure prevents assignment without suppressing Item failure handling
or idle settlement. Unavailable index evidence never becomes ANY. A query has no
lifecycle, cache, queue, persistent cursor or Worker lease authority.

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
