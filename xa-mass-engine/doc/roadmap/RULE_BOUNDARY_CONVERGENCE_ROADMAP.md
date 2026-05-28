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
  -> runtime assembly contract owned by engine-side rule runtime assembly

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
- `RuleBasedTaskWorkerMatchingStrategy` only needs two methods today:
  - `getDefaultRules()`
  - `evaluate(rule, context)`
- `RuleManagerFactory` seeds default/project/loose rule sets by mutating a
  storage-backed manager.
- `xa-mass-sdk` `EngineConfig.getRuleManager()` creates the seeded manager via
  `RuleManagerFactory.getDefaultRuleManager(getRuleStorage())`.
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
- storage should not own evaluator lifecycle just because the first
  implementation kept evaluators next to definitions
- server/admin APIs should not use `/runtime/rules` for future write APIs
- default/sample rule seeding is bootstrap, not engine kernel behavior
- route compatibility is not a goal in this internal convergence roadmap; move
  in-repo callers to the corrected boundary and remove the old path

## Target Shape

First stable target:

```text
RuleDefinitionStore
  -> add/update/delete/list rule definitions
  -> module owner: mass-storage-api

RuleEvaluatorRegistry
  -> register/lookup evaluator implementations
  -> module owner: xa-mass-engine rule runtime assembly

MatchingRuleSetProvider
  -> read active worker-matching rules
  -> module owner: xa-mass-engine matching/rules boundary
  -> may be backed by RuleDefinitionStore

MatchingRuleEvaluator
  -> evaluate RuleDefinition against WorkerMatchContext
  -> module owner: xa-mass-engine matching/rules boundary
```

Names may change during implementation, but the four roles should stay
separate.

Do not introduce a generic plugin framework. The default evaluator may remain
QLExpress. The important change is owner separation, not evaluator
extensibility.

## Engine Dependency Convergence Review

The current `xa-mass-engine` production dependency shape is close, but not
clean.

Dependencies that remain justified:

- `xa-mass-base`: task, contract, result, assignment, and shared model truth.
- `mass-runtime-api`: task work runtime, result runtime, lease/counter records,
  and runtime contracts consumed by the engine kernel.
- `mass-storage-api`: storage contracts that the engine currently owns or
  projects through:
  - `TaskStorage` for stable task shell persistence
  - `TaskDetailStore` for bounded compatibility projection
  - projection enums/records used by `TaskWorkProjectionState`
  - rule definition model/provider types until the rule boundary is narrowed
- `xa-mass-worker-runtime`: worker registration, lifecycle, candidate,
  admission, and report surfaces used by assignment.
- `mass-trace-sink`: execution trace emission.

Dependencies that should converge:

- `mass-runtime-memory` is a production-scope residual dependency. Current main
  code only needs it because `TaskManager` convenience constructors instantiate
  `InMemoryTaskResultRuntime` directly. The engine kernel should accept an
  injected `TaskResultRuntime`; in-memory defaults should be assembled by
  server/SDK/test bootstrap. After that, `mass-runtime-memory` should become a
  test or assembly-surface dependency, not an engine production dependency.
- `mass-storage-api` remains acceptable as a contract dependency, but
  rule-specific storage access should shrink to a narrow
  `MatchingRuleSetProvider` / `RuleDefinitionStore` boundary. Matching should
  not import `RuleStorage` directly.
- `mass-storage-memory` is already test-scope and should stay out of engine
  production code.

Storage dependency rule for engine:

```text
engine may depend on storage contracts for current kernel truth
engine must not depend on storage implementations
engine matching must not depend on CRUD-shaped storage or manager APIs
```

## Non-Goals

1. No rule language rewrite.
2. No replacement of QLExpress in this roadmap.
3. No public rule-admin product design. Existing internal read/bootstrap routes
   may be renamed or removed when in-repo callers move.
4. No cross-project rule scoping redesign unless the inventory proves current
   project/default semantics are already ambiguous.
5. No new matching strategy. `RuleBasedTaskWorkerMatchingStrategy` remains the
   default strategy while its rule dependency is narrowed.
6. No compatibility aliases for old broad manager methods after in-repo callers
   move.
7. No storage implementation dependency from engine production code.

## Slice RBC-0: Inventory Current Rule Callers

Goal: classify every rule call site before changing code.

Scope:

1. List all production and test callers of:
   - `RuleManager`
   - `RuleManagerFactory`
   - `RuleStorage`
   - `RuleOperations`
   - `/api/v1/runtime/rules`
2. Record method-level usage for each caller, not only file-level usage.
3. Classify each caller as one of:
   - matching-time evaluation
   - admin/control-plane definition read
   - bootstrap/sample fixture setup
   - evaluator assembly
   - test-only convenience
4. Identify callers that mutate rule definitions through engine package types.
5. Identify whether any caller depends on evaluator registration being stored
   inside `RuleStorage`.
6. Record engine production dependencies that exist only for default
   construction, especially `mass-runtime-memory`.
7. Produce a small inventory doc beside this roadmap.

Acceptance:

1. Every current rule caller has one classification.
2. Every current rule caller lists the exact methods it invokes.
3. The inventory names which call sites must move before `RuleManager` can be
   narrowed.
4. The inventory names which engine dependencies are kernel contracts and which
   are assembly/default-constructor residues.
5. No behavior changes in this slice.

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
4. Declare the module owner for each new contract:
   - definition persistence contract
   - evaluator registry
   - matching rule-set provider
   - matching evaluator
5. Update docs to say rule definitions are control-plane storage truth and
   matching consumes a snapshot/provider, not CRUD.

Acceptance:

1. Engine matching can be described without CRUD verbs.
2. Rule definition storage can be described without evaluator lifecycle verbs.
3. Every new contract has a declared module owner.
4. No new generic plugin framework is introduced.

## Slice RBC-2: Split Evaluator Registry From Rule Storage

Goal: stop treating evaluator lifecycle as durable storage behavior before the
matching dependency is narrowed.

Scope:

1. Introduce a concrete evaluator registry contract or owner.
2. Move evaluator registration and lookup out of `RuleStorage`.
3. Keep `RuleStorage` focused on rule definition persistence.
4. Let in-memory and JDBC rule storage share the same evaluator registry where
   assembly requires it.
5. Update storage tests that currently assert evaluator metadata through
   `RuleStorage`.

Acceptance:

1. `RuleStorage` no longer exposes evaluator registration methods.
2. In-memory and JDBC storage remain definition stores.
3. Rule evaluation still supports the current QLExpress evaluator.
4. Existing tests for registered evaluator metadata target the evaluator
   registry, not storage.

## Slice RBC-3: Introduce Matching Rule Contracts

Goal: give matching a narrow dependency before removing the broad manager.

Scope:

1. Introduce `MatchingRuleSetProvider` or equivalent for active/default worker
   matching rules.
2. Introduce `MatchingRuleEvaluator` or equivalent for evaluating one rule
   against `WorkerMatchContext`.
3. Keep the implementation backed by existing rule definitions and evaluator
   registry.
4. Keep rule evaluation diagnostics equivalent.
5. Keep default rule evaluation behavior equivalent.

Acceptance:

1. The matching contract exposes no CRUD verbs.
2. The matching contract does not expose evaluator registration.
3. Existing matching tests still prove default rule evaluation and failed-rule
   diagnostics.

## Slice RBC-4: Narrow Engine Rule Usage

Goal: remove rule CRUD authority from engine matching dependencies.

Scope:

1. Replace `RuleBasedTaskWorkerMatchingStrategy`'s dependency on broad
   `RuleManager` with the narrow matching-time rule contracts.
2. Move or delete in-engine methods that exist only as CRUD pass-throughs:
   - `addDefaultRule`
   - `addDefaultRules`
   - `removeDefaultRule`
   - `updateRule`
   - `deleteRule`
   - `clear`
3. Remove `RuleManager` entirely if its remaining behavior is only a pass-
   through to the new contracts.
4. Remove direct matching dependency on `RuleStorage`.

Acceptance:

1. Matching strategy no longer imports or depends on CRUD-shaped rule owner
   methods.
2. Matching strategy imports only matching rule contracts and model types.
3. Existing matching tests still prove default rule evaluation and failed-rule
   diagnostics.
4. No compatibility aliases remain for removed manager methods.

## Slice RBC-5: Move Bootstrap And Admin Writes Out Of Engine

Goal: rule writes enter through storage/admin/bootstrap surfaces, not engine.

Scope:

1. Replace production bootstrap writes that go through engine `RuleManager`
   with an explicit rule-definition setup path.
2. Move `xa-mass-sdk` `EngineConfig.getRuleManager()` away from seeded
   `RuleManagerFactory` construction.
3. Move `MassSdkApplication.replaceDefaultRules(...)` and scenario setup to
   the explicit bootstrap/admin rule-definition path.
4. Keep sample/test bootstrap deterministic, but make it clear that bootstrap
   is not engine kernel behavior.
5. Keep or rename `RuleApiController` read APIs based on current console needs;
   do not preserve `/api/v1/runtime/rules` as a compatibility requirement.
6. Put any future rule write endpoint under an explicit admin/control-plane
   path, not `/runtime/rules`.

Acceptance:

1. No production bootstrap path needs engine package rule CRUD methods.
2. `RuleApiController` is either read-only or explicitly admin/control-plane
   named.
3. `doc/INTERNAL_API_REFERENCE.md` does not describe rule definitions as
   runtime truth.
4. In-repo callers use the corrected route or bootstrap surface; no old-path
   compatibility layer remains.

## Slice RBC-6: Guard And Proof

Goal: prevent rule CRUD and assembly residues from drifting back into engine.

Scope:

1. Add or update architecture guard tests:
   - matching strategy may depend on matching rule contracts only
   - engine must not expose CRUD-shaped rule manager methods as the matching
     dependency
   - future rule write endpoints must not live under `/runtime/rules`
   - engine production code must not depend on storage implementations
2. Add focused proof that default rules still affect worker matching.
3. Add focused proof that rule definition persistence still works through
   memory and JDBC storage.
4. Add dependency proof for `xa-mass-engine` production scope after the
   `mass-runtime-memory` constructor residue is removed.

Acceptance:

1. Guard fails if engine matching imports a broad CRUD rule manager.
2. Guard fails if rule definition writes are added under runtime API routes.
3. Guard fails if engine production code imports storage implementation
   packages.
4. Memory and JDBC rule definition tests pass.
5. Existing scheduling/rule diagnostics remain behaviorally equivalent.
6. `xa-mass-engine` production dependency review documents whether
   `mass-runtime-memory` is gone or still intentionally pending.

## Implementation Order

Recommended order:

```text
RBC-0 -> RBC-1 -> RBC-2 -> RBC-3 -> RBC-4 -> RBC-5 -> RBC-6
```

Do not start by moving files. First classify call sites and define the contract
split. The highest-risk parts are evaluator lifecycle ownership and engine
dependency direction, not Java package movement.

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

Dependency review command for the engine convergence slice:

```powershell
mvn -pl xa-mass-engine dependency:tree
```

The exact test list should be corrected in RBC-0 after the current rule test
inventory is complete.
