# XA Mass Worker Matching JVM

Status: current facts, Rule binding, source index and shared Eligibility inventory Owner.

A Rule is a stable semantic ID in the fixed application composition. Its facts
projection and query interpretation stay paired. It has no independent lifecycle,
persisted DSL, dynamic registry or per-Task candidate cache.

## Owner Boundary

```text
Worker / Platform facts -> enabled Rule projections -> Redis supply indexes

Main-selected NORMAL Tasks -> Matching bindings -> shared query targets (MAX)
  -> deficits -> refill -> Kernel initial hold -> current projection recheck
  -> process-local shared Eligibility inventory

TaskItems -> normalized queries (SUM) -> local take -> current address
  -> Kernel exact clean confirmation -> exact Item claim -> Command
```

Inventory is shared by `workerGroupId + ruleId`. Matching owns its entries,
query projection and cleanup deadline, retaining only opaque Kernel fences.
It requests initial holds through the narrow Kernel collaboration port and
never reads/constructs Score, confirms an execution, releases a hold, claims an
Item or publishes Commands. Kernel/Pacer receives Task IDs and bounded held
identities; Rule IDs, targets and source coordinates remain inside Matching.

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

- `deficits(targets)`: count current local stock and return each target's shortfall.
- `refill(targets, budget, kernelHold)`: recheck deficits, obtain source identities,
  request initial holds, read current membership/projection and admit stock.
- `take(requests)`: atomically remove matching local entries and return opaque fences.

Their operator-free query is `{"query":{"worker.country":["US","CN"]},"count":1}`:
choose one from either country. Independent minima use separate entries, such as
US count 10 and CN count 15. An empty query (also the configuration binder's
omitted empty object) means ANY. `workerId` lists mean explicit identity selection
and cannot combine with properties. One batch has at most 100 queries; each count
is 1..1000. Consumption additionally totals at most 100 candidates.

Handler validation and normalization are shared by all three operations.
HTTP Item selectors retain their existing `{op,values}` syntax; only Matching
converts them to the common query. Equal normalized refill targets merge using
MAX across Tasks; consumption counts sum actual requests. Overlapping queries
share physical entries; projected supply is counted against all overlapping targets
before requesting holds and checked again after acquisition. A US/CN OR
query never silently becomes one quota per country.

| Rule | Source projection | Query |
| --- | --- | --- |
| `worker.default` | no facts required for identity; optional country projection | ANY, IDs, and existing country property queries when enabled |
| `worker.country` | valid two-uppercase-letter country | ANY, IDs, country eq/in |
| `worker.messaging.available` | valid country and `messaging.enabled=true`; optional phone partition | membership-constrained ANY/IDs; country and optional one phone with AND |
| `proof.worker.facts` | fixed pool/target/platform and slot partitions | finite proof selectors on explicitly enabled Groups |

The Rule set and HTTP selector admission remain unchanged in this slice.
Business Rule extraction, default tightening and primary/secondary residency are
separate work. Adding a Rule must keep projection and all three query operations
paired without adding Kernel/Pacer branches.

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
a lifecycle lock. Old entries expire naturally. A round attempts at most 100
candidates per Eligibility and 1000 globally, rotating across selected pools and
bounded query pages. Constrained targets share the bounded attempt budget before ANY. Query pages
and their starting positions rotate, including when a page contains fewer than
100 targets. Supply and post-hold projection are each one batch per Eligibility,
not one Redis call per query. Explicit identity lists share a rotating bounded
observation page so occupied prefixes do not permanently hide later identities.
Watermarks are targets, not consumption gates or online/execution truth.

Kernel chooses a 5-second initial deadline and applies HOT/floor/exact acquisition.
After acquisition clears dirty, Matching batch-reads the current source projection
before admission. Default identity candidates need no facts; available country
projection enriches them for the existing default property query capability.
Inventory operations are locally atomic; source, Kernel and other external calls
execute outside its monitor. Source reads/holds are skipped at satisfied watermarks.

Consumption and deadline expiry remove entries. Dirty may transiently overcount
inventory until consumption or expiry, but final exact confirmation rejects the
old fence. No watermarks read Score. No hold is renewed, returned after failed
confirmation/claim, compensated or adopted across restart. Lost/failed admission
leaves the hold to expire. Capacity exhaustion is a shortfall, with no wait queue.

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
of Handler suffixes. The source coordinate remains `countryCode * 2^43 +
lastTakenMillis` with base-26 A..Z prefix 0..675 and milliseconds since 2000-01-01.
Proof partitions use prefix zero. Facts preserve low take time; bounded source
take rotates primary and partition entries together. This source coordinate is
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
malformed facts abort startup. With no configured Groups, rebuild is a no-op
and opens no Redis connection. The fixed Pacer refill Producer consumes these source indexes; there is no
index repair scan, lease registry or per-Task candidate publication.

## Cost, Failure and Proof

- Binding preparation: one HMGET per bounded Main batch.
- Normalized stock counts and take: zero Redis commands or facts reads.
- Named source refill with up to 100 distinct queries and 100 identities: one source Lua, one HOT ZMSCORE and
  TIME, one acquisition TIME and bounded CAS Lua, one projection Lua (6 commands).
- Default identity refill without a configured projection: HOT observation and
  acquisition only. A mixed default request can use both bounded identity and
  Group ANY hold calls, with at most 100 acquired candidates in total. Optional
  country projection adds one combined post-hold snapshot.
- Confirmation retains its TIME plus one bounded exact CAS per 100 identities.
  Address, Item claim, publication and Result paths retain their separate costs.

These are client command counts, not throughput promises. Matching logs aggregate
shortfall, acquired/admitted stock, requested/consumed candidates, unused expiry
and capacity limits. Existing
Pacer stage evidence separates refill, initial hold, confirmation rejection and
dispatch. No management API or diagnostics thread is added.

Binding failure blocks candidates while Item expiry/exhaustion/idle settlement
continues. Refill infrastructure failure reaches its Producer backoff; partial
holds expire. Take misses never query a source or acquire a new hold. Restart
loses local inventory and rebuilds it through ordinary refill without adoption,
ACK, replay or a repair scan.

Focused tests cover interpretation, overlapping stock and concurrency. Redis
Owner proves shared target MAX, local consumption, post-hold projection, exact
confirmation, dirty/expiry, restart and command counts. Runtime Boundary and
Dynamic Matching witness real Worker execution; Call Performance separately
measures mixed-workload behavior. See [TESTING](../TESTING.md).

Binding shape changes use the agreed stop/rebuild cutover: stop processes for an
explicit scope, clear only that scope with SCAN + UNLINK, then rebuild resources.
No old binding reader, compatibility alias or automatic non-test cleanup exists.
