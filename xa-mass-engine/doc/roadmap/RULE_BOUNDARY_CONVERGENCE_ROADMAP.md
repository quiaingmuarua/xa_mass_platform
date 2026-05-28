# Rule Boundary Convergence Roadmap

Status: active direction document. No implementation slices have landed from
this roadmap yet.

This roadmap narrows the rule boundary after the worker-runtime and storage
boundary convergence work. The current code already stores rule definitions in
`platform_infra/mass-storage-*`, but `xa-mass-engine` still exposes a broad
`RuleManager` that looks like a rule CRUD owner.

The target is not to remove rule-based matching from engine. The target is to
make the owner split explicit:

```text
control-plane rule definition
  -> mass-storage-api / mass-storage-memory / mass-storage-jdbc

rule admin and bootstrap
  -> server / SDK / sample-admin surfaces

rule evaluator registration
  -> runtime assembly contract

engine matching
  -> read active matching rules
  -> evaluate rules against WorkerMatchContext
  -> emit assignment diagnostics
```

Engine may consume rules. Engine must not become the rule catalog CRUD owner.

## Current Code Observations

- `RuleDefinition`, `RuleType`, and `RuleEvaluator` live under
  `platform_infra/mass-storage-api`.
- `RuleStorage` persists rule definitions, but it also carries evaluator
  registry methods:
  - `registerEvaluator(...)`
  - `getEvaluator(...)`
  - `getRegisteredEvaluatorTypes()`
  - `removeEvaluator(...)`
- `InMemoryRuleStorage` stores rule definitions in memory and registers
  `QLExpressRuleEvaluator`.
- `JdbcRuleStorage` persists rule definitions in `xa_rule` and keeps an
  in-process evaluator map.
- `xa-mass-engine.rules.RuleManager` is currently both:
  - a rule definition CRUD facade (`addDefaultRule`, `updateRule`,
    `deleteRule`, `clear`, etc.)
  - the matching-time rule evaluation service consumed by
    `RuleBasedTaskWorkerMatchingStrategy`
- `RuleManagerFactory` seeds default/project/loose rule sets by mutating a
  storage-backed manager.
- `RuleApiController` is read-only today (`GET /api/v1/runtime/rules`,
  `GET /api/v1/runtime/rules/meta`), but the path name says `runtime` even
  though rule definitions are control-plane storage truth.
- `MassSdkApplication.replaceDefaultRules(...)` and server/test bootstrap paths
  still use the broad rule operations surface for scenario setup.

## Boundary Decision

Rule definitions are control-plane truth.

Rule evaluation is an engine matching concern.

Evaluator registration is runtime assembly, not durable storage truth.

Implications:

- durable rule CRUD belongs outside `xa-mass-engine`
- engine matching should not call a CRUD-shaped `RuleManager`
- storage should not be forced to own evaluator lifecycle forever just because
  the first implementation kept evaluators next to definitions
- server/admin APIs should not use `/runtime/rules` for future write APIs
- default/sample rule seeding is bootstrap, not engine kernel behavior

## Target Shape

First stable target:

```text
RuleDefinitionStore
  -> add/update/delete/list rule definitions
  -> storage module responsibility

RuleEvaluatorRegistry
  -> register/lookup evaluator implementations
  -> runtime assembly responsibility

MatchingRuleSetProvider
  -> read active worker-matching rules
  -> may be backed by RuleDefinitionStore

MatchingRuleEvaluator
  -> evaluate RuleDefinition against WorkerMatchContext
  -> engine strategy dependency
```

Names may change during implementation, but the four roles should stay
separate.

Do not introduce a generic plugin framework. The default evaluator may remain
QLExpress. The important change is owner separation, not evaluator
extensibility.

## Non-Goals

1. No rule language rewrite.
2. No replacement of QLExpress in this roadmap.
3. No public rule-admin product design beyond preserving existing read and
   bootstrap behavior.
4. No cross-project rule scoping redesign unless the inventory proves current
   project/default semantics are already ambiguous.
5. No new matching strategy. `RuleBasedTaskWorkerMatchingStrategy` remains the
   default strategy while its rule dependency is narrowed.
6. No compatibility aliases for old broad manager methods after in-repo callers
   move.

## Slice RBC-0: Inventory Current Rule Callers

Goal: classify every rule call site before changing code.

Scope:

1. List all production and test callers of:
   - `RuleManager`
   - `RuleManagerFactory`
   - `RuleStorage`
   - `RuleOperations`
   - `/api/v1/runtime/rules`
2. Classify each caller as one of:
   - matching-time evaluation
   - admin/control-plane definition read
   - bootstrap/sample fixture setup
   - evaluator assembly
   - test-only convenience
3. Identify callers that mutate rule definitions through engine package types.
4. Identify whether any caller depends on evaluator registration being stored
   inside `RuleStorage`.
5. Produce a small inventory doc beside this roadmap.

Acceptance:

1. Every current rule caller has one classification.
2. The inventory names which call sites must move before `RuleManager` can be
   narrowed.
3. No behavior changes in this slice.

## Slice RBC-1: Define Narrow Rule Contracts

Goal: make the contract split explicit without moving behavior yet.

Scope:

1. Define the minimal matching-time surface needed by
   `RuleBasedTaskWorkerMatchingStrategy`:
   - read active matching rules
   - evaluate one rule against `WorkerMatchContext`
   - expose evaluator metadata for diagnostics only if still needed
2. Define the control-plane rule-definition surface separately from the
   matching surface.
3. Define evaluator registry ownership separately from durable rule storage.
4. Update docs to say rule definitions are control-plane storage truth and
   matching consumes a snapshot/provider, not CRUD.

Acceptance:

1. Engine matching can be described without CRUD verbs.
2. Rule definition storage can be described without evaluator lifecycle verbs.
3. No new generic plugin framework is introduced.

## Slice RBC-2: Narrow Engine Rule Usage

Goal: remove rule CRUD authority from engine matching dependencies.

Scope:

1. Replace `RuleBasedTaskWorkerMatchingStrategy`'s dependency on broad
   `RuleManager` with the narrow matching-time rule contract.
2. Move or delete in-engine methods that exist only as CRUD pass-throughs:
   - `addDefaultRule`
   - `addDefaultRules`
   - `removeDefaultRule`
   - `updateRule`
   - `deleteRule`
   - `clear`
3. Keep rule evaluation diagnostics equivalent.
4. Keep default rule evaluation behavior equivalent.

Acceptance:

1. Matching strategy no longer imports or depends on CRUD-shaped rule owner
   methods.
2. Existing matching tests still prove default rule evaluation and failed-rule
   diagnostics.
3. Existing SDK/server bootstrap paths still work through their own setup
   surface.

## Slice RBC-3: Move Bootstrap And Admin Writes Out Of Engine

Goal: rule writes enter through storage/admin/bootstrap surfaces, not engine.

Scope:

1. Replace production bootstrap writes that go through engine `RuleManager`
   with an explicit rule-definition setup path.
2. Keep sample/test bootstrap deterministic, but make it clear that bootstrap
   is not engine kernel behavior.
3. Keep `RuleApiController` read-only unless a separate admin API roadmap
   explicitly adds write endpoints.
4. Rename future write paths away from `/runtime/rules`.

Acceptance:

1. No production bootstrap path needs engine package rule CRUD methods.
2. `RuleApiController` remains read-only, or any write path is explicitly
   admin/control-plane named.
3. `doc/INTERNAL_API_REFERENCE.md` does not describe rule definitions as
   runtime truth.

## Slice RBC-4: Split Evaluator Registry From Rule Storage

Goal: stop treating evaluator lifecycle as durable storage behavior.

Scope:

1. Introduce a concrete evaluator registry contract or owner.
2. Move evaluator registration and lookup out of `RuleStorage`.
3. Keep `RuleStorage` focused on rule definition persistence.
4. Let in-memory and JDBC rule storage share the same evaluator registry where
   assembly requires it.

Acceptance:

1. `RuleStorage` no longer exposes evaluator registration methods.
2. In-memory and JDBC storage remain definition stores.
3. Rule evaluation still supports the current QLExpress evaluator.
4. Existing tests for registered evaluator metadata are updated to target the
   evaluator registry, not storage.

## Slice RBC-5: Guard And Proof

Goal: prevent rule CRUD from drifting back into engine.

Scope:

1. Add or update architecture guard tests:
   - matching strategy may depend on matching rule contracts only
   - engine must not expose CRUD-shaped rule manager methods as the matching
     dependency
   - future rule write endpoints must not live under `/runtime/rules`
2. Add focused proof that default rules still affect worker matching.
3. Add focused proof that rule definition persistence still works through
   memory and JDBC storage.

Acceptance:

1. Guard fails if engine matching imports a broad CRUD rule manager.
2. Guard fails if rule definition writes are added under runtime API routes.
3. Memory and JDBC rule definition tests pass.
4. Existing scheduling/rule diagnostics remain behaviorally equivalent.

## Implementation Order

Recommended order:

```text
RBC-0 -> RBC-1 -> RBC-2 -> RBC-3 -> RBC-4 -> RBC-5
```

Do not start by moving files. First classify call sites and define the contract
split. The risky part is not Java package movement; it is accidentally making
engine, storage, SDK, and server all share a different broad rule bucket.

## Verification Candidates

Initial commands to keep in the roadmap proof set:

```powershell
mvn -pl xa-mass-engine -am '-Dtest=RuleManagerTest,RuleConfigTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am '-Dtest=InMemoryRuleStorageTest,JdbcStorageH2Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-server -am '-Dtest=RuleApiControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The exact test list should be corrected in RBC-0 after the current rule test
inventory is complete.
