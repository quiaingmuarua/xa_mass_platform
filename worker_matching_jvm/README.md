# XA Mass Worker Matching JVM

Status: current Worker facts, allocation-rule and matching owner.

`worker_matching_jvm` is a Java 21 internal runtime module. It owns the facts
that describe Workers, the rules that describe matching intent, and the
interpretation that turns those two inputs into bounded Worker-ID evidence.
It does not select or lease a Worker.

## Owner Boundary

```text
Server writes Worker facts and Task/Item rules
  -> WorkerMatchingCatalog persistent truth

Kernel Pacer offers identity-only Match Demand
  -> Kernel exact-holds the admitted bounded Worker pool
  -> WorkerMatchingRuntime evaluates only that pool against facts and rules
  -> identity-only Match Evidence
  -> Kernel confirms the exact hold, applies priority and uniqueness, and claims
```

The split is deliberate:

| Owner | Owns |
| --- | --- |
| Worker Matching | Worker and Platform Properties, Task and Item rules, constraint semantics, bounded supplied-pool filtering |
| Kernel | Task/Item/Worker score, candidate deficit, priority, unique selection, exact Worker lease, Item claim, retry and finality |
| Server | public validation, ordered cross-owner writes, Runtime View composition and lifecycle assembly |
| Transport | complete Properties upload during Prepare and execution of an already-targeted Command |

`WorkerMatchRuntime` lives in `kernel_jvm` because it is the narrow port used
by Kernel policy. Its Demand records contain a bounded Worker-ID pool and the
Kernel hold deadline; Evidence returns only the matched subset under that same
deadline. They never contain a Rule, Property, Score, endpoint or Delivery
DTO.

## Persistent Catalog

`RedisWorkerMatchingCatalog` owns these keys under the configured
`xa_mass:<scope>` base:

```text
:matching:worker:facts:<workerGroupId>
:matching:worker:platform-properties:<workerGroupId>
:matching:task:rules
:matching:task:<taskId>:item-rules
```

Worker Prepare replaces the complete Worker Properties value and preserves
the independently written Platform Properties value. Platform patch requires
an existing Worker facts row. Task and Item rules are create-only: an exact
retry is unchanged and different content conflicts. Missing Kernel resources
leave inert orphan facts or rules; only a Kernel Pacer Demand can make them
participate in scheduling.

Rules use the existing finite constraint language. Roots are `workerId`,
`worker.*` and `platform.*`; operators are `$eq`/`$equal`, `$ne`, `$gt`,
`$gte`, `$lt`, `$lte`, `$in`, `$exists` and `$range`. All conditions are ANDed
and `{}` is unrestricted. Invalid or missing stored rules produce empty
evidence and a safe diagnostic; rules and Properties are never logged.

## Runtime

One resident virtual thread owns batch consumption:

```text
take first Demand
-> drain at most 99 more
-> load persisted rules and facts
-> evaluate
-> publish expiring single-use Evidence
```

The default Demand capacity and Evidence capacity are each 10,000. Batches,
Demand Worker pools and each Evidence candidate list are limited to 100.
Evidence expires with the Kernel hold, currently five seconds in the
production dispatch configuration.

PRECOMPUTED Task and ON_DEMAND Item Demands both contain a bounded Worker pool
observed by Kernel score policy. Matching loads facts only for those supplied
IDs, applies the persistent Task or Item Rule, and returns every match in that
pool. Requested counts, Task priority and cross-Task uniqueness remain Kernel
inputs and never enter Matching. There is no Group facts scan, Item cursor,
Properties index or matching-result cache in this cut.

Catalog runtime failures release pending Demand ownership without publishing
Evidence. A later Pacer round may offer the Demand again. An unexpected
`Error` ends the Matching runtime in `FAILED`; Server health reports DOWN and
does not silently restart it. Server restart discards transient Demand,
Evidence while preserving facts and rules in Redis.

## Evidence Semantics

Evidence proves only that a Worker in the supplied held pool matched the
persisted facts observed by Matching before the hold deadline. It is not
availability or a scheduling decision. Kernel must confirm that the exact
clean HOT hold is still active before selection or dispatch. A later
Properties update does not revoke already published Evidence; hold expiry,
single consumption and the exact Score fence bound the stale window.

## Non-Owners

This module must not import or own:

```text
Worker Score interpretation or transitions
CandidateWorkerCache
TaskItem claim, retry or finality
priority or cross-Task unique selection
DeliveryCommand / DeliveryReport
HTTP, Spring, Netty or Worker lifecycle
```

Build and owner tests:

```text
./gradlew :worker_matching_jvm:test
./gradlew :server_jvm:redisOwnerIntegrationTest
./gradlew :server_jvm:runtimeBoundaryIntegrationTest
```
