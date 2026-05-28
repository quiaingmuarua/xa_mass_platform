# Rule Boundary Convergence Roadmap

Status: active direction document.

Progress:

- RBC-0 inventory is documented in
  [`RULE_BOUNDARY_CONVERGENCE_INVENTORY.md`](RULE_BOUNDARY_CONVERGENCE_INVENTORY.md).
- RBC-1 contract notes are documented in
  [`RULE_BOUNDARY_CONTRACTS.md`](RULE_BOUNDARY_CONTRACTS.md).
- RBC-2 is implemented for the rule boundary: evaluator registry moved out of
  `RuleStorage`, matching contracts exist, and the concrete QLExpress rule
  evaluator moved to engine rule assembly. The remaining `xa-mass-base`
  `qlexpress4` dependency is classified as JSON-DSL boundary work, not rule
  evaluator work.
- RBC-3 is implemented for the matching strategy: `RuleBasedTaskWorkerMatchingStrategy`
  now depends on `MatchingRuleSetProvider` and `MatchingRuleEvaluator`, not the
  CRUD-shaped `RuleManager`. SDK assembly still gets those method references
  from `EngineConfig.getRuleManager()` until RBC-4 moves bootstrap/admin writes.

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

Compatibility task projection cleanup is related engine dependency work, but it
has different blast radius and should not block this rule boundary track. Track
that work in
[`PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md`](PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md).

## Current Code Observations

- `RuleDefinition`, `RuleType`, and `RuleEvaluator` live under
  `platform_infra/mass-storage-api`.
- `RuleStorage` now persists rule definitions only. Evaluator lifecycle has
  moved out of storage.
- `InMemoryRuleStorage` stores rule definitions in memory and no longer owns
  evaluator registration.
- `JdbcRuleStorage` persists rule definitions in `xa_rule` and no longer owns
  evaluator registration.
- The concrete QLExpress rule evaluator now lives in
  `xa-mass-engine.rules.QLExpressRuleEvaluator`.
- The `qlexpress4` third-party dependency is currently declared by
  `xa-mass-base` for JSON-DSL runtime code. That is a separate base-module
  boundary issue tracked in
  [`../../../xa-mass-base/JSON_DSL_BOUNDARY_CONVERGENCE_ROADMAP.md`](../../../xa-mass-base/JSON_DSL_BOUNDARY_CONVERGENCE_ROADMAP.md).
- `xa-mass-engine.rules.RuleManager` is currently both:
  - a rule definition CRUD facade (`addDefaultRule`, `updateRule`,
    `deleteRule`, `clear`, etc.)
  - the matching-time rule evaluation service consumed by
    `RuleBasedTaskWorkerMatchingStrategy`
- `RuleManager` now uses an engine-owned `RuleEvaluatorRegistry` for evaluator
  lookup instead of reading evaluators from `RuleStorage`.
- `RuleBasedTaskWorkerMatchingStrategy` now only accepts
  `MatchingRuleSetProvider` and `MatchingRuleEvaluator` for rule access.
- The SDK assembly path still obtains those method references from the broad
  `RuleManager`; moving that bootstrap/admin owner is RBC-4.
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
- Rule route naming is wired in more than the controller:
  - `ApiRouteAuthorizationCatalog`
  - `FrontendConsoleController`
  - `FrontendConsoleRoutingService`
  - related controller and routing tests
- `MassSdkApplication.replaceDefaultRules(...)` and server/test bootstrap paths
  still use broad rule-definition storage operations for scenario setup.
- `xa-mass-engine` has a production dependency on `mass-runtime-memory` only
  because `TaskManager` convenience constructors instantiate
  `InMemoryTaskResultRuntime` directly.

## Boundary Decision

Rule definitions are control-plane truth.

Rule evaluation is an engine matching concern.

Evaluator registration is runtime assembly, not durable storage truth.

QLExpress is a concrete evaluator implementation. It should not live in
storage implementations. The `xa-mass-base` QLExpress dependency is caused by
legacy JSON-DSL runtime code and is handled by the JSON-DSL boundary roadmap.

Implications:

- durable rule CRUD belongs outside `xa-mass-engine`
- engine matching should not call a CRUD-shaped `RuleManager`
- storage should not own evaluator lifecycle just because the first
  implementation kept evaluators next to definitions
- server/admin APIs should not use `/runtime/rules` for future write APIs
- default/sample rule seeding is bootstrap, not engine kernel behavior
- route compatibility is not a goal in this internal convergence roadmap; move
  in-repo callers to the corrected boundary and remove the old path
- engine should not depend on in-memory runtime implementations for production
  code defaults

## Target Shape

First stable target:

```text
RuleDefinitionStore
  -> add/update/delete/list rule definitions
  -> module owner: mass-storage-api

RuleEvaluatorRegistry
  -> register/lookup evaluator implementations
  -> module owner: xa-mass-engine rule runtime assembly, or a dedicated
     rule-runtime module if implementation coupling grows

MatchingRuleSetProvider
  -> read active worker-matching rules
  -> module owner: xa-mass-engine matching/rules boundary
  -> may be backed by RuleDefinitionStore

MatchingRuleEvaluator
  -> evaluate RuleDefinition against WorkerMatchContext
  -> module owner: xa-mass-engine matching/rules boundary

QLExpress evaluator implementation
  -> module owner: engine rule runtime assembly or dedicated evaluator module
  -> not storage implementation modules
```

Names may change during implementation, but the roles should stay separate.

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
- `mass-storage-api`: storage contracts that the engine currently consumes:
  - `TaskStorage` for stable task shell persistence
  - rule definition model/provider types until the rule boundary is narrowed
- `xa-mass-worker-runtime`: worker registration, lifecycle, candidate,
  admission, and report surfaces used by assignment.
- `mass-trace-sink`: execution trace emission.

Dependencies that should converge in this roadmap:

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
- the rule evaluator implementation should own any QLExpress dependency it
  needs; `xa-mass-base` QLExpress residue is JSON-DSL work, not rule matching
  work.

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
8. No compatibility projection cleanup in this roadmap; that work is tracked
   separately because it changes read-model/result projection behavior.

## Slice RBC-0: Inventory Current Rule Callers

Goal: classify every rule call site before changing code.

Deliverable: [`RULE_BOUNDARY_CONVERGENCE_INVENTORY.md`](RULE_BOUNDARY_CONVERGENCE_INVENTORY.md).

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
   - route/auth/console wiring
   - test-only convenience
4. Identify callers that mutate rule definitions through engine package types.
5. Identify whether any caller depends on evaluator registration being stored
   inside `RuleStorage`.
6. Record current QLExpress implementation and dependency locations.
7. Record engine production dependencies that exist only for default
   construction, especially `mass-runtime-memory`.
8. Produce a small inventory doc beside this roadmap.

Acceptance:

1. Every current rule caller has one classification.
2. Every current rule caller lists the exact methods it invokes.
3. The inventory names which call sites must move before `RuleManager` can be
   narrowed.
4. The inventory names which engine dependencies are kernel contracts and which
   are assembly/default-constructor residues.
5. The inventory names every in-repo route/auth/console caller that must move
   if `/api/v1/runtime/rules` is renamed.
6. No behavior changes in this slice.

## Slice RBC-1: Define Rule Runtime Contracts And Owners

Goal: make the contract split explicit without moving behavior yet.

Deliverable: [`RULE_BOUNDARY_CONTRACTS.md`](RULE_BOUNDARY_CONTRACTS.md).

Scope:

1. Define the minimal matching-time surface needed by
   `RuleBasedTaskWorkerMatchingStrategy`:
   - read active matching rules
   - evaluate one rule against `WorkerMatchContext`
   - expose evaluator metadata for diagnostics only if still needed
2. Define the control-plane rule-definition surface separately from the
   matching surface.
3. Define evaluator registry ownership separately from durable rule storage.
4. Decide the concrete QLExpress evaluator module owner:
   - engine rule runtime assembly, or
   - a dedicated evaluator/rule-runtime module if the engine module would
     otherwise become too implementation-heavy
5. Declare the module owner for each new contract:
   - definition persistence contract
   - evaluator registry
   - matching rule-set provider
   - matching evaluator
   - QLExpress evaluator implementation
6. Update docs to say rule definitions are control-plane storage truth and
   matching consumes a snapshot/provider, not CRUD.

Acceptance:

1. Engine matching can be described without CRUD verbs.
2. Rule definition storage can be described without evaluator lifecycle verbs.
3. Every new contract has a declared module owner.
4. The QLExpress dependency owner is explicit before any file move.
5. No new generic plugin framework is introduced.

## Slice RBC-2: Extract Evaluator Registry And Matching Contracts

Goal: stop treating evaluator lifecycle as durable storage behavior and give
matching a narrow dependency before removing the broad manager.

Scope:

1. Introduce `RuleEvaluatorRegistry` or equivalent.
2. Move evaluator registration and lookup out of `RuleStorage`. (Implemented.)
3. Keep `RuleStorage` focused on rule definition persistence.
4. Introduce `MatchingRuleSetProvider` or equivalent for active/default worker
   matching rules. (Implemented.)
5. Introduce `MatchingRuleEvaluator` or equivalent for evaluating one rule
   against `WorkerMatchContext`. (Implemented.)
6. Move or share QLExpress evaluator implementation according to the RBC-1
   owner decision. (Implemented for rule evaluator.)
7. Update storage tests that currently assert evaluator metadata through
   `RuleStorage`.

Acceptance:

1. `RuleStorage` no longer exposes evaluator registration methods.
2. In-memory and JDBC storage remain definition stores.
3. The matching contract exposes no CRUD verbs.
4. The matching contract does not expose evaluator registration.
5. Rule evaluation still supports the current QLExpress evaluator.
6. Rule storage modules no longer import or auto-register QLExpress evaluator
   implementations.
7. Existing matching tests still prove default rule evaluation and failed-rule
   diagnostics.

## Slice RBC-3: Narrow Engine Rule Usage

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

## Slice RBC-4: Move Bootstrap And Admin Writes Out Of Engine

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

Acceptance:

1. No production bootstrap path needs engine package rule CRUD methods.
2. `doc/INTERNAL_API_REFERENCE.md` does not describe rule definitions as
   runtime truth.
3. In-repo bootstrap callers use the corrected setup surface; no old manager
   compatibility layer remains.

## Slice RBC-5: Rename Rule API Route And Console Wiring

Goal: rule read/write surfaces are named as admin/control-plane surfaces, not
runtime surfaces.

Scope:

1. Rename or remove `RuleApiController`'s `/api/v1/runtime/rules` route.
2. Update `ApiRouteAuthorizationCatalog` for the new rule route.
3. Update frontend console route aliases and resource routes:
   - `FrontendConsoleController`
   - `FrontendConsoleRoutingService`
   - `/status/rules`
   - `/resources/rules`
4. Update controller, authorization, routing, and console integration tests.
5. Put any future rule write endpoint under an explicit admin/control-plane
   path, not `/runtime/rules`.

Acceptance:

1. No in-repo caller depends on `/api/v1/runtime/rules`.
2. Rule read APIs are either removed or explicitly control-plane/admin named.
3. Route authorization covers the new route.
4. Console redirects and resource routes remain coherent after the rename.
5. No compatibility alias remains for the old runtime rule path.

## Slice RBC-6: Remove Engine Runtime-Memory Production Dependency

Goal: remove in-memory runtime implementation ownership from engine production
code.

Scope:

1. Remove `TaskManager` convenience constructors that instantiate
   `InMemoryTaskResultRuntime`.
2. Require `TaskResultRuntime` injection at the engine constructor boundary.
3. Move in-memory default assembly to server/SDK/test composition:
   - `xa-mass-server` can keep constructing `InMemoryTaskResultRuntime` for
     memory profile assembly.
   - `xa-mass-sdk` can keep defaulting `EngineConfig.taskResultRuntime` for
     embedded assembly.
   - engine tests can construct in-memory runtime directly as test fixtures.
4. Change `xa-mass-engine` `mass-runtime-memory` dependency to test scope or
   remove it if no engine tests need it directly.
5. Update callers to use the explicit constructor or assembly defaults.

Acceptance:

1. `xa-mass-engine` main sources do not reference
   `com.xa.mass.runtime.memory`.
2. `xa-mass-engine` production dependency tree does not include
   `mass-runtime-memory` as a direct production dependency.
3. Server and SDK embedded assembly still have an in-memory task result runtime
   default where appropriate.
4. Engine tests that need in-memory runtime use it as a test fixture, not as an
   engine production default.

## Slice RBC-7: Guard And Proof

Goal: prevent rule CRUD and assembly residues from drifting back into engine.

Scope:

1. Add or update architecture guard tests:
   - matching strategy may depend on matching rule contracts only
   - engine must not expose CRUD-shaped rule manager methods as the matching
     dependency
   - future rule write endpoints must not live under `/runtime/rules`
   - engine production code must not depend on storage implementations
   - engine production code must not import `com.xa.mass.runtime.memory`
   - storage modules must not import concrete rule evaluator implementations
2. Add focused proof that default rules still affect worker matching.
3. Add focused proof that rule definition persistence still works through
   memory and JDBC storage.
4. Add dependency proof for `xa-mass-engine` production scope.

Acceptance:

1. Guard fails if engine matching imports a broad CRUD rule manager.
2. Guard fails if rule definition writes are added under runtime API routes.
3. Guard fails if engine production code imports storage implementation
   packages.
4. Guard fails if engine production code imports runtime memory
   implementation packages.
5. Guard fails if storage modules import QLExpress evaluator classes after
   evaluator extraction.
6. Memory and JDBC rule definition tests pass.
7. Existing scheduling/rule diagnostics remain behaviorally equivalent.

## Implementation Order

Recommended order:

```text
RBC-0 -> RBC-1 -> RBC-2 -> RBC-3 -> RBC-4 -> RBC-5 -> RBC-6 -> RBC-7
```

Do not start by moving files. First classify call sites and define the contract
split. The highest-risk parts are evaluator lifecycle ownership, route
renaming blast radius, and engine dependency direction, not Java package
movement.

## Verification Candidates

Initial commands to keep in the roadmap proof set:

```powershell
mvn -pl xa-mass-engine -am '-Dtest=RuleManagerTest,RuleConfigTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am '-Dtest=InMemoryRuleStorageTest,JdbcStorageH2Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-server -am '-Dtest=RuleApiControllerTest,FrontendConsoleControllerTest,ControlConsoleRoutingIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Dependency review commands for the engine convergence slices:

```powershell
mvn -pl xa-mass-engine dependency:tree
```

The exact test list should be corrected in RBC-0 after the current rule test
inventory is complete.
