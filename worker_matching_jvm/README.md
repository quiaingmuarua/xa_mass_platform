# XA Mass Worker Matching JVM

Status: current facts, named Rule admission, source index and Eligibility Owner.

A Rule is a stable semantic ID mapped to one thread-safe Handler instance in fixed
application composition. It owns qualification, shortfalls, admission, inventory
and consumption for that Rule. Tasks using the same Rule and WorkerGroup share the same stock. One Rule instance
serves multiple Groups; Group is an explicit resource scope on every operation.
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
NORMAL Task descriptors -> Group/Rule target MAX -> Rule deficits
  -> Pacer HOT head -> Kernel exact 1-second lease -> offered held IDs
  -> Rule qualification and admission -> Rule-owned local stock
messageId -> Item selector -> Catalog normalization/grouping -> Rule.take
  -> messageId -> held candidate -> current address
  -> Kernel exact clean confirmation -> exact Item claim -> Command
```

Rules receive opaque held fences, never a lease acquisition capability. They cannot
discover replacement IDs, renew holds, decode Kernel scores or claim Items.
Pacer carries Rule names, Group coordinates, correlation IDs and immutable query data
through `WorkerMatching`. It does not normalize or semantically group queries,
interpret business fields, depend on Handler implementations or construct index coordinates.

## Named Rule Admission

`resolveRefillTargets(workerGroupId, ruleId, requested)` resolves bounded immutable
targets without Redis access or stock changes. Explicit targets override configured
Group/Rule defaults, otherwise ANY 100. It normalizes queries and merges duplicates
using MAX. Empty targets, unknown/unavailable Rules and unsupported queries fail.
Server stores the resulting snapshot with the Rule name in the Kernel Task
descriptor. Matching does not accept Task IDs, persist Task configuration or own
Task lifecycle. The [Task Owner](../kernel_jvm/doc/resource-model/task-resource-model.md)
owns descriptor creation, lookup, corruption handling and configuration equality.

`RefillTarget` and `EligibilityQuery` remain shared assignment values. Rule
implementations own their interpretation; Kernel stores their structure. Main
shares its complete immutable Task descriptors with independent refill and dispatch.

The refill Producer groups and concatenates declarations by Group/Rule without
interpreting or merging queries. It calls `groupsNeedingRefill(targetsByGroup)`
once, then `refill(group, targetsByRule, heldCandidates)` for each acquired Group
batch. Matching normalizes equal targets using MAX and resolves each Rule by name.
The input is bounded to 100 Group/Rule coordinates and 10,000 declarations; each
Rule operation receives at most 100 queries through the existing rotating pages.
Shortage observation does not advance the target cursor; a refill attempt does.

Global expired-stock and inactive-cursor cleanup belongs to `groupsNeedingRefill`.
The hint and later admission may observe different stock. Refill independently
validates and works without a prior hint or Task registration. Both operations may redo
bounded local target normalization; they retain no shared execution plan, Handler
view or inventory transaction. Server admission and Main do not maintain stock.

Server admission uses `WorkerMatchingCatalog.normalizeQuery(group, ruleId, query)`.
The Pacer port exposes only shortage observation, refill and
`take(group, ruleId, queriesByMessageId)`. Missing Rule names or unavailable Group
indexes are rejected with no fallback. Group enablement still controls index maintenance.
All returned collections are immutable. These named operations do not receive Task IDs.

Take accepts at most 100 nonblank message IDs with non-null queries. Catalog captures
input order and normalizes the entire batch before any stock consumption. Equal
normalized queries are grouped in first-appearance order; each group's count is its
actual number of requests. Catalog invokes `RuleHandler.take` once and associates
each group's candidates with its message IDs in input order. The final Map follows
the original input order, omits unfulfilled IDs and contains no repeated Worker.
A valid empty batch returns without accessing stock; a late invalid query consumes
nothing. Fences and deadlines are returned unchanged. Current Rules take locally
without Redis access. The query-to-count Map remains internal to Matching.

Message IDs are call-local correlation keys. Matching neither stores them nor reads
Items, deduplicates requests across calls or owns their lifecycle. Filtering an
association or failing address lookup, confirmation or claim does not transfer its
candidate to another message ID, restore stock, trigger another take or renew a lease.

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
query means ANY, while count remains required. Targets are persisted in Task
descriptors. Catalog normalizes queries and merges equal targets using MAX; Rule
deficits and refill receive ordered query-to-count Maps. The internal Rule take
receives normalized queries with actual Item counts; its results retain the supplied
query keys. Server normalizes Items before storage, while Catalog normalizes and
groups them again at consumption. Item queries still never create refill demand.

Each operation permits at most 100 queries. Refill target counts are 1..1000;
take counts and their sum are at most 100. Refill accepts at most 100 unique held
IDs and an acceptance limit in 0..100. Invalid input is rejected before stock
changes. Overlapping queries cannot consume the same candidate twice. Default
identity targets retain the declared target count but use `min(count, unique ID
count)` for shortages, without checking Worker existence.

Old `{op,values}` conditions are rejected, including in retained TaskItem records;
there is no compatibility reader or conversion to ANY. Recreate old property-query
Tasks in a new scope. Existing records are never automatically migrated or cleared.
Old ANY/ID Maps already have the current shape and remain readable. Task target JSON,
Facts, index keys and index encoding are unchanged by this query migration.

| Rule | Source qualification | Queries |
| --- | --- | --- |
| `worker.default` | No facts needed for identity; optional country source | ANY, IDs, and country queries where enabled |
| `worker.country` | Valid two-uppercase-letter country | ANY, country string list |
| `worker.messaging.available` | Valid country and `messaging.enabled=true`; optional phone partition | Membership-constrained ANY; country AND optional one phone |
| `proof.worker.facts` | Fixed pool/target/platform and slot partitions | Finite proof selectors on configured Groups |

Only Default accepts explicit Worker IDs, without property combinations. Named
Rules reject them; named-Rule ID targets are rejected with no fallback.
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

Global lazy expiry runs once at Group shortage observation by invoking current Rule-owned
pool cleanup. It does not store a second inventory. Group access expires only its
own pool; diagnostics and capacity reads do not sweep unrelated pools. Admission
uses observed shortages and rechecks each entry's original deadline and shared
hard capacity at commit, without revalidating the whole inventory.
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

- Target resolution: local only, no Redis; Task configuration reads belong to Task Owner.
- Group shortage observation: zero Redis commands; local target aggregation and
  one global expiry sweep per round. Named refill independently repeats bounded
  target normalization; only visited target pages reach Rule inventory operations.
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

Candidate failures do not add a prerequisite to Item expiry/exhaustion/idle settlement. Refill infrastructure failure reaches its Producer backoff; partial
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

Task configuration now lives in the Task descriptor. Recreate Tasks in a new scope;
old descriptor formats are rejected, and old Matching data is neither read nor
cleared. HTTP, facts/index formats and Score encoding are unchanged. Local stock
is still lost on restart and outstanding holds expire.
