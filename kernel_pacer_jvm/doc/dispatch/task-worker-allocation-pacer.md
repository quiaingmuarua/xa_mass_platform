# Task Worker Allocation Policy

Status: active Kernel PRECOMPUTED allocation contract.

## Purpose

`TaskWorkerAllocationPolicy` fills Candidate Cache deficits for a bounded,
Main-selected set of `PRECOMPUTED_TASK_RULE` Tasks. It neither discovers Tasks
nor interprets allocation Rules.

```text
Due PRECOMPUTED Tasks
  -> observe Candidate Cache counts
  -> order incomplete Tasks by priority then taskId inside each WorkerGroup
  -> calculate current deficits
  -> observe and exact-hold one bounded due HOT Worker pool
  -> publish one ordered Group Match Demand
  -> Worker Matching filters the held pool and appends Candidate Cache entries
```

## Input

Each `ObservedTask` contains a minimal `TaskDescriptor` and its opaque observed
Task score. The Task ID is derived from the descriptor rather than copied into
a second field. The descriptor has no Rule. It supplies the fixed WorkerGroup,
priority, and `maximumCandidateWorkers`. Receiving an ON_DEMAND Task is a
caller error.

## One Round

For each WorkerGroup, one allocation round:

1. Reads Candidate Cache counts for all supplied PRECOMPUTED Task IDs.
2. Omits Tasks whose cache already reaches `maximumCandidateWorkers`.
3. Sorts remaining Tasks by ascending priority and then taskId.
4. Limits the ordered Task list and total deficit to 100.
5. Observes at most that total deficit from the due HOT Worker pool.
6. Exact-holds the observed Workers until one common deadline.
7. Offers one `TaskRuleMatchDemand` containing only successful holds.

Matching processes the supplied Task order and may append accepted candidates
directly to each Candidate bucket. Allocation never waits for that work. A later
Pacer round reads actual Cache counts and computes the remaining deficit.

## Demand

```text
TaskRuleMatchDemand
  workerGroupId
  orderedTaskNeeds[]
    candidateId
    maximumCandidateWorkers
  heldWorkerLeaseScores
    workerId -> opaque exact held score
  holdUntilMillis
```

The Task list and held Worker map each contain at most 100 entries. The static
maximum lets Candidate Cache atomically reject entries when a concurrent or
earlier write has filled the Task. The current deficit is a Pacer-local input:
it controls how many Workers are observed and held but is not copied into the
Demand.

Only one Demand per WorkerGroup may be pending. A busy Group or full Demand
queue rejects the offer without blocking. The Pacer does not retry inside the
round and does not release the already-created holds. They expire naturally.
Unrelated Groups remain independent.

## Candidate Cache

Worker Matching calls the Kernel-owned atomic append operation:

```text
candidateId
maximumCandidateWorkers
CandidateWorkerEntry[] = workerId + heldWorkerLeaseScore
expiresAtMillis = holdUntilMillis
```

The Cache first removes expired entries and then accepts only the capacity
remaining under the Candidate maximum. It returns exactly the Worker IDs
written by that call. Matching removes only those accepted IDs from the current
Demand's available pool, so a Cache-rejected Worker may still match a later
Task in the same ordered Demand.

Candidate entries contain no WorkerGroup, Rule, Properties, or endpoint.
Dispatch obtains the Task's Group from its descriptor, loads the current Worker
delivery descriptor, and exact-renews the cached held score immediately before
Item claim.

## Ownership

Allocation owns Task ordering, deficit computation, bounded Worker observation,
exact initial hold, and Demand timing. Matching owns Rule and Properties
interpretation. Candidate Cache owns atomic Candidate-address capacity.
Dispatch owns final exact renewal, round uniqueness, Item claim, and Command
publication. No failure path compensates by releasing an unmatched or
unaccepted hold.
