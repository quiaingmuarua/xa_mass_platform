# XA Mass Worker Matching JVM

Status: current facts, Rule binding, source index and shared Eligibility inventory Owner.

A Rule is a stable semantic ID mapped to one Handler instance in fixed application
composition. Its facts projection and query interpretation stay paired. It has no independent lifecycle,
persisted DSL, dynamic registry or per-Task candidate cache.

## Owner Boundary

**Core mechanism change: candidate supply belongs exclusively to Pacer.** Matching no
longer discovers IDs from an index or invokes an initial-acquisition port. It may
qualify and adopt only the closed batch Pacer already holds.

| Stage | Authority and fence |
| --- | --- |
| Supply | Pacer observes Group HOT and exact-acquires a 1-second handoff hold S0 |
| Admission | Matching reads supplied-ID projections; one batch-bound callback exact-extends accepted IDs to a 5-second inventory hold S1 |
| Execution | Kernel confirms S1 to S2, setting dirty=1, then exact-claims the Item |

```text
Worker / Platform facts -> enabled Rule projections -> Redis eligibility indexes
NORMAL Tasks -> bindings -> shared query targets MAX -> local Group deficits
  -> Pacer Group HOT observation -> short hold S0 -> offered IDs
  -> Matching current projection -> acceptance plan -> one Kernel extension S1
  -> process-local shared Eligibility inventory
TaskItems -> normalized queries SUM -> local take -> current address
  -> Kernel exact clean confirmation S2 -> exact Item claim -> Command
```

Inventory is shared by `workerGroupId + ruleId`. Matching owns its entries,
projection and cleanup deadline and retains opaque Kernel fences. Only Pacer
chooses supply identities, Group budgets and lease deadlines. The renewal callback
accepts only a unique subset of its issued IDs, once during the issuing call and on
the calling thread. It captures Group and S0 fences; Matching cannot supply scores
or deadlines. Rule Handlers receive no lease capability. Kernel/Pacer receives
Task IDs and bounded held identities, never Rule IDs, targets or index coordinates.

## Shared Rule Binding

`bindTaskRule(taskId, workerGroupId, ruleId, refillTargets)` creates one immutable
binding before Kernel Task creation. Omitted targets resolve from Matching's
Group/Rule configuration, otherwise ANY 100. Explicit targets override defaults.
The complete normalized targets are stored. An identical binding is unchanged;
a different or corrupt stored value conflicts. Configuration changes do not
rewrite existing bindings. Unknown/unavailable Rules and unsupported targets
are rejected. Failed Kernel creation may leave an inert binding without rollback.

`prepareTaskQueries(taskId -> workerGroupId)` resolves at most 100 Tasks with one
HMGET. Missing/corrupt bindings, wrong Groups and unavailable Rules yield null.
Main shares that prepared map between refill and dispatch. A Task query is a
view of shared inventory; it owns no candidate copy or lifecycle. Admission uses
the same local Handler interpretation without reading facts or taking candidates.

## Unified Queries and Fixed Handlers

The three paired Eligibility operations are:

- `deficits(preparedTasks)`: aggregate local query shortfalls and capacity into Group deficits.
- `refill(preparedTasks, group, offeredCandidates, renewal)`: read current projections
  for at most 100 supplied IDs, plan acceptance, renew the union once and admit only S1.
- `take(requests)`: atomically remove matching local entries and return opaque fences.

Their operator-free query is `{"query":{"worker.country":["US","CN"]},"count":1}`:
choose one from either country. Independent minima use separate entries, such as
US count 10 and CN count 15. An empty query (also the configuration binder's
omitted empty object) means ANY. `workerId` lists mean explicit identity selection
only in `worker.default` and cannot combine with properties. One batch has at most 100 queries; each count
is 1..1000. Consumption additionally totals at most 100 candidates.

The common Q type retains immutable bounded structure, including value order and
duplicates. Each Handler owns semantic normalization; the existing identity and
country Rules sort/deduplicate their set-valued parameters. Handler validation and
normalization are shared by all three operations.
HTTP Item selectors retain their existing `{op,values}` syntax; only Matching
converts them to the common query. Equal normalized refill targets merge using
MAX across Tasks; consumption counts sum actual requests. Default identity targets
retain their declared count in the Binding but use `min(count, unique ID count)`
for deficits. Two IDs with count 100 are full at two retained entries; no extra
Worker existence read is performed. Overlapping queries
share physical entries; each admission is counted against overlapping targets
before further entries are planned. A US/CN OR
query never silently becomes one quota per country.

| Rule | Source projection | Query |
| --- | --- | --- |
| `worker.default` | no facts required for identity; optional country projection | ANY, IDs, and existing country property queries when enabled |
| `worker.country` | valid two-uppercase-letter country | ANY, country eq/in |
| `worker.messaging.available` | valid country and `messaging.enabled=true`; optional phone partition | membership-constrained ANY; country and optional one phone with AND |
| `proof.worker.facts` | fixed pool/target/platform and slot partitions | finite proof selectors on explicitly enabled Groups |

Only `worker.default` accepts Worker ID queries, both in refill targets and Item
admission. Named Rules reject them instead of interpreting them as membership
filters. Old named-Rule ID bindings read as unavailable; they never fall back.
Default retains its existing optional country capability for current SMS start
and cancellation flows. Removing that capability is a later binding migration.

## Adding a Rule

`RedisWorkerMatchingCatalog` receives an immutable `Map<String, RuleHandler>`.
The application supplies `DefaultRuleHandler`, `CountryRuleHandler`,
`MessagingRuleHandler` and explicitly enabled proof implementations. Unknown
configured IDs, missing default and conflicting index namespaces fail construction.
Implementations live under `rules`; the Catalog and stock mechanism do not import
that package. A new Rule adds an implementation, a composition entry and its proof.
There is no runtime registration, Rule lifecycle or per-Handler connection pool.

The public [Rule Handler contract](src/main/java/com/xa/mass/workermatching/RuleHandler.java)
pairs the functions behind the shared `deficits/refill/take` loop:

- `bind` returns lightweight Group-bound functions using the Catalog's connection supplier.
- `normalize` validates/canonicalizes Q; `selector` may translate the Rule's HTTP syntax
  to that same Q. Existing property Rules keep HTTP eq/in; a new Rule may use list parameters.
- `compile` returns a pure matcher over immutable Rule-owned projections and an effective
  target bound where needed. It performs no Redis read.
- `snapshot` reads only the supplied IDs in one bounded projection batch after S0.
  Shared refill matches that evidence, then requests the single S1 extension. A Handler
  cannot discover other IDs, acquire leases or rotate a source index during refill.
- `indexes` declares exclusive namespace roots and fixed Lua preparation programs.
  Each program returns a read/validate `prepare` function that returns an `apply` closure.
  Namespaces include their descendants; preparation cannot write. The facts Owner prepares
  the entire batch before its first write and rejects invalid update closures.

A Rule may own multiple keys and choose its layout. `PartitionedZsetIndex` and
`ZsetProjection` are helpers for the existing implementations, not universal query
or storage models. Public projections are immutable Rule-owned values; shared stock
retains them without decoding. Handler work runs outside stock locks. Take commits
only entries from its observed snapshot that are still the exact resident objects;
concurrent consumption or replacement can produce fewer results, never duplicates.

The separately packaged [Bucket Rule proof](../server_jvm/src/test/java/com/xa/mass/server/testsupport/BucketRuleHandler.java)
uses bucket SETs and a projection HASH through only the public contract. Redis Owner
proves update ordering, independent facts, command budgets and dirty-fence rejection;
Runtime Boundary runs two Tasks through it with an actual Worker. It is test-only.

Example composition, including a Group-managed Call target:

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

Rare partitions and explicit identity workloads must declare suitable targets;
refill never discovers them by scanning Items. The scenario-workers profile
explicitly targets ANY 1000 for its managed Groups, allowing its large targeted
Call fixtures to consume the shared inventory. The general default remains 100.

## Inventory and Refill Bounds

`SharedEligibilityInventory` is the sole process-local store: at most 100 resident
Eligibilities, 1000 entries per Eligibility and 10000 entries total. One entry
contains Worker ID, current query projection, opaque initial fence and cleanup
deadline. Tasks have neither private stock nor reserved shares.

The fixed single-flight Pacer refill Producer uses only Main-selected NORMAL
RUNNING Tasks, with a 50ms completion-relative interval. INITIAL does not prewarm.
Closed/parked/disabled Tasks produce no later demand; in-flight evidence is not
a lifecycle lock. Old entries expire naturally. Pacer rotates Groups after every
attempt, with up to 100 candidates per Group and 1000 per round. Positive business
deficit enables a Group; its numeric value does not reduce the 100-ID scan budget.
Matching rotates Eligibility acceptance and bounded query pages, including empty
attempts. Constrained queries precede ANY within each Eligibility. One candidate
can enter only one Eligibility; overlapping queries share its physical entry.

Pacer exact-acquires S0 before any projection read. Matching collects acceptance
plans across that Group, then invokes one renewal for their union. Only successful
S1 transitions enter stock. Default identity membership needs no facts; enabled
country projections support its existing property selectors. Explicit IDs only
filter this batch and stock: they never initiate a targeted HOT read. Rare queries
and IDs may wait longer under this bounded Group supply policy.

The plans live only in the current stack. Inventory snapshot/add/take are local
atomic operations; Handler, Redis and renewal calls run outside the monitor.
Satisfied watermarks or exhausted capacity skip supply. Counts are hints, not
live execution truth: unobserved dirty changes may temporarily overcount stock.

Consumption and expiry remove entries. S0 failures expire after the short hold;
ambiguous renewal or failed insertion leaves S1 to expire. There is no periodic
renewal, compensation release, candidate reinsertion or adoption across restart.
Only the initial due acquisition may clear dirty. Extension and final confirmation
require exact clean active non-PAUSE fences and check Redis time inside their Lua.
Capacity exhaustion produces a shortfall with no wait queue.

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
there is no cross-owner transaction, guaranteed retry or repair. Post-hold
projection recheck and Kernel exact dirty confirmation fence inventory admission and execution. Already
confirmed execution is not revoked by a later facts observation.

Startup rebuilds only enabled Group indexes with bounded SCAN/UNLINK and HSCAN
pages, before admission and Pacer start. Retained facts are the rebuild input;
malformed facts abort startup. Cleanup visits only the declared roots and descendants
of enabled Rules, leaving other index namespaces untouched. With no configured Groups, rebuild is a no-op
and opens no Redis connection. Refill reads their projections only for Pacer-issued IDs; there is no
autonomous source take, index repair scan, lease registry or per-Task publication.

## Cost, Failure and Proof

- Binding preparation: one HMGET per bounded Main batch.
- Normalized stock counts and take: zero Redis commands or facts reads.
- Each eligible Group: existing HOT observation (TIME + bounded range), one S0 Lua,
  at most one projection read per participating Handler, zero or one merged S1 Lua.
  One named Handler with accepted stock uses five client commands for up to 100 IDs.
- Default without a configured projection needs no projection read; it uses the same
  Pacer Group supply, never an explicit-ID observation path.
- Final confirmation: one bounded Lua, with its Redis TIME inside the operation.
  Address, Item claim, publication and Result paths retain their separate costs.

These are client command counts, not throughput promises. Matching logs aggregate
shortfall, offered/renewed/admitted stock, short unaccepted holds, renewal rejection,
renewed-but-not-inserted holds, consumption, unused expiry and capacity limits. Existing
Pacer stage evidence separates refill, initial hold, inventory extension, confirmation rejection and
dispatch. No management API or diagnostics thread is added.

Binding failure blocks candidates while Item expiry/exhaustion/idle settlement
continues. Refill infrastructure failure reaches its Producer backoff; partial
holds expire. Take misses never query a source or acquire a new hold. Restart
loses local inventory and rebuilds it through ordinary refill without adoption,
ACK, replay or a repair scan.

Focused tests cover interpretation, overlapping stock and concurrency. Redis
Owner proves closed supplied batches, one merged renewal, shared target MAX,
post-hold projection, S0/S1/S2 fence invalidation, commit-time expiry and command counts. Runtime Boundary and
Dynamic Matching witness real Worker execution; Call Performance separately
measures mixed-workload behavior. See [TESTING](../TESTING.md).

This supply-authority repair changes no HTTP, Binding shape, Redis keys or Score
encoding. Restart the existing process: local stock is discarded and outstanding
holds expire. It requires no runtime-data cleanup or migration.
