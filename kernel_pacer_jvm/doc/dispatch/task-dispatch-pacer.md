# Task Dispatch Policy

Status: active Kernel Task dispatch contract.

## Purpose

`TaskDispatchPolicy` handles a bounded Main-selected NORMAL Task set:

```text
observe due Item scores
  -> load minimal TaskItems
  -> settle expired or exhausted Items
  -> obtain candidates through named Matching take
  -> acquire Worker execution, claim Item, and publish Command
  -> pace, close, or park the Task
```

Kernel owns scheduling and finality. Matching returns bounded candidates through
fixed query functions: Pool entries carry opaque Pacer-issued candidate fences,
while direct functions return identity hints. Neither is an execution lease.

## Common Item Flow

For each Task, the policy:

1. observes at most `assignment-batch-limit` raw ACTIVE Item candidates (default 100, 1..1000);
2. loads the corresponding minimal TaskItems;
3. stores the fixed failed Result before promoting exhausted or expired Items
   to `TERMINAL(tag=5)` for the complete observed batch, including 1000 failures;
4. identifies claimable Items in observation order;
5. obtains Worker candidates using the descriptor Group and Item queries;
6. delegates exact Worker confirmation, Item claim, and Command publication;
7. rewrites ordinary Task pacing in a `finally` boundary.

If no claimable Item remains, `TaskIdleSettlement` performs the complete ACTIVE
recheck and exact close or private idle park.

## Named Query Functions

Main supplies complete immutable NORMAL Task descriptors to refill and
dispatch. Dispatch passes the explicit Group and messageId-to-WorkerQuery Map
through `WorkerMatching`. Matching owns normalization, equivalent-query counts and
candidate correlation; Pacer keeps each returned candidate with its message ID.
Pool functions consume candidate stock; direct Identity/Phone functions return
identity hints without touching a Pool. Only the separate refill Producer reads
ordinary due HOT heads and exact-candidateizes them before qualification.
Candidateization retains generation and does not acquire an execution lease.
Pool admission starts its own TTL. A take miss leaves the Item due without
immediate supply or substitute scans. See [Candidate Selection](assignment-dispatch-scheduling.md#candidate-selection)
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

The hint holds no Worker, Score or query configuration and adds no Redis command.
Leaving the observed batch discards that Task's history; process restart clears all
history. It prevents fixed-order monopolization among continuously observed
competing Tasks, without promising equal throughput, weighted priority, Item
fairness, a completion deadline or progress without usable candidate evidence.
INITIAL priority and the Score Owner's bounded Task discovery remain unchanged.

One dispatch round keeps a Worker-ID set shared across Tasks. A Worker can back
at most one Item assignment in that round. Candidate order belongs to the named
Matching function. Acquisition contention or a missing descriptor yields partial
progress. The policy neither refills nor restores consumed stock within the
attempt. An execution lease acquired before a failed claim or publication
recovers through expiry.

## Assignment And Result Boundary

`TaskAssignmentDispatcher` alone constructs a Delivery Command after:

```text
strict observed or current-identity Worker execution acquisition
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
- Properties changes request best-effort sealing invalidation after facts commit;
  final confirmation rejects invalidated fences; unused holds expire naturally.

## Guardrails

- Keep one selector expression in `TaskItem`; do not add derived ID/query state,
  property-specific branches or Rule maps.
- Do not let Matching lease, rank, claim, or publish Commands.
- Do not infer Item failure from absent candidates.
- Do not add a per-Task cache, async matching job or compensating lease release.
- Do not treat Result observation as TaskItem finality.
