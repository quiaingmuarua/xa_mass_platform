# XA Mass Worker Matching JVM

Status: current Worker facts, fixed Rule Handlers, Task bindings and materialized eligibility index Owner.

Matching pairs two functions in each Rule Handler: project Worker/Platform facts
into an index, and interpret a bounded query against that index. A Rule is a
stable semantic ID selected at composition; it has no create/update/delete API,
Task lifecycle, persisted DSL definition or runtime registration.

## Owner Boundary

```text
Worker / Platform facts
  -> enabled Rule Handler projections, in the same facts mutation
  -> shared Group eligibility indexes

Task ID -> Matching Task binding -> prepared TaskQuery
Item selector -> bounded eligible Worker IDs
  -> Kernel HOT observation / initial hold
  -> same TaskQuery membership recheck
  -> Kernel exact confirmation / Item claim / Command
```

Matching owns facts, bindings, index encoding and take-time rotation. It never
reads Worker Score, acquires or releases leases, claims Items, ranks Tasks or
publishes Commands. Kernel receives Task IDs and bounded identity evidence;
Rule IDs and index coordinates stop at Matching. Server validates and composes
Owner calls. Transport supplies admitted observations and executes Commands.

## Shared Rule Binding

Every Task, including a managed Call, has a create-only binding established
before its Kernel descriptor. Omitted public `ruleId` selects `worker.default`.
`bindTaskRule(taskId, workerGroupId, ruleId)` uses one Lua on the binding HASH.
An identical binding is unchanged; a different or corrupt stored value conflicts.
Unknown or unavailable Rules are rejected. Failed Kernel creation may leave an
inert binding; no rollback, discovery, cleanup job or Task lifecycle is added.

`loadTaskBindings` returns Task binding snapshots, not Rule definitions.
`prepareTaskQueries(taskId -> workerGroupId)` accepts at most 100 Tasks, uses one
HMGET and returns a dispatch-local query per valid binding. Missing/corrupt
bindings, wrong Groups and disabled Handlers return no query. They never select
a default Rule implicitly. The query has no thread, cache, close or lifecycle.
The same resolved query performs admission, take and post-hold membership checks.
Sharing a Rule shares eligibility indexes, not Task state or Worker leases.

## Fixed Handlers and Selectors

| Rule | Projection | Query |
| --- | --- | --- |
| `worker.default` | no required facts for identity selection | `{}` and sole `workerId` lists use Kernel HOT selection; property conditions use the enabled country Handler |
| `worker.country` | Workers with a two-uppercase-letter country | ANY, explicit IDs, or `worker.country` eq/in |
| `worker.messaging.available` | country-valid Workers with `messaging.enabled=true`, plus an optional phone partition | ANY/IDs constrained by membership; country eq/in and optional one phone combined with AND |
| `proof.worker.facts` | fixed proof pool/target/platform and convergence-slot partitions | finite proof selectors; enabled only in proof Group configurations |

```json
{}
{"workerId":["worker-a","worker-b"]}
{"worker.country":{"op":"in","values":["CN","US"]}}
{"worker.country":{"op":"eq","values":["CN"]},"worker.phone":{"op":"eq","values":["+86123"]}}
```

Kernel captures bounded immutable JSON, ANY and explicit-ID structure. Matching
interprets full property names and `{op, values}`; eq requires one string, in
accepts 1..100 strings. Each Handler admits only its supported fields and shapes.
No general boolean expression evaluator or scan-over-facts query is installed.
Named Rules constrain ANY and explicit IDs as well as property selectors.
Adding a Rule/index changes Matching's finite composition and proof, not Kernel
Task metadata, Pacer branches or Transport.

Enable non-default Handlers explicitly per Group:

```yaml
xa:
  mass:
    worker-matching:
      rules:
        worker-groups:
          demo-sim: [worker.country, worker.messaging.available]
```

Each configured ID must name an installed Handler. The default Rule is available
without configuration. A property query cannot silently downgrade when its
required Group index is unavailable.

## Persistent Catalog

All keys use the configured `xa_mass:<scope>` base:

```text
:matching:worker:facts:<group>                         HASH workerId -> Worker JSON
:matching:worker:platform-properties:<group>           HASH workerId -> Platform JSON
:matching:task:rules                                  HASH taskId -> {ruleId,workerGroupId}
:matching:worker:index:<encoded-group>:<handler>       ZSET workerId -> internal coordinate
:matching:worker:index:<encoded-group>:<handler>:partitions
                                                      HASH workerId -> partition suffixes
:matching:worker:index:<encoded-group>:<handler>:partition:<digest>
                                                      ZSET workerId -> same coordinate
```

`encoded-group` is UTF-8 Base64 URL without padding; partition digests are SHA-1
of Handler-owned suffixes. Encoded namespaces make Group-local startup cleanup
unambiguous even for IDs containing separators or glob characters. These are
derived eligibility structures, never scheduling truth or per-Task caches.

Current shared index mechanics encode `countryCode * 2^43 + lastTakenMillis`,
where countryCode is base-26 A..Z (0..675) and time is relative to 2000-01-01 UTC.
All values are exact Redis doubles. Proof partitions use prefix zero. Initial
take time is zero; a facts update preserves existing low take time. Take rotates
the primary and all current partition memberships together. Index time has no
Worker lease or availability meaning.

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
membership recheck and exact dirty confirmation remain Kernel fences. Already
confirmed execution is not revoked by a later facts observation.

Startup rebuilds only enabled Group indexes with bounded SCAN/UNLINK and HSCAN
pages, before admission and Pacer start. Retained facts are the rebuild input;
malformed facts abort startup. There is no background matching consumer, queue,
index repair scan, lease registry or per-Task candidate publication.

## Bounded Query and Failure Semantics

A take accepts at most 100 selectors with a total demand of at most 100 IDs in
first-appearance order. Equal selectors share their actual Item demand. Country
queries merge bounded country heads by low take time; ANY samples bounded
members; explicit IDs are intersected with the selected Rule index. Sparse phone
queries read their partition directly, not a truncated country sample. One Lua
returns unique IDs across the batch and rotates take time. It does not overfetch
for a busy Worker or refill within the same round.

Kernel intersects with HOT and obtains initial holds, then calls one bounded
retain Lua on the same query. Membership loss or competing holds can yield fewer
assignments. Successfully held but rejected IDs remain excluded for that round
and expire naturally. Missing evidence never becomes ANY; infrastructure failures
reach the existing caller error/backoff boundary. Due work is rediscovered by
Kernel scheduling without Matching jobs or compensating releases.

Client command budgets, not throughput promises:

- 1..100 Task bindings: one HMGET per dispatch round.
- Each Task's indexed subset: one take Lua, plus zero or one retain Lua.
- Default ANY/IDs: no index command after the common binding read.
- 1..100 Worker replacements: one facts/index Lua; Platform patch: one Lua.

Kernel additionally owns HOT/hold, endpoint reads, confirmation and claim.

## Proof and Cutover

Matching unit tests own selector admission and fixed Handler pairing. Redis Owner
proves concurrent binding/facts updates, exact index projection, sparse phone
queries, post-hold membership change, corrupt metadata, isolated startup rebuild
and command budgets. Pacer tests own grouped demand, missing-binding behavior,
round exclusions and opaque fences. Runtime Boundary, Worker Dynamic Matching
and product proofs witness real execution after live facts changes.

Task/API and storage cutover retires the old modes, DSL, Rule definition HASH,
Match Demand and Candidate Cache. Stop processes for an explicitly selected
scope, clear only that scope with SCAN + UNLINK and rebuild Groups/Tasks/Workers
from configuration and source facts. No old-format runtime reader, alias, online
migration or automatic non-test cleanup is supplied. See [TESTING](../TESTING.md).
