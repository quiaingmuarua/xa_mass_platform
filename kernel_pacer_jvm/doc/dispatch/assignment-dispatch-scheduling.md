# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract.

Detailed owners: [Initialization](task-initialization-policy.md),
[Task Dispatch](task-dispatch-pacer.md), [Matching](../../../worker_matching_jvm/README.md),
[Worker hold protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
and [Delivery](../../../doc/kernel/worker-delivery-dispatch.md).

## Authority

One Main Scheduler supplies complete bounded input to three fixed single-flight
Producers: Task initialization, Task dispatch and optional Worker serviceability.
Due Task score is the only scheduling demand source. Matching has no resident
consumer, Task job, per-Task candidate cache or queue handoff.

## Candidate Selection

Within the Dispatch Producer, `prepareTaskQueries(taskId -> group)` resolves all
at most 100 Tasks with one Matching HMGET. The returned dispatch-local query is
reused for take and membership recheck. Missing/corrupt bindings, wrong Groups,
unavailable Handlers and read failures never select an unrestricted fallback.
Item failure and idle settlement still run when candidate admission is unavailable.

Each Task supplies at most 100 due Items. Kernel captures selectors; Matching
interprets property names, operations and values. Default Rule identity selectors
use HOT selection; named Rules constrain ANY/IDs through their index too.

```text
default explicit IDs -> one aggregate HOT observation -> initial holds
indexed selectors -> group equal expressions using their actual waiting Item counts
  -> one take -> deduplicate and exclude used IDs
  -> one HOT observation -> one initial hold -> one membership retain
  -> pair surviving IDs with the earliest waiting Items of each selector
default ANY -> bounded Group HOT observation -> initial holds
  -> one Binding descriptor read for all usable candidates
  -> exact confirmation -> exact Item claim -> Command publication
```

Explicit targets are bounded by 100 Items x 100 IDs and aggregated into one Owner
read; only at most 100 can be held. Truncating that input to the first 100 IDs
would starve later Items and is forbidden. Indexed take demand sums to at most
100, preserving skewed per-selector counts instead of an equal-share cap.
Empty subsets skip their Owner operation. After the common binding read, default
ANY/IDs require no Matching index operation.

Matching never reads Worker Score. Index take time rotates identity evidence and
has no lease meaning. Kernel observes HOT, obtains initial holds and rechecks
index membership because initial hold clears dirty. Every successfully held ID
is excluded for the rest of the round, including membership-rejected IDs. Partial
hold/retain results pair survivors with the earliest Items of the same selector.
There is no same-round refill, overfetch, release compensation or pending registry.

For each Task's indexed subset, Matching uses one take Lua and zero or one retain
Lua. Kernel uses at most one indexed HOT observation/hold pair, plus the distinct
bounded identity paths, and one descriptor HMGET. Final confirmation, claim and
publication retain their existing costs. These are client-command budgets, not
throughput or latency claims.

## Assignment Closure

Only the package-private `TaskAssignmentDispatcher` constructs claimed Commands:

```text
exact Worker confirmation (clean original score -> execution fence with dirty=1)
  -> exact ACTIVE Item claim
  -> ResultContext carrying the returned execution fence
  -> Adapter-partitioned Worker mailbox
```

Pacer treats scores as opaque evidence. It cannot decode, construct or calculate
coordinates. An exact-fence failure publishes no Command. A confirmed execution
is not revoked by later facts updates. Unused and publication-failed leases
recover through existing expiry semantics.

Task Dispatch independently stores failed Result before requesting terminal tag
5 for exhausted/expired Items, and owns pacing/idle close or park. Result routing
and subsequent outcome observations retain their separate lifecycle and commits.

## Failure Semantics

| Failure | Result |
| --- | --- |
| missing/unavailable Task binding | no assignment; failure/idle handling continues |
| invalid selector | Server rejects before Item mutation; stored invalid input fails bounded acquisition |
| index or HOT/hold failure | existing Dispatch backoff; no fallback or extra Matching job |
| changed membership, dirty or competing exact score | candidate cannot pass the required fence |
| unused hold | expires naturally; no compensation |
| restart | bindings/facts persist; startup rebuilds enabled indexes before admission; Kernel rediscovery resumes |

There is no Score/facts transaction, ACK, replay, repair scan or guarantee that
lost Properties evidence eventually arrives. Rules never couple Task lifecycles.
