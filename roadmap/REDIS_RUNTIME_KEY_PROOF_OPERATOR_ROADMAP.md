# Redis Runtime Key Proof Runner Roadmap

Status: deferred successor roadmap; do not implement before transport presence
and worker runtime Redis key convergence produce stable key-family boundaries.

This roadmap creates a dedicated tools module for proving Redis runtime
keyspace ownership, lifecycle, and residue after the runtime Redis key model is
reasonable enough to codify. It is not a prerequisite for
[TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md](./TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md).
TPRK comes first; this proof runner follows once transport presence and worker
runtime Redis key families have stable owners, callers, and lifecycle rules.

The target module is:

```text
tools/xa-mass-redis-runtime-proof
```

It is the Redis-runtime analogue of `xa-mass-trace`: a proof-runner and analysis
surface over runtime artifacts. It does not own runtime truth, does not provide
a general Redis CLI, and does not replace runtime, trace, engine, server, or SDK
behavior proof.

## Current Facts

Repository truth-layer rules already classify Redis as runtime state for:

- task ready/delayed queues,
- active leases and retry timing,
- task and worker counters,
- worker registry slot/admission state,
- transport worker presence / route-owner view,
- transport dispatch handoff,
- transport result and dispatch-failure inboxes,
- Redis-backed result runtime rows and barriers.

Current proof gap:

- Redis keyspace checks are performed with ad hoc scripts or direct test
  assertions.
- Ad hoc scripts can inventory one local Redis instance, but they do not create
  a reusable proof surface.
- Key count and memory usage do not prove that a key family should exist.
- Transport presence and worker runtime key convergence now require key-family
  proof: owner, production query, lifecycle, derivation, and residue behavior.

Current ordering decision:

- Temporary Redis inventory/probe scripts are acceptable during key convergence
  when they are implemented in Python or Node, are replayable, use bounded
  namespace `SCAN`, and avoid Bash-only pipelines.
- This roadmap must not codify unstable or known-unreasonable Redis key
  structures. If TPRK or worker-runtime convergence changes canonical key
  families, RRKP specs should be created after those decisions land.
- The proof runner focuses on key structure: key family, Redis type,
  cardinality/length, TTL/PTTL, namespace, and lifecycle deltas. Item payload,
  task/result field schema, worker declaration schema, and value semantics
  remain behavior-test or owner-test concerns.

Existing module boundaries:

- `tools/xa-mass-admin-cli` calls server HTTP APIs and must not become a direct
  Redis runtime inspector.
- `xa-mass-trace` queries canonical trace artifacts and scenario analyzers; it
  does not read Redis runtime keyspace.
- `platform_infra/mass-runtime-redis` owns Redis-backed `TaskWorkRuntime`,
  `TaskResultRuntime`, and `WorkerRegistry` implementation truth.
- `transport/transport_runtime` owns Redis-backed transport presence and
  transport queues.

## Owner Review

Redis runtime key truth belongs to the runtime owners that write it:

- `platform_infra/mass-runtime-redis` for `TaskWorkRuntime`,
  `TaskResultRuntime`, and `WorkerRegistry` keyspaces.
- `transport/transport_runtime` for transport presence, dispatch handoff,
  delivery, result, and dispatch-failure inbox keyspaces.

`tools/xa-mass-redis-runtime-proof` owns only a read-only proof surface:

- snapshot Redis keyspace,
- classify keys by owner/spec,
- compare before/after lifecycle deltas,
- validate key existence rules,
- produce machine-readable proof reports.

It must not become:

- a Redis runtime implementation,
- a cleanup tool,
- a production recovery loop,
- a server/admin HTTP client,
- a general Redis CLI or Redis admin shell,
- a trace analyzer replacement,
- a source of public SDK/server key contracts.

## Boundary Decision

Create a new Maven module under `tools/`, separate from `xa-mass-admin-cli`.

Target dependency rule:

```text
tools/xa-mass-redis-runtime-proof
  may depend on:
    lettuce-core
    jackson-databind
    test libraries

  production scope must not depend on:
    xa-mass-engine
    xa-mass-server
    sdk modules
    platform_infra/mass-runtime-redis
    transport/transport_runtime
    transport adapter implementations

  test scope may use fixtures or generated examples from owner modules only when
  the purpose is drift detection and those dependencies do not enter the proof
  runner runtime classpath.

  may reference owner docs, owner manifests, and local proof specs, but must not
  require production runtime code to execute the proof runner.
```

The module may define key-family proof specs as proof contracts. These specs are
not runtime truth. When a spec disagrees with production code, the result is a
proof failure or spec-update task, not a reason to make the tool a runtime
owner.

Specs must not become a second hand-written Redis key dictionary. Each spec
family needs at least one drift guard:

- an owner-maintained key-family manifest,
- an owner-maintained generated fixture,
- an owner baseline section plus a residue scan that finds current production
  key builders.

Minimum owner manifest fields:

```text
familyId
ownerModule
namespacePattern
keyPattern
redisType
classification
writerSymbol
readerSymbol
productionQuery
lifecycleRule
derivedFrom
driftSource
status
```

The tool reads those artifacts as proof inputs. It must not import production
runtime writer classes to discover key names at proof-runner runtime.

Specs are scenario-driven. A key family becomes proven only when a current
consumer roadmap or behavior-producing scenario needs that family and supplies
owner, lifecycle, and drift evidence. Families discovered without such evidence
stay `unknown-current`, `needs-owner-review`, or placeholder entries.

## Proof Grammar

A Redis key family passes key-existence proof only when the report can name:

1. owner module,
2. namespace pattern,
3. physical key pattern,
4. Redis type,
5. classification:
   - canonical runtime truth,
   - derived lookup index,
   - derived cleanup index,
   - diagnostic/read projection,
   - bounded residue,
   - unknown/residue to delete,
6. production query or lifecycle operation served,
7. writer owner,
8. reader owner,
9. cleanup or expiry rule,
10. rebuild or invalidation rule if derived,
11. forbidden key families, key patterns, or owner classifications,
12. behavioral scenario that proves the key matters.

Snapshot proof is structural. It must not inspect item fields, payload schemas,
handler envelopes, business values, or JSON/member field names. Those contracts
belong to engine, transport, trace, or scenario tests.

The snapshot layer may record only key-level and Redis-type structural metadata:

- key count and sample key names,
- Redis type,
- TTL/PTTL, including the current `no-expiry` state when no TTL is set,
- type-specific length/cardinality: `HLEN`, `SCARD`, `ZCARD`, `LLEN`, `XLEN`,
  or string byte length,
- optional Redis `MEMORY USAGE` when explicitly enabled.

Default snapshot mode must not emit raw task payloads, item payload fields,
hash field names, set/zset members, list entries, stream entry fields, string
values, `payloadJson`, API keys, tokens, credentials, secrets, authorization
material, or full business values. Specs may define required/forbidden key
families and structural metric expectations, but not required/forbidden item or
value fields.

The following must never pass as proof by themselves:

- local key count,
- local Redis memory use,
- physical key existence,
- a Java unit test that copies fields,
- an assertion that a key matches a string path,
- Redis value field inspection,
- a scan with no owner/query/lifecycle classification.

## Target Proof Runner Surface

The module does not provide a Redis CLI obligation. It may expose a minimal
proof runner command surface so CI, local verification, and dependent roadmaps
can produce deterministic proof artifacts.

Initial proof verbs:

```text
snapshot
  read Redis keyspace under one or more namespace prefixes and emit JSON

classify
  apply key-family specs to a snapshot and emit known/unknown/residue groups

diff
  compare two snapshots and emit lifecycle deltas

assert
  fail when required key-family proof rules are violated

scenario
  combine a named scenario manifest, before/after snapshots, optional command
  metadata, and optional trace analyzer output into one proof report
```

The first implementation should not expose arbitrary Redis query functions or
run destructive Redis cleanup. If future Redis mutation or cleanup support is
needed, it must get a separate roadmap because it would change the module from
read-only proof to mutation tooling.

## Report Model

Each report should be machine-readable JSON and human-readable text.

Minimum JSON fields:

```text
toolVersion
redisEndpoint
db
capturedAt
namespacePrefixes
summary
keyFamilies[]
unknownKeys[]
residueFindings[]
assertions[]
ownerManifests[]
snapshotPolicy
scenario
verificationInputs
```

`keyFamilies[]` must include:

```text
familyId
owner
classification
keyPattern
redisType
keyCount
structuralCount
lengthMetrics
ttlPolicy
sampleKeys
noValueCapturePolicy
requiredKeyFamilies
forbiddenKeyFamilies
forbiddenKeyPatterns
ownerManifest
productionQuery
lifecycleRule
derivedFrom
forbiddenClassifications
status
```

## Hard Rules

1. The module is read-only in the first roadmap. It must not mutate Redis.
2. The module must not become part of production server, SDK, engine, or
   transport runtime execution.
3. The module must not be embedded in `tools/xa-mass-admin-cli`.
4. Key count and memory use are inventory evidence only, never proof of key
   reasonableness.
5. Unknown keys fail the strict assertion mode unless explicitly allowed by a
   scenario manifest.
6. A retained key family needs owner, query, lifecycle, and derivation proof.
7. A derived key must name its canonical owner and rebuild/invalidation rule.
8. No report may claim runtime behavior unless it names the behavior-producing
   scenario or external test run.
9. No report may claim trace proof unless it references a `xa-mass-trace`
   validation/analyzer result.
10. Namespace filters are mandatory. The tool must not scan all Redis keys by
    default.
11. Test fixtures must use isolated namespace prefixes and clean them
    explicitly.
12. Physical Redis key names remain implementation artifacts. They must not leak
    into public SDK/server contracts.
13. Snapshot output must avoid value capture by default and must not print raw
    payload, token, secret, credential, or authorization values.
14. Production proof-runner code must not import runtime writer modules to
    discover key builders.
15. Key-family specs must not become a second hand-written Redis key dictionary.
    Specs should come from an owner-module manifest or generated owner fixture
    by default. A tool-local spec is allowed only when paired with an automated
    source drift guard that fails CI when the owner keyspace changes.
16. Snapshot proof needs one isolated live Redis smoke. Generated fixtures are
    sufficient for offline classify/assert behavior, but not for proving the
    proof-runner entry can scan, filter, inspect type/length/TTL metadata, avoid
    value capture, and clean up a real Redis namespace.
17. No key family may be marked proven only from closed-room specs. A proven
    family needs a current scenario or consumer roadmap that produces or
    consumes the key family.

## Non-Goals

- No Redis cleanup or mutation support in the first roadmap.
- No production reconciliation or recovery loop.
- No Redis HA, cluster, failover, partition, or process-kill proof.
- No replacement for `xa-mass-trace` analyzers.
- No replacement for engine deterministic proof or server E2E proof.
- No admin HTTP workflow or operator auth support.
- No public contract for Redis physical key names.
- No change to runtime key writers in the first slice.
- No item payload, handler envelope, JSON field, or Redis value-schema proof.

## Do Not Start With

Do not start by optimizing Redis memory, deleting keys, or adding cleanup or
mutation support.

The first useful work is to define the proof grammar and build a read-only
snapshot/classification module. Production key convergence roadmaps can then use
that module as a stable proof surface.

## RRKP-0: Proof Grammar And Module Boundary

Goal: lock the module purpose and proof contract before code.

Scope:

1. Record this roadmap.
2. Define the target module name and read-only boundary.
3. Define the first key-family proof grammar.
4. Decide how this module relates to:
   - `tools/xa-mass-admin-cli`,
   - `xa-mass-trace`,
   - `platform_infra/mass-runtime-redis`,
   - `transport/transport_runtime`.
5. Define structural snapshot capture and no-value-capture rules.
6. Define owner-manifest or fixture drift guard requirements, including the
   minimum owner manifest schema.
7. Cross-link dependent Redis key convergence roadmaps.

Acceptance:

1. The roadmap states that admin-cli remains HTTP-only.
2. The roadmap states that this module is read-only in the first roadmap.
3. The roadmap defines why local key count/memory is not proof.
4. The roadmap defines minimum report fields and key-family proof fields.
5. The roadmap states that item/value field proof belongs to behavior/scenario
   tests, not this proof runner.
6. The roadmap defines how specs stay aligned with owner key builders without
   production-scope dependency on runtime writer modules.
7. The roadmap defines the minimum owner manifest schema for first-batch specs.
8. `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` references this roadmap
   as proof infrastructure.

## RRKP-1: Maven Module Skeleton

Prerequisite: TPRK has converged transport presence key families enough that
the specs would not preserve known residue as a second truth track.

Goal: create the read-only tool module.

Scope:

1. Add `tools/xa-mass-redis-runtime-proof`.
2. Add the module to the root Maven `pom.xml`.
3. Add module README with:
   - role,
   - non-goals,
   - proof verb list,
   - relationship to `xa-mass-trace` and admin-cli,
   - proof grammar summary.
4. Add a minimal proof runner entrypoint for CI/local verification.
5. Add a minimal test proving the proof runner can parse bounded proof verbs
   without Redis.
6. Add CI ownership for the new module:
   - offline proof-runner/fixture tests must run in `.github/workflows/maven.yml`
     under the proof-credibility path,
   - workflow path filters must include `tools/xa-mass-redis-runtime-proof/**`.

Acceptance:

1. `./mvnw -pl tools/xa-mass-redis-runtime-proof -am test` passes.
2. The module does not depend on `xa-mass-engine`, `xa-mass-server`, SDK
   modules, `platform_infra/mass-runtime-redis`, `transport/transport_runtime`,
   or transport adapter implementation modules in production scope.
3. The root module list includes only this new tool module change.
4. The README explicitly says the module is read-only proof tooling.
5. Any test-scope dependency on owner modules is justified as fixture/drift
   proof and does not enter the proof runner runtime classpath.
6. CI runs the module's offline skeleton/fixture tests and proof summaries
   identify Redis key proof as support proof rather than runtime behavior proof.
7. RRKP-1 does not require a live Redis snapshot smoke; that belongs to RRKP-2
   after `snapshot` exists.

## RRKP-2: Snapshot And Classify Atoms

Goal: create reusable read-only atoms after current Redis key convergence has
identified stable key families.

Scope:

1. Implement `snapshot`:
   - requires `--namespace`,
   - uses `SCAN`, not `KEYS`,
   - captures type, TTL/PTTL, key count, type-specific length/cardinality,
     optional memory usage, and sample key names,
   - records current no-expiry behavior explicitly when TTL is absent,
   - never captures hash field names, set/zset members, list entries, stream
     fields, string values, item payload fields, or JSON field names,
   - rejects raw-value or field-name dump modes,
   - emits JSON.
2. Implement `classify`:
   - reads snapshot JSON,
   - applies key-family specs,
   - emits classified, unknown, and residue groups.
3. Implement `assert`:
   - fails unknown keys in strict mode,
   - fails key families missing owner/query/lifecycle classification,
   - fails forbidden key families, key patterns, or owner classifications.
4. Add tests using generated snapshot fixtures, not a live Redis dependency.
5. Add one isolated live Redis snapshot smoke:
   - uses test harness or test fixture code to create keys under a unique
     namespace,
   - invokes the public proof-runner `snapshot` path against Redis,
   - proves namespace filtering, SCAN-based traversal, type/length/TTL capture,
     no value capture, and explicit test-harness cleanup,
   - does not use `KEYS` or scan unrelated namespaces,
   - does not expose setup, cleanup, delete, or mutation as proof-runner verbs,
   - leaves classify/assert behavior covered by fixture-based tests.

Acceptance:

1. Snapshot requires explicit namespace filters.
2. Classification can run offline from a JSON snapshot.
3. Strict assertion fails unknown key families.
4. Snapshot output proves structural metadata only and does not expose item,
   payload, or Redis value fields.
5. Tests cover:
   - known key,
   - unknown key,
   - forbidden key family or key pattern,
   - type/length/TTL capture, including current no-expiry behavior,
   - raw payload/token/secret value non-capture,
   - derived key without canonical owner,
   - no namespace supplied.
6. A Redis-backed smoke proves the real `snapshot` entry against an isolated
   namespace, including test-harness-owned cleanup and no value capture.
7. The smoke or Redis-client instrumentation proves snapshot traversal uses
   SCAN semantics and never needs `KEYS`.
8. The proof runner remains read-only; Redis writes/deletes for the live smoke
   are owned by test harness setup/cleanup code, not by proof-runner commands.

Suggested verification:

```powershell
.\mvnw.cmd -pl tools/xa-mass-redis-runtime-proof -am test
.\mvnw.cmd -pl tools/xa-mass-redis-runtime-proof -am -Dtest=RedisRuntimeProofLiveSnapshotSmokeTest test
```

## RRKP-3A: Transport Presence First Consumer Specs

Goal: encode the first proof specs needed by the transport presence key
convergence roadmap without blocking on worker registry or full task/result
Redis keyspace.

Scope:

1. Add first-batch specs for transport presence key families:
   - `route-presence`,
   - `worker`,
   - `route`,
   - `worker-routes`,
   - `routes`,
   - `workers`.
2. Add owner-manifest or generated-fixture drift guards for transport presence
   specs. The default source is the transport owner module. A tool-local spec
   must include an automated source drift guard that checks the owner keyspace
   source or generated fixture and fails CI on drift.
3. Mark any uncertain family as `unknown-current` or `needs-owner-review`, not
   as proven.

Acceptance:

1. Every spec names owner, classification, production query, lifecycle rule,
   forbidden key families/patterns/classifications, and the scenario or
   consumer roadmap that needs the family.
2. Every transport presence spec names its owner-module manifest, generated
   fixture, or automated owner-source drift guard.
3. The transport presence specs encode current known gaps:
   - `workers` is write-only unless a reader is proven,
   - `worker-routes` must either feed `findOwners(workerId)` or become residue,
   - `worker:{workerId}` is projection/cache, not canonical route-owner truth.
4. No task work/result runtime placeholder is added to RRKP-3A.
5. RRKP-3A may encode the transport presence shape that TPRK converged, but it
   must not be implemented early merely to freeze current residue.

## RRKP-3B: Worker Registry Boundary Specs

Goal: add worker registry key-family specs as the next consumer without making
them a prerequisite for transport presence convergence.

Scope:

1. Add first-batch worker registry boundary specs:
   - group slots,
   - group heartbeat deadlines,
   - candidate buckets,
   - worker bucket membership,
   - worker group map,
   - task-worker active count.
2. Preserve `group:{groupId}:slots` as canonical current worker aggregate.
3. Mark candidate buckets, membership sets, and active counts as derived views
   unless current code proves they are canonical truth.
4. Require owner-module manifests or generated owner fixtures from the worker
   runtime/Redis owner. Tool-local specs must include automated owner-source
   drift guards and CI failure on drift.
5. Do not add task/result runtime key families to RRKP-3B; follow the Deferred
   Specs section instead.

Acceptance:

1. Every worker registry spec names owner, classification, production query,
   lifecycle rule, derivation source, forbidden key families/patterns/
   classifications, and the scenario or consumer roadmap that needs the family.
2. `group:{groupId}:slots` remains the canonical current worker aggregate in
   the spec and report language.
3. Derived worker registry key families name rebuild/invalidation rules.
4. Any tool-local worker registry spec has an automated owner-source drift
   guard.
5. RRKP-3B can be implemented after TPRK is unblocked; it is not required for
   RRKP-3A acceptance.

## Deferred Specs: Task Work And Result Runtime

Goal: keep non-consumer Redis families visible without turning RRKP-3A into a
closed-room key dictionary.

Deferred families:

- task ready/delayed/lease/counter groups,
- stable-final result rows,
- result barriers or repair anchors where currently identifiable.

Rules:

1. Deferred task/result families are not part of RRKP-3A acceptance.
2. They stay unproven until a TaskWork or TaskResult Redis key convergence
   roadmap names a scenario or consumer that needs them.
3. If discovered by snapshot before such a roadmap exists, they must be reported
   as `unknown-current`, `needs-owner-review`, or placeholder entries.
4. They must not be promoted to proven status by local key presence alone.

## RRKP-4: Diff And Scenario Reports

Goal: make lifecycle proof reusable across runtime scenarios.

Scope:

1. Implement `diff` over two snapshot JSON files.
2. Implement `scenario` report assembly from:
   - scenario id,
   - before snapshot,
   - after snapshot,
   - scenario manifest,
   - behavior-producing command or test metadata,
   - optional trace analyzer result path.
3. Add built-in scenario ids for first consumers:
   - `transport-presence-online-heartbeat-offline-prune`,
   - `transport-presence-multi-route-reconnect`,
   - `worker-registry-reserve-confirm-release`.
4. Keep broader task/result runtime scenarios deferred until their key-family
   specs are promoted by a TaskWork or TaskResult Redis key convergence roadmap:
   - `task-work-lease-expiry-redispatch`,
   - `result-runtime-stable-final-read`.
5. Scenario report generation must not execute the runtime scenario in the
   first implementation. It assembles evidence produced by existing tests,
   smoke runs, or manual proof runs.

Minimum scenario manifest fields:

```text
scenarioId
producerType
producerId
producerCommand
producerExitCode
producerArtifactPath
producerArtifactDigest
namespacePrefixes
redisDb
snapshotCommand
snapshotToolVersion
timestampSource
startedAt
endedAt
beforeSnapshot
afterSnapshot
expectedFamilyDeltas[]
externalProofPaths[]
traceAnalyzerResult
traceAnalyzerStatus
traceAnalyzerExitCode
proofStatus
```

Acceptance:

1. Diff reports added/removed/changed key families and structural key/count/
   TTL deltas.
2. Scenario reports fail when required key-family deltas are missing.
3. Scenario reports can include a `xa-mass-trace` analyzer result as
   observational proof but do not parse raw logs.
4. No scenario report claims behavior without a named behavior-producing input.
5. Scenario reports fail if the manifest omits producer id, namespace, expected
   family deltas, producer exit code, producer artifact digest, snapshot tool
   version, Redis db/namespace, timestamp source, or proof status.
6. Task/result runtime scenarios are not required for post-TPRK
   scenario-report integration.
7. Scenario reports remain artifact metadata unless the producer command,
   producer exit code, artifact digest, and optional trace analyzer status are
   present and consistent.

## RRKP-5A: Transport Presence Structural Integration

Goal: replace TPRK's temporary structural probes with this module only after
TPRK converges the transport presence keyspace.

Scope:

1. Add a sample proof-runner sequence based on the converged transport
   presence key model.
2. Add fixture snapshots for:
   - empty namespace,
   - one online polling route,
   - multi-route worker,
   - stale/offline/pruned route.
3. Update TPRK follow-up verification to call this module for `snapshot`,
   `classify`, and `assert` key-family proof.
4. Keep production TPRK code changes separate from this tools roadmap.

Acceptance:

1. TPRK's converged key families can be replayed through `snapshot`,
   `classify`, and `assert` as structural key-existence support proof.
2. Temporary Python/Node probes used during TPRK can be retired after their
   evidence is covered by this module.
3. The report must not preserve pre-convergence residue such as write-only
   `workers` or unused `worker-routes` as proven families unless TPRK resolves
   them with a named production query.
4. RRKP-5A does not require `diff` or `scenario`; those belong to RRKP-5B after
   RRKP-4 exists.

## RRKP-5B: Transport Presence Scenario Report Integration

Goal: make post-TPRK verification consume diff/scenario proof after the report
assembler exists.

Scope:

1. Update post-TPRK verification to include `diff` and `scenario` reports
   produced by RRKP-4.
2. Use behavior-producing transport presence evidence as the scenario input.
3. Keep broader worker-registry and task/result scenarios outside TPRK
   acceptance.

Acceptance:

1. Post-TPRK verification can use `diff` and `scenario` reports after RRKP-4 is
   implemented.
2. Transport presence scenario reports fail when expected key-family deltas are
   missing.
3. No transport presence scenario report claims runtime behavior without named
   behavior-producing evidence.

## RRKP-6: Proof Registry And Testing Index Placement

Goal: document where Redis key proof belongs in the project proof system.

Scope:

1. Update `doc/TESTING_INDEX.md` to classify this module as support proof for
   Redis runtime key ownership and residue.
2. Update `doc/PROOF_REGISTRY.md` only if a runtime invariant gains a new
   named Redis-key proof companion.
3. Update `platform_infra/mass-runtime-redis/README.md` and
   `transport/TRANSPORT_BOUNDARY_BASELINE.md` only with cross-links after the
   module exists.
4. Update CI workflows so the new module cannot drift outside protected
   verification:
   - `.github/workflows/maven.yml` proof-credibility runs offline module tests,
   - `.github/workflows/redis-runtime.yml` includes
     `tools/xa-mass-redis-runtime-proof/**` path filters and runs the live
     Redis snapshot smoke,
   - proof summary inputs include the tool module reports and label the result
     as support proof.

Acceptance:

1. Docs say Redis key proof is not a replacement for runtime behavior proof.
2. Docs say Redis key proof is a companion for runtime truth owner review and
   residue detection.
3. No registry row claims Redis key proof alone as primary behavior proof.
4. CI gates run offline tests and the Redis-backed snapshot smoke for
   `tools/xa-mass-redis-runtime-proof`.
5. CI proof summaries identify Redis key proof as support proof.

## Suggested Implementation Order

0. Complete or materially converge TPRK enough that transport presence key
   families have stable owners, callers, and lifecycle rules.
1. RRKP-0 roadmap dependency repair.
2. RRKP-1 Maven module skeleton.
3. RRKP-2 snapshot/classify/assert atoms.
4. RRKP-3A transport presence specs from the converged key model.
5. RRKP-3B worker registry boundary specs only after its key model is stable.
6. RRKP-4 diff/scenario reports.
7. RRKP-5A post-TPRK structural integration.
8. RRKP-5B post-TPRK scenario report integration after RRKP-4 exists.
9. RRKP-6 proof registry/testing index and CI placement.

## Roadmap Completion Criteria

This roadmap is complete only when:

1. `tools/xa-mass-redis-runtime-proof` exists as a Maven module.
2. It can snapshot Redis keyspace under explicit namespace filters.
3. It can classify first-batch Redis runtime key families by owner and proof
   status, while marking deferred task/result families as placeholders or
   `needs-owner-review`.
4. It can fail unknown/residue/forbidden key families in strict mode.
5. It can snapshot structural metadata without reading or emitting item,
   payload, member, entry, hash-field, or string values.
6. First-batch specs are tied to owner manifests, generated fixtures, or
   owner-doc/residue-scan drift guards.
7. It can produce diff and scenario reports from before/after snapshots.
8. Post-TPRK verification can use structural proof and scenario report proof
   without preserving the temporary Python/Node probes as a second proof
   surface.
9. Active docs explain that Redis key proof is support proof, not behavior
   proof by itself.
10. Focused module tests and the isolated live Redis snapshot smoke pass.
11. CI workflows run the module tests/smoke and collect proof summary reports.

## Open Decisions

1. Whether the first implementation should use only built-in specs or allow
   external JSON spec files as bounded proof-runner inputs.
2. Whether live Redis snapshot should include `MEMORY USAGE` by default or only
   behind an explicit flag.
3. Whether future scenario command execution belongs in this module or remains
   in `xa-mass-testing` with this module only assembling reports.
4. Whether task/result runtime key specs should stay family-level until a
   separate TaskWork/TaskResult Redis key convergence roadmap exists.
