# Redis Runtime Key Proof Operator Roadmap

Status: proposed direction document.

This roadmap creates a dedicated tools module for proving Redis runtime
keyspace ownership, lifecycle, and residue. It is a prerequisite proof surface
for Redis key convergence work such as
[TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md](./TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md).

The target module is:

```text
tools/xa-mass-redis-runtime-proof
```

It is the Redis-runtime analogue of `xa-mass-trace`: an operator-facing read and
analysis surface over runtime artifacts. It does not own runtime truth and it
does not replace runtime, trace, engine, server, or SDK behavior proof.

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

`tools/xa-mass-redis-runtime-proof` owns only a read-only operator proof surface:

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
  the purpose is drift detection and those dependencies do not enter the CLI
  runtime classpath.

  may reference owner docs, owner manifests, and local proof specs, but must not
  require production runtime code to execute the CLI.
```

The module may define key-family proof specs as operator proof contracts. These
specs are not runtime truth. When a spec disagrees with production code, the
result is a proof failure or spec-update task, not a reason to make the tool a
runtime owner.

Specs must not become a second hand-written Redis key dictionary. Each spec
family needs at least one drift guard:

- an owner-maintained key-family manifest,
- an owner-maintained generated fixture,
- an owner baseline section plus a residue scan that finds current production
  key builders.

The tool reads those artifacts as proof inputs. It must not import production
runtime writer classes to discover key names at CLI runtime.

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
11. forbidden facts that must not appear in the value,
12. behavioral scenario that proves the key matters.

Forbidden-fact proof requires bounded value-shape capture. A snapshot must be
able to record shape without dumping business payloads:

- hash: field names, field count, selected redacted scalar samples when allowed,
- set/zset: member count, member token-shape samples, score range when useful,
- list/stream: length, entry field names, redacted sample envelope shape,
- string: byte length, optional digest, optional redacted structured field names
  when the value is a known JSON envelope.

Default snapshot mode must not emit raw task payloads, `payloadJson`, API keys,
tokens, credentials, secrets, authorization material, or full business values.
Specs may define `requiredFields`, `forbiddenFields`, and `allowedSampleFields`.
The assertion path must be able to fail on forbidden field names without reading
or printing the forbidden value itself.

The following must never pass as proof by themselves:

- local key count,
- local Redis memory use,
- physical key existence,
- a Java unit test that copies fields,
- an assertion that a key matches a string path,
- a scan with no owner/query/lifecycle classification.

## Target CLI Shape

Initial commands:

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

The first implementation should not run destructive Redis cleanup. If a future
operator cleanup command is needed, it must get a separate roadmap because it
would change the module from read-only proof to mutation tooling.

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
valueShapePolicy
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
logicalItemCount
ttlPolicy
sampleKeys
valueShape
requiredFields
forbiddenFields
redactionPolicy
ownerManifest
productionQuery
lifecycleRule
derivedFrom
forbiddenFacts
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
13. Snapshot output must be redacted by default and must not print raw payload,
    token, secret, credential, or authorization values.
14. Production CLI code must not import runtime writer modules to discover key
    builders.

## Non-Goals

- No Redis cleanup command in the first roadmap.
- No production reconciliation or recovery loop.
- No Redis HA, cluster, failover, partition, or process-kill proof.
- No replacement for `xa-mass-trace` analyzers.
- No replacement for engine deterministic proof or server E2E proof.
- No admin HTTP workflow or operator auth support.
- No public contract for Redis physical key names.
- No change to runtime key writers in the first slice.

## Do Not Start With

Do not start by optimizing Redis memory, deleting keys, or adding a cleanup
command.

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
5. Define bounded value-shape capture and redaction rules.
6. Define owner-manifest or fixture drift guard requirements.
7. Cross-link dependent Redis key convergence roadmaps.

Acceptance:

1. The roadmap states that admin-cli remains HTTP-only.
2. The roadmap states that this module is read-only in the first roadmap.
3. The roadmap defines why local key count/memory is not proof.
4. The roadmap defines minimum report fields and key-family proof fields.
5. The roadmap defines how forbidden facts can be checked without dumping raw
   Redis values.
6. The roadmap defines how specs stay aligned with owner key builders without
   production-scope dependency on runtime writer modules.
7. `TRANSPORT_PRESENCE_REDIS_KEY_CONVERGENCE_ROADMAP.md` references this roadmap
   as proof infrastructure.

## RRKP-1: Maven Module Skeleton

Goal: create the read-only tool module.

Scope:

1. Add `tools/xa-mass-redis-runtime-proof`.
2. Add the module to the root Maven `pom.xml`.
3. Add module README with:
   - role,
   - non-goals,
   - command list,
   - relationship to `xa-mass-trace` and admin-cli,
   - proof grammar summary.
4. Add a CLI entrypoint.
5. Add a minimal test proving the CLI can parse commands without Redis.

Acceptance:

1. `mvn -pl tools/xa-mass-redis-runtime-proof -am test` passes.
2. The module does not depend on `xa-mass-engine`, `xa-mass-server`, SDK
   modules, `platform_infra/mass-runtime-redis`, `transport/transport_runtime`,
   or transport adapter implementation modules in production scope.
3. The root module list includes only this new tool module change.
4. The README explicitly says the module is read-only proof tooling.
5. Any test-scope dependency on owner modules is justified as fixture/drift
   proof and does not enter the CLI runtime classpath.

## RRKP-2: Snapshot And Classify Atoms

Goal: replace temporary Redis scripts with reusable read-only atoms.

Scope:

1. Implement `snapshot`:
   - requires `--namespace`,
   - uses `SCAN`, not `KEYS`,
   - captures type, TTL/PTTL, logical size, optional memory usage, and sample
     key names,
   - captures bounded value shape by Redis type:
     - hash field names and field count,
     - set/zset/list/stream member or entry shape samples with redaction,
     - string length, digest, and optional redacted JSON field names,
   - rejects raw-value dump unless a future roadmap explicitly adds an unsafe
     forensic mode,
   - emits JSON.
2. Implement `classify`:
   - reads snapshot JSON,
   - applies key-family specs,
   - emits classified, unknown, and residue groups.
3. Implement `assert`:
   - fails unknown keys in strict mode,
   - fails key families missing owner/query/lifecycle classification,
   - fails forbidden fact markers.
4. Add tests using generated snapshot fixtures, not a live Redis dependency.

Acceptance:

1. Snapshot requires explicit namespace filters.
2. Classification can run offline from a JSON snapshot.
3. Strict assertion fails unknown key families.
4. Snapshot output can prove forbidden field-name presence or absence without
   printing forbidden values.
5. Tests cover:
   - known key,
   - unknown key,
   - forbidden fact marker,
   - redacted hash field-name capture,
   - raw payload/token/secret value suppression,
   - derived key without canonical owner,
   - no namespace supplied.

Suggested verification:

```powershell
mvn -pl tools/xa-mass-redis-runtime-proof -am test
```

## RRKP-3: First Consumer Key-Family Specs

Goal: encode the first proof specs needed by the transport presence key
convergence roadmap without blocking on the full task/result Redis keyspace.

Scope:

1. Add first-batch specs for transport presence key families:
   - `route-presence`,
   - `worker`,
   - `route`,
   - `worker-routes`,
   - `routes`,
   - `workers`.
2. Add first-batch worker registry boundary specs:
   - group slots,
   - group heartbeat deadlines,
   - candidate buckets,
   - worker bucket membership,
   - worker group map,
   - task-worker active count.
3. Add owner-manifest or generated-fixture drift guards for the first-batch
   specs. The manifest may live in the owner module or in this tool module, but
   it must name its owner source and verification scan.
4. Add task work/result runtime only as deferred family-level placeholders:
   - ready/delayed/lease/counter groups,
   - stable-final result rows,
   - result barriers or repair anchors where currently identifiable.
5. Mark any uncertain family as `unknown-current` or `needs-owner-review`, not
   as proven.

Acceptance:

1. Every spec names owner, classification, production query, lifecycle rule,
   and forbidden facts.
2. Every first-batch spec names its owner-manifest, generated fixture, or
   owner-doc/residue-scan drift guard.
3. The transport presence specs encode current known gaps:
   - `workers` is write-only unless a reader is proven,
   - `worker-routes` must either feed `findOwners(workerId)` or become residue,
   - `worker:{workerId}` is projection/cache, not canonical route-owner truth.
4. Worker registry specs preserve `group:{groupId}:slots` as canonical current
   worker aggregate.
5. Task/result key families remain placeholders unless a separate TaskWork or
   TaskResult Redis key convergence roadmap promotes them.
6. Uncertain task/result key families are not promoted to proven status.

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
   smoke runs, or manual commands.

Minimum scenario manifest fields:

```text
scenarioId
producerType
producerId
producerCommand
namespacePrefixes
startedAt
endedAt
beforeSnapshot
afterSnapshot
expectedFamilyDeltas[]
externalProofPaths[]
traceAnalyzerResult
proofStatus
```

Acceptance:

1. Diff reports added/removed/changed key families and logical item deltas.
2. Scenario reports fail when required key-family deltas are missing.
3. Scenario reports can include a `xa-mass-trace` analyzer result as
   observational proof but do not parse raw logs.
4. No scenario report claims behavior without a named behavior-producing input.
5. Scenario reports fail if the manifest omits producer id, namespace, expected
   family deltas, or proof status.
6. Task/result runtime scenarios are not required for the first TPRK-enabling
   implementation.

## RRKP-5: Transport Presence Consumer Integration

Goal: make TPRK use the module instead of temporary scripts.

Scope:

1. Add a sample command sequence for transport presence key proof.
2. Add fixture snapshots for:
   - empty namespace,
   - one online polling route,
   - multi-route worker,
   - stale/offline/pruned route.
3. Update TPRK verification to call this module for key-family proof.
4. Keep production TPRK code changes separate from this tools roadmap.

Acceptance:

1. TPRK can use `snapshot`, `classify`, `diff`, and `scenario` reports as its
   key-existence proof surface.
2. Temporary scripts are no longer required for TPRK review.
3. The report flags `workers` and Redis `findOwners`/`worker-routes` gaps until
   production code resolves them.

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

Acceptance:

1. Docs say Redis key proof is not a replacement for runtime behavior proof.
2. Docs say Redis key proof is a companion for runtime truth owner review and
   residue detection.
3. No registry row claims Redis key proof alone as primary behavior proof.

## Suggested Implementation Order

1. RRKP-0 roadmap and TPRK dependency link.
2. RRKP-1 Maven module skeleton.
3. RRKP-2 snapshot/classify/assert atoms.
4. RRKP-3 first consumer key-family specs.
5. RRKP-4 diff/scenario reports.
6. RRKP-5 TPRK consumer integration.
7. RRKP-6 proof registry/testing index placement.

## Roadmap Completion Criteria

This roadmap is complete only when:

1. `tools/xa-mass-redis-runtime-proof` exists as a Maven module.
2. It can snapshot Redis keyspace under explicit namespace filters.
3. It can classify first-batch Redis runtime key families by owner and proof
   status, while marking deferred task/result families as placeholders or
   `needs-owner-review`.
4. It can fail unknown/residue/forbidden key families in strict mode.
5. It can check forbidden field names through bounded redacted value-shape
   capture.
6. First-batch specs are tied to owner manifests, generated fixtures, or
   owner-doc/residue-scan drift guards.
7. It can produce diff and scenario reports from before/after snapshots.
8. TPRK can use it as the key-existence proof surface.
9. Active docs explain that Redis key proof is support proof, not behavior
   proof by itself.
10. Focused module tests pass.

## Open Decisions

1. Whether the first implementation should use only built-in specs or allow
   external JSON spec files from the command line.
2. Whether live Redis snapshot should include `MEMORY USAGE` by default or only
   behind an explicit flag.
3. Whether future scenario command execution belongs in this module or remains
   in `xa-mass-testing` with this module only assembling reports.
4. Whether task/result runtime key specs should stay family-level until a
   separate TaskWork/TaskResult Redis key convergence roadmap exists.
