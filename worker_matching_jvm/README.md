# XA Mass Worker Matching JVM

Status: current facts, Rule binding, source index and Eligibility Owner.

A Rule is a stable semantic ID mapped to one thread-safe Handler instance in fixed
application composition. It owns qualification, shortfalls, admission, inventory
and consumption for that Rule. Tasks sharing its WorkerGroup share the same stock.
There is no persisted DSL, dynamic registry, per-Task cache or Matching execution thread.

## Owner Boundary

Pacer synchronously calls Matching from its existing independent refill and dispatch
Producers. It observes the Group HOT head and exact-acquires 1-second candidate
leases before a Rule reads eligibility. Only successful new fences are offered.
Matching processing and stock waiting share the original deadline; unmatched leases
expire naturally. Kernel/Pacer retains all Score, confirmation and claim authority.

| Stage | Authority and fence |
| --- | --- |
| Supply | Pacer reads a bounded Group HOT head and exact-acquires leases, clearing dirty |
| Qualification and admission | Each Rule reads only offered IDs and retains admitted candidates with the original fences and deadlines |
| Coordination | Catalog excludes IDs actually accepted by an earlier Rule from later Rules in that Group batch |
| Execution | Kernel exact-confirms a clean inventory fence, sets dirty=1, then exact-claims the Item |

**Cross-Rule failure contract:** Rule A's successful refill remains committed if
Rule B subsequently fails. The exception ends the remaining batch and reaches the
existing Pacer failure path. The next normal round observes new shortfalls.
There is no rollback or automatic replay. Within each current Rule implementation,
Redis reads and fallible validation/matching finish before its bounded local commit.
This partial-success contract is independent of atomic Facts/index writes below.

```text
Worker / Platform facts -> one Lua -> enabled Rule indexes
NORMAL Tasks -> bindings -> Group/Rule target MAX -> Rule deficits
  -> Pacer HOT head -> Kernel exact 1-second lease -> offered held IDs
  -> Rule qualification and admission -> Rule-owned local stock
Item selectors -> Rule.take -> current address
  -> Kernel exact clean confirmation -> exact Item claim -> Command
```

Rules receive opaque held fences, never a lease acquisition capability. They cannot
discover replacement IDs, renew holds, decode Kernel scores or claim Items.
Kernel/Pacer sees Task IDs and held identities, never Rule IDs or index coordinates.

## Shared Rule Binding

`bindTaskRule(taskId, workerGroupId, ruleId, refillTargets)` creates an immutable
binding before Kernel Task creation. Omitted targets resolve from Group/Rule
configuration, otherwise ANY 100. Explicit targets override defaults. Complete
normalized targets are stored; identical bindings are unchanged, different or
corrupt bindings conflict. Configuration changes do not rewrite existing bindings.
Unknown/unavailable Rules and unsupported targets are rejected. Failed Kernel
creation may leave an inert binding; it does not roll back Matching.

`prepareTaskQueries(taskId -> workerGroupId)` resolves at most 100 Tasks with one
HMGET. Missing/corrupt bindings, wrong Groups and unavailable Rules yield null.
Main shares this map between refill and dispatch. A Task view delegates validation
and take to its Rule with the Group; it holds no private candidate copy.

Only the refill Producer calls `prepareRefill(preparedTasks)`. Its invocation-local
batch merges targets by Group/Rule and canonical query using MAX. It pages at most
100 targets per Rule operation and rotates visited target pages. Unvisited pages
never enter Rule operations. Group calls retain the closed demand batch without
re-reading bindings or reconstructing Task targets. Server admission and Main
preparation do not maintain inventory.

## Unified Queries and Fixed Handlers

The [RuleHandler interface](src/main/java/com/xa/mass/workermatching/RuleHandler.java)
contains exactly four operations:

| Operation | Contract |
| --- | --- |
| `normalizeQuery(group, query)` | Idempotent validation/canonicalization shared by Item and target admission; no Redis read or stock mutation |
| `deficits(group, targets)` | Immutable observed shortages, never reservations |
| `refill(group, targets, offered, maxAccepted)` | Qualify and admit held candidates; return actual accepted Worker IDs |
| `take(group, limits)` | Validate again and commit each still-current selected entry, returning original held fences |

Rule ID is associated with the instance at assembly; these methods have no Task ID.
Group is a semantic coordinate. Redis keys, source scores, qualification values and
local collections never cross this interface. Every result is an immutable snapshot.
All operations are thread-safe and Group-isolated. A Rule can implement these
operations using ZSETs, Maps or another bounded representation.

`EligibilityQuery` is the immutable `Map<String,List<String>>` contract in Kernel's
assignment package. It carries no quantity or field semantics. HTTP and Redis
store the direct Map; fields are non-blank and each has 1..100 non-blank strings,
with at most 100 fields. Lists retain order and duplicates until the Rule normalizes
them. Current Rules sort/deduplicate set-valued parameters, including Default IDs.
Only Default accepts `workerId`, exclusively of other fields; Kernel does not
interpret it. Country uses `{"worker.country":["CN","US"]}` and Messaging intersects
its supported fields. Empty query means no additional condition within that Rule.

`RefillTarget` pairs that shared query with a count and retains the flat HTTP/YAML
shape `{"query":{"worker.country":["US","CN"]},"count":100}`. Omitted or null target
query means ANY, while count remains required. It is also the persisted Binding
value. Catalog normalizes queries and merges equal targets using MAX; Rule deficits
and refill receive ordered query-to-count Maps. Take receives the same query type
with actual Item counts. Results remain keyed by the supplied queries even when
normalization changes their value order. Task views normalize Items before storage
and before Pacer grouping; Item queries still never create refill demand.

Each operation permits at most 100 queries. Refill target counts are 1..1000;
take counts and their sum are at most 100. Refill accepts at most 100 unique held
IDs and an acceptance limit in 0..100. Invalid input is rejected before stock
changes. Overlapping queries cannot consume the same candidate twice. Default
identity targets retain the declared Binding count but use `min(count, unique ID
count)` for shortages, without checking Worker existence.

Old `{op,values}` conditions are rejected, including in retained TaskItem records;
there is no compatibility reader or conversion to ANY. Recreate old property-query
Tasks in a new scope. Existing records are never automatically migrated or cleared.
Old ANY/ID Maps already have the current shape and remain readable. Task Binding,
Facts, index keys and index encoding are unchanged by this query migration.

| Rule | Source qualification | Queries |
| --- | --- | --- |
| `worker.default` | No facts needed for identity; optional country source | ANY, IDs, and country queries where enabled |
| `worker.country` | Valid two-uppercase-letter country | ANY, country string list |
| `worker.messaging.available` | Valid country and `messaging.enabled=true`; optional phone partition | Membership-constrained ANY; country AND optional one phone |
| `proof.worker.facts` | Fixed pool/target/platform and slot partitions | Finite proof selectors on configured Groups |

Only Default accepts explicit Worker IDs, without property combinations. Named
Rules reject them; old named-Rule ID bindings are unavailable with no fallback.
Default's optional country capability remains for current SMS start/cancel flows.

## Adding a Rule

Application assembly supplies the immutable Rule ID-to-instance Map.
`RedisRuleStorage` supplies the current implementations' single Redis connection,
key construction, trusted index-write descriptors, clock and shared capacity budget.
It is storage assembly, separate from the four-operation Eligibility contract.
Unknown IDs, missing Default and duplicate storage namespaces fail construction.
No Rule creates an independent connection pool or lifecycle loop.

The current partitioned Rules reuse `PartitionedRuleHandler`, including Country's
paired numeric prefix/time encoding and query interpretation. Its local candidate
implementation, `LocalCandidateRule`, holds private Group pools and typed qualification
values. Rules may reuse that implementation or implement the interface directly.
Catalog Eligibility calls never receive predicates, projections, source keys or
encoded index scores. Facts/index keys remain in the separate storage assembly.

Local candidate operations observe stock and evaluate fallible matchers once outside
their gate. There is no pool revision or optimistic retry. Take commits only the exact
selected entry object while it is still current and unexpired; a consumed, expired or
reinserted entry is skipped without selecting a replacement. Unaffected entries can
still commit. Unrelated entries and Groups cannot restart matching in that call.

Refill uses its observed shortfalls and checks each admission for a vacant Worker ID,
the original deadline and current hard capacity. Concurrent refills may exceed a
target watermark; concurrent consumption may leave it short. Targets are not
reservations, and later rounds observe the changed stock. Per-call limits and the
pool/process capacity limits remain hard bounds. Reads and all fallible matching
still finish before any new entry is admitted by that Rule.

Take and expiry cannot return an old or duplicate consumed entry. Shared
`CandidateBudget` stores only counts and opaque pool tokens, with no Worker IDs,
Rule projections or matching. Group pools remain in their Rule.

Facts/index maintenance uses trusted construction-time `IndexMutation` descriptors.
Their fixed Lua programs return a read/validate `prepare` function and an `apply`
closure. Preparation cannot write. One Facts script prepares every Worker and
enabled index before its first write; a new Rule's storage joins that script through
implementation assembly, never through sequential public Rule property writes.

The test-only [Bucket Rule](../server_jvm/src/test/java/com/xa/mass/server/testsupport/BucketRuleHandler.java)
uses SETs and a HASH source, with its own Eligibility through the same public
operations. Redis Owner proves its atomic index writes, independent facts,
command budgets and dirty rejection. Runtime Boundary runs two Tasks through
it with an actual Worker. Pure in-memory rules also exercise Catalog routing.

```yaml
xa:
  mass:
    worker-matching:
      rules:
        worker-groups:
          demo-sim: [worker.country, worker.messaging.available]
        default-refill-targets:
          demo-sim:
            "[worker.default]":
              - query: {"worker.country": [CN]}
                count: 10
```

Rare partitions and explicit identities need suitable declared targets; Items never
drive refill. The scenario-workers profile targets ANY 1000 for managed Groups;
the general default remains 100.

## Inventory and Refill Bounds

Current Rules retain candidates only in this process: at most 100 resident
Group/Rule pools, 1000 entries per pool and 10000 in total. Each entry carries
its identity, private qualification value, original opaque fence and deadline.
Tasks have no reserved share. Restart discards all stock without adoption.

The single-flight refill Producer uses Main-selected NORMAL RUNNING Tasks, with
a 50ms completion-relative interval. INITIAL does not prewarm. Closed, parked or
disabled Tasks supply no later demand; an in-flight round is not a lifecycle lock.
Pacer rotates Groups, at most 100 HOT candidates per Group and 1000 per round.
A positive deficit enables a Group but never reduces its fixed HOT scan budget.

Catalog rotates Rules and bounded query pages, including empty attempts. A Rule
prioritizes constrained targets before ANY, incrementing all overlapping target
counts for each admitted candidate. Each offered candidate/query pair is evaluated
once per participating page before admission. Each Group batch passes only remaining
IDs to later Rules; an actual acceptance, not a count estimate, removes an offer.

Global lazy expiry runs once at refill preparation by invoking current Rule-owned
pool cleanup. It does not store a second inventory. Group access expires only its
own pool; diagnostics and capacity reads do not sweep unrelated pools. Admission
rechecks original deadlines, current shortages and shared capacity at commit.
Other expiry may release capacity after the round's budget was observed, so a
round may conservatively underfill. A Rule with no available capacity reports zero
refill deficit, preserving the existing full-stock supply suppression. No operation
extends a lease.

Pacer computes the original deadline immediately before acquisition. Qualification
time and inventory waiting consume that same second. Explicit IDs only filter
offered identities and stock; they never trigger a targeted HOT read. Rare targets
may wait longer under the fixed Group supply policy.

Consumption is destructive. Misses, read failures, ambiguous acquisition and failed
admission leave any held Kernel lease to expire; there is no compensation release,
renewal, reinsertion, replay or pending registry. Unobserved dirty changes may
temporarily overcount stock; final execution confirmation still requires the exact
clean active non-PAUSE fence and Redis time. Already-held candidates are not a
readiness assertion.

## Persistent Catalog

All keys use `xa_mass:<scope>`:

```text
:matching:worker:facts:<group>                         HASH workerId -> Worker JSON
:matching:worker:platform-properties:<group>           HASH workerId -> Platform JSON
:matching:task:rules                                  HASH taskId -> {ruleId,workerGroupId,refillTargets}
:matching:worker:index:<encoded-group>:<handler>       ZSET workerId -> source coordinate
:matching:worker:index:<encoded-group>:<handler>:partitions
                                                      HASH workerId -> partition suffixes
:matching:worker:index:<encoded-group>:<handler>:partition:<digest>
                                                      ZSET workerId -> source coordinate
```

Group encoding is UTF-8 Base64 URL without padding; partition digests use SHA-1
of the existing ZSET Handler suffixes. Those implementations retain the source coordinate `countryCode * 2^43 +
lastTakenMillis` with base-26 A..Z prefix 0..675 and milliseconds since 2000-01-01.
Proof partitions use prefix zero. The retained low time component is preserved
by facts updates; refill no longer rotates or writes these coordinates. This index coordinate is
neither a Kernel fence nor a consumable inventory entry.

## Facts Writes and Index Maintenance

Prepare creates no facts. An admitted Adapter observation replaces the complete
Worker string Map, including an observed empty Map, while preserving Platform
Properties. Platform patch requires an existing Worker facts row and changes
only supplied Platform fields; null removes a field. Nested Platform JSON,
including empty arrays and objects, retains its shape.

A batch of 1..100 Worker replacements or one bounded Platform patch uses one Lua.
That operation reads the latest opposite facts, validates stored objects/index
metadata, computes enabled projections and writes facts with the corresponding
memberships. There is no Java pre-read/CAS loop. Worker and Platform writes cannot
lose each other's independent changes. Missing eligibility removes memberships.
Unexpected/corrupt stored data fails; it is not converted to empty eligible facts.

Server separately asks Worker Score Owner to invalidate candidate eligibility
after APPLIED facts writes. Facts/index and dirty are different Owner commits;
there is no cross-owner transaction, guaranteed retry or repair. Acquisition clears
dirty before Matching reads current projections. Matching never clears it again:
a successful dirty update after acquisition invalidates the candidate's exact
execution fence, including when it follows the projection read. Facts may still
change between projection and invalidation/confirmation; dirty is not a facts
version and does not make the independent commits atomic. Already confirmed execution is
not revoked by later facts observations. The dirty operation continues marking all
existing valid scores, including due and recovery scores; it is not lease-only.

Startup rebuilds only enabled Group indexes with bounded SCAN/UNLINK and HSCAN
pages, before admission and Pacer start. Retained facts are the rebuild input;
malformed facts abort startup. Cleanup visits only the declared roots and descendants
of enabled Rules, leaving other index namespaces untouched. With no configured Groups, rebuild is a no-op
and opens no Redis connection. Refill reads their projections only for Pacer-issued IDs; there is no
autonomous source take, index repair scan, lease registry or per-Task publication.

## Cost, Failure and Proof

- Binding preparation: one HMGET per bounded Main batch.
- Refill preparation: zero Redis commands; one target aggregation and global expiry
  sweep per round; only visited bounded target pages reach Rule operations.
- Normalized stock counts and take: zero Redis commands or facts reads.
- Each nonempty eligible Group batch: one read-only HOT head Lua (TIME and
  ZRANGE BYSCORE LIMIT 0 limit inside), then one candidate-acquisition Lua, followed by at most one
  projection read per participating Handler. No successful acquisitions means no
  Matching call. One named Handler uses three client commands for up to 100 IDs.
  Zero-match batches also acquire leases; full stock skips observation and acquisition.
- Default without a configured projection needs no projection read; it uses the same
  Pacer Group supply, never an explicit-ID observation path.
- Final confirmation: one bounded Lua, with its Redis TIME inside the operation.
  Address, Item claim, publication and Result paths retain their separate costs.

These are client command counts, not throughput promises. Matching logs aggregate
shortfall, resident/admitted stock, consumption, unused expiry and capacity limits. Pacer records observed counts and acquisition success/rejection;
offered now means successfully leased, not an unheld observation. Existing
Pacer stage evidence separates refill observation, first acquisition, confirmation rejection and
dispatch. `INITIAL_HOLD` retains attempted `batchSize` and successful `count`;
their difference counts rejected candidates only when `failed=false`. A failed
call is unconfirmed, not proof of lease rejection. No management API or diagnostics thread is added.

Binding failure blocks candidates while Item expiry/exhaustion/idle settlement
continues. Refill infrastructure failure reaches its Producer backoff; partial
holds expire. Take misses never query a source or acquire a new hold. Restart
loses local inventory and rebuilds it through ordinary refill without adoption,
ACK, replay or a repair scan.

Focused tests cover interpretation, overlapping stock, bounded predicate evaluation,
Group target paging, cross-Rule partial success, local expiry and concurrency. Redis
Owner proves head observation and acquisition-driven progress, closed supplied batches, acquisition before
projection (including unmatched candidates), shared target MAX, dirty clearing,
execution fence invalidation, commit-time expiry and command order/counts. Controlled
Matching clocks prove that processing time consumes the original lease deadline.
Runtime Boundary adds six actual Workers in two Groups serving four Tasks and 800
Items, including shared and different Rules within one Group. Runtime Boundary and
Dynamic Matching witness real Worker execution; Call Performance separately
measures mixed-workload behavior. See [TESTING](../TESTING.md).

This Rule ownership change affects no HTTP, Binding shape, Redis keys or Score
encoding. Restart the existing process: local stock is discarded and outstanding
holds expire. It requires no runtime-data cleanup or migration.
