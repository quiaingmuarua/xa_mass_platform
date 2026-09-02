# Task Dispatch Policy

Status: active Kernel Task dispatch contract.

## Purpose

`TaskDispatchPolicy` handles a bounded Main-selected NORMAL Task set:

```text
observe due Item scores
  -> load minimal TaskItems
  -> settle expired or exhausted Items
  -> obtain leased Worker candidates
  -> exact claim and publish Commands
  -> pace, close or park the Task
```

Kernel owns scheduling and finality. Worker Matching supplies only short-lived
identity evidence for ON_DEMAND Items.

## Common Item Flow

For each Task, the policy:

1. observes a bounded ACTIVE Item score set;
2. loads the corresponding minimal TaskItems;
3. stores the fixed failed Result before promoting exhausted or expired Items
   to `FINAL_FAILED`;
4. identifies claimable Items;
5. obtains Worker candidates through the Task's fixed allocation mechanism;
6. delegates the exact Worker-renew, Item-claim and Command-publication closure;
7. rewrites ordinary Task pacing in a `finally` boundary.

If no claimable Item remains, `TaskIdleSettlement` performs the complete
ACTIVE recheck and exact close or private idle park.

## PRECOMPUTED

```text
Candidate Cache consume
  -> exact renew cached Worker lease score
  -> load current minimal Worker descriptor
  -> pair leased Workers with claimable Items
```

A cache miss is a bounded no-op. Dispatch does not publish Item Match Demand
and does not fall back to ON_DEMAND matching.

## ON_DEMAND

```text
claimable taskId + messageId keys
  -> take Item Match Evidence
  -> confirm exact active HOT holds
  -> Kernel priority and round-unique selection
  -> pair Worker with Item
  -> publish Item Match Demand for every unassigned Item
  -> exact-hold the admitted bounded Worker pool
```

An Item Evidence contains its key, fixed WorkerGroup, matched Worker IDs and
the hold deadline copied from its Demand. It has no Rule, Properties, Score or
endpoint. Expired, wrong-Group or no-longer-held evidence is treated as empty.

Kernel observes a bounded due HOT pool, offers the Item Demands and exact-holds
that pool when at least one Demand is admitted. Worker Matching reads the
create-only Item Rules and only the facts for those supplied Worker IDs. It
returns all matches in the pool; requested count and cross-Task uniqueness are
applied later by Kernel.

## Round Uniqueness

One dispatch round keeps a WorkerId set shared across Tasks. A Worker can back
at most one Item assignment in that round. Task priority is applied by Kernel
candidate selection; Matching does not know the priority.

An inactive hold or claim failure returns partial progress. The policy does
not refill inside the same Item assignment attempt. Unmatched and unselected
holds are not released; expiry restores unused capacity and their newer score
position allows other due Workers to enter later bounded pools.

## Assignment And Result Boundary

`TaskAssignmentDispatcher` alone constructs the Delivery Command after:

```text
exact Worker lease renewal
  -> exact Item claim
  -> mailbox append
```

Result routing later owns success/failure interpretation and finality
transitions. Dispatch does not inspect Adapter connection state or Worker
Properties.

## Failure Semantics

- Missing Item records are ignored for the current observation.
- Invalid stored Kernel records fail at their owner boundary.
- Demand capacity, missing Evidence and empty matches leave Items due.
- A changed Worker Score prevents exact hold confirmation and therefore
  dispatch.
- A Properties change does not revoke already emitted Evidence; Evidence TTL,
  single consumption and exact Score lease bound the stale window.
- Matching runtime failure prevents new evidence and is exposed through Server
  readiness; Kernel does not install a fallback matcher.

## Guardrails

- Do not add allocation rules to `TaskItem`.
- Do not let Matching lease, rank, claim or publish Commands.
- Do not infer Item failure from missing Evidence.
- Do not add an ON_DEMAND result cache or Candidate Cache fallback.
- Do not treat Result observation as TaskItem finality.
