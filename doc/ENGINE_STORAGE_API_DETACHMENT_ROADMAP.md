# Engine Storage API Detachment Roadmap

Status: proposed.

This roadmap removes the remaining production dependency from
`xa-mass-engine` to `mass-storage-api`. It follows
[`PROJECTION_INFRASTRUCTURE_RETIREMENT_ROADMAP.md`](./PROJECTION_INFRASTRUCTURE_RETIREMENT_ROADMAP.md):
PIR deleted review/projection infrastructure, but it intentionally left the
non-projection engine storage dependency in place. This roadmap handles that
next boundary.

## Problem Statement

`xa-mass-engine/pom.xml` still has a production dependency on
`mass-storage-api`.

Current production imports are limited, but they are still storage-owned:

- `TaskManager` imports `TaskShellStore` and `TaskShellLifecycleQuery`.
- `StorageBackedMatchingRuleSetProvider` imports `RuleStorage`.
- engine rule runtime classes import `RuleDefinition`, `RuleEvaluator`, and
  `RuleType` from `com.xa.mass.storage.rule`.

This keeps the engine kernel coupled to a DB/storage module even after
projection retirement. The remaining dependency is not review/projection
residue; it is a deeper contract ownership issue.

## Current Code Facts

- `mass-storage-api` currently contains only:
  - `RuleStorage`
  - `TaskShellStore`
  - `TaskShellLifecycleQuery`
  - `RuleDefinition`
  - `RuleEvaluator`
  - `RuleType`
- `TaskManager` uses task-shell storage as a persistence port for task shell
  create/read/update/delete and lifecycle-deadline polling fallback.
- `TaskShellStore` is broader than the engine runtime kernel needs: it also
  exposes all-task list/status/project queries. Those are control-plane query
  concerns, not runtime-task handling.
- `TaskManager` still contains `ScanningTaskShellLifecycleQuery`, which scans
  task shell storage when the store does not implement
  `TaskShellLifecycleQuery`.
- Engine matching already has narrow matching-time contracts:
  `MatchingRuleSetProvider`, `MatchingRuleEvaluator`, and
  `RuleEvaluatorRegistry`.
- `StorageBackedMatchingRuleSetProvider` is an adapter from storage CRUD
  (`RuleStorage`) to the engine matching-time rule set provider. It lives in
  engine today, which pulls storage back into the kernel.
- `xa-mass-engine` has a `mass-storage-memory` dependency in test scope. This
  is test residue, not a production dependency.
- Moving storage implementations to depend directly on `xa-mass-engine` would
  create an avoidable Maven cycle risk because engine tests currently depend
  on storage-memory fixtures.

## Boundary Decision

Engine production must depend on engine-facing contracts, not on DB/storage
modules.

Introduce a small engine-facing SPI/contract module, tentatively named
`xa-mass-engine-spi`, that owns only the contracts the runtime kernel needs
from outside:

```text
xa-mass-engine -> xa-mass-engine-spi -> xa-mass-base
mass-storage-memory/jdbc -> xa-mass-engine-spi
xa-mass-sdk/server -> xa-mass-engine + storage implementations
```

The SPI module is not a DB module and it is not a new home for broad task
CRUD. It contains runtime-kernel ports and matching-rule value contracts
needed by engine and storage adapters.

Storage modules become adapters that implement engine-facing ports. Engine no
longer imports `com.xa.mass.storage.*` in production and no longer declares
`mass-storage-api` in production scope. Broad task list/status/project query
surfaces stay outside engine in control-plane/server/SDK query ownership.

Rule CRUD and rule persistence remain outside engine. Engine should consume a
`MatchingRuleSetProvider`, not `RuleStorage`. The adapter from `RuleStorage` to
`MatchingRuleSetProvider` belongs in SDK/server assembly or an adapter module,
not in engine production.

## Target Shape

Engine production:

```text
TaskManager -> narrow runtime-task shell port from xa-mass-engine-spi
TaskManager -> deadline/lifecycle query port from xa-mass-engine-spi
RuleBasedTaskWorkerMatchingStrategy -> MatchingRuleSetProvider / MatchingRuleEvaluator
```

Storage adapters:

```text
InMemoryTaskShellStore implements engine-spi runtime-task shell ports
JdbcTaskShellStore implements engine-spi runtime-task shell ports
InMemoryRuleStorage persists engine-spi RuleDefinition values
```

Assembly:

```text
SDK/server wire storage implementations into engine ports
SDK/server may adapt RuleStorage into MatchingRuleSetProvider
```

The exact module name is a slice-1 decision, but the dependency shape is not:
engine must not depend on `mass-storage-api`, `mass-storage-memory`, or
`mass-storage-jdbc` in production.

## Non-Goals

1. No change to task scheduling, lease, result convergence, retry, or terminal
   policy behavior.
2. No deletion of storage implementations. `mass-storage-memory` and
   `mass-storage-jdbc` remain valid adapters unless a later roadmap retires or
   renames them.
3. No rule CRUD redesign. This roadmap only removes engine's dependency on
   rule storage contracts.
4. No public external Java SDK redesign. Public-facing `xa-mass-java-sdk` is a
   separate module and should not be pulled into this boundary cleanup.
5. No compatibility aliases for old package names. Update in-repo callers.
6. No broad rename churn unless the owner or direction materially changes.
7. No direct dependency from storage adapter modules to `xa-mass-engine` unless
   ESD-0 proves Maven cycles and test layout are already resolved. Prefer the
   SPI module to keep adapter direction clean.
8. No wholesale move of `TaskShellStore` into engine SPI. The current
   `TaskShellStore` mixes runtime-kernel needs with all-task control-plane
   queries; ESD must split before moving.

## Hard Rules

1. `xa-mass-engine` production POM must not depend on `mass-storage-api`,
   `mass-storage-memory`, or `mass-storage-jdbc` after this roadmap completes.
2. `xa-mass-engine/src/main` must not import `com.xa.mass.storage.*` after
   this roadmap completes.
3. Engine production must not contain a storage-backed adapter such as
   `StorageBackedMatchingRuleSetProvider`.
4. `RuleStorage` remains persistence/control-plane shape. Matching-time engine
   code consumes `MatchingRuleSetProvider`.
5. Task shell storage is still persisted DB/control-plane shell state, not
   runtime truth. Moving the engine-needed port must not rename it to
   `RuntimeTaskShell` or imply that engine owns all task CRUD.
6. Storage adapters may implement engine-facing ports, but runtime decisions
   remain in engine/runtime, not in storage.
7. Test-scope storage dependencies are allowed only while classified. The final
   guard must distinguish production dependency removal from test fixture
   residue.
8. Engine-facing task ports must not expose all-task list/status/project
   queries. Those are control-plane query surfaces.

## Slice ESD-0: Dependency And Caller Inventory

Goal: classify every current engine production dependency on
`mass-storage-api` and every non-engine caller affected by moving the contracts.

Scope:

1. Record every `xa-mass-engine/src/main` import from
   `com.xa.mass.storage.*`.
2. Record every `xa-mass-sdk/src/main` and `xa-mass-server/src/main` caller
   that exposes or wires `TaskShellStore`, `TaskShellLifecycleQuery`,
   `RuleStorage`, or `com.xa.mass.storage.rule.*`.
3. Record every storage implementation that implements task-shell or rule
   contracts.
4. Record test-only dependencies separately, especially engine tests using
   `InMemoryTaskShellStore` and `InMemoryRuleStorage`.
5. Split current `TaskShellStore` method usage into:
   - runtime-kernel shell operations actually used by engine lifecycle
   - lifecycle/deadline query operations
   - all-task control-plane query operations (`listTasksPaged`,
     `getTasksByStatus`, `getTasksByProject`)
6. Decide the new SPI module name and package convention.
7. Confirm whether `mass-storage-api` will remain as a rule-persistence API
   after engine detachment or whether it becomes empty and should be retired.

Acceptance:

1. Every `com.xa.mass.storage.*` production import in engine has one
   replacement decision.
2. Every SDK/server public or assembly caller has one migration decision.
3. Test-scope storage dependencies are classified separately from production
   dependencies.
4. All-task query operations are explicitly assigned outside engine before
   code moves start.
5. The new SPI module name and package convention are documented before code
   moves start.

## Slice ESD-1: Create Engine-Facing SPI Module

Goal: create the non-DB contract module that breaks the engine -> storage
dependency without reversing storage adapters directly into engine.

Scope:

1. Add the selected SPI module, e.g. `xa-mass-engine-spi`.
2. Define narrow runtime-kernel task shell ports in the SPI package. The exact
   names are an ESD-0/ESD-1 decision, but the shape must exclude broad
   all-task query methods. Candidate split:
   - by-id shell command/lookup port used by engine lifecycle
   - deadline/lifecycle query port used by max-runtime maintenance
3. Move or recreate matching-rule runtime value contracts needed by engine:
   - `RuleDefinition`
   - `RuleType`
   - `RuleEvaluator`
4. Do not move `RuleStorage` into engine SPI unless ESD-0 proves it is a
   runtime-kernel port. The default assumption is that `RuleStorage` remains
   persistence/control-plane.
5. Keep behavior unchanged. At this slice boundary, storage-api may still
   contain old broad contracts until callers are migrated.

Acceptance:

1. SPI module compiles and depends only on stable lower modules such as
   `xa-mass-base`.
2. SPI module has no dependency on `mass-storage-*`, `xa-mass-engine`,
   `xa-mass-sdk`, or `xa-mass-server`.
3. No production behavior changes.

## Slice ESD-2: Retarget Engine Main To SPI Contracts

Goal: make engine production compile against SPI contracts instead of
`mass-storage-api`.

Scope:

1. Update `TaskManager` to import SPI runtime-kernel task shell ports, not the
   broad storage `TaskShellStore`.
2. Update engine rule runtime classes to import SPI `RuleDefinition`,
   `RuleEvaluator`, and `RuleType`.
3. Remove or move `StorageBackedMatchingRuleSetProvider` out of engine
   production. Engine keeps only `MatchingRuleSetProvider`.
4. Update `EngineProofOwnershipGuardTest` to forbid all
   `com.xa.mass.storage.*` imports in engine main, not just projection
   imports.
5. Keep `mass-storage-api` dependency temporarily only if compilation still
   requires it during the migration slice; remove it in ESD-5.

Acceptance:

1. `xa-mass-engine/src/main` has no `com.xa.mass.storage.*` imports.
2. Engine production still compiles and existing engine behavior tests pass.
3. Matching code consumes matching-time provider/evaluator contracts, not
   storage CRUD.
4. Engine-facing task ports do not expose `listTasksPaged`,
   `getTasksByStatus`, or `getTasksByProject`.

## Slice ESD-3: Retarget Storage Adapters To SPI Contracts

Goal: make storage implementations implement the engine-facing SPI ports while
remaining outside engine production.

Scope:

1. Update `InMemoryTaskShellStore` and `JdbcTaskShellStore` to implement SPI
   runtime-kernel task shell ports.
2. Update rule storage implementations to store and return SPI rule
   definitions/types.
3. Keep or split broad all-task query storage outside engine. If a
   `TaskShellQueryStore` remains, it is control-plane query ownership, not an
   engine-facing port.
4. If `mass-storage-api` still contains engine-facing task ports or
   storage-owned rule value duplicates, delete them after all callers have
   migrated.
5. Ensure storage adapter modules do not depend on `xa-mass-engine`; they
   depend only on the SPI module.

Acceptance:

1. Storage memory/JDBC modules compile against SPI contracts.
2. No duplicate live engine-facing task-shell or rule value contracts remain
   in `mass-storage-api`.
3. No storage adapter module depends on `xa-mass-engine`.
4. Any remaining all-task query storage contract is documented as
   control-plane/server/SDK query ownership, not engine runtime ownership.

## Slice ESD-4: Retarget SDK And Server Assembly

Goal: move assembly and public internal SDK wiring to the new contract owner.

Scope:

1. Update `EngineConfig`, `MassEngineBuilder`, `MassApplicationBuilder`, and
   `MassSdk.EngineOptions` to use SPI runtime-kernel task shell ports where
   they assemble engine.
2. Update rule operations and rule configuration surfaces to use SPI rule
   value contracts where they interact with engine matching.
3. Move the `RuleStorage` -> `MatchingRuleSetProvider` adapter out of engine.
   Candidate homes:
   - SDK assembly if only SDK/server boot needs it
   - storage adapter module if it is storage-specific
   - a small adapter package in a non-engine module if both SDK and server
     need it
4. Update `XaMassServerApplication` wiring to pass storage adapters into
   engine through SPI contracts.
5. Move all-task list/status/project query surfaces to control-plane/server or
   SDK query ownership if they currently route through engine.

Acceptance:

1. SDK/server production compiles with SPI contract imports.
2. No server or SDK path reintroduces engine -> storage production dependency.
3. Rule CRUD remains outside engine; matching-time rule consumption still goes
   through `MatchingRuleSetProvider`.
4. All-task list/status/project query surfaces do not require engine
   production to depend on storage contracts.

## Slice ESD-5: Remove Engine Production Storage Dependency

Goal: delete the actual production dependency from `xa-mass-engine/pom.xml`.

Scope:

1. Remove `mass-storage-api` from `xa-mass-engine/pom.xml` production
   dependencies.
2. Keep or remove `mass-storage-memory` test scope based on ESD-0
   classification. If kept, document it as test fixture residue.
3. Add a Maven enforcer or architecture guard that fails if engine production
   declares any `mass-storage-*` dependency.
4. Add a source guard that fails if `xa-mass-engine/src/main` imports
   `com.xa.mass.storage.*`.

Acceptance:

1. `mvn -pl xa-mass-engine -am test` passes.
2. `xa-mass-engine/pom.xml` has no production dependency on
   `mass-storage-api`, `mass-storage-memory`, or `mass-storage-jdbc`.
3. Engine main-source scan for `com.xa.mass.storage` returns no output.
4. Guard fails on reintroduced production storage imports or POM dependency.

## Slice ESD-6: Retire Or Reclassify `mass-storage-api`

Goal: avoid leaving `mass-storage-api` as a misleading half-empty contract
module.

Scope:

1. Inspect remaining contents of `mass-storage-api` after ESD-3/ESD-4.
2. If empty, remove it from the reactor and all POMs.
3. If it still owns persistence-only contracts, rename or document it as
   storage-adapter API rather than engine-facing API.
4. Update `platform_infra/README.md`, `doc/AGENT_BASELINE.md`,
   `doc/INFRA_TRUTH_LAYERS.md`, and engine baseline docs.

Acceptance:

1. No active doc says engine depends on `mass-storage-api`.
2. `mass-storage-api` is either removed or explicitly documented as
   persistence/control-plane only.
3. No duplicate engine-facing contracts exist in both SPI and storage API.

## Implementation Order

```text
ESD-0 -> ESD-1 -> ESD-2 -> ESD-3 -> ESD-4 -> ESD-5 -> ESD-6
```

Do not start by deleting `mass-storage-api` from the engine POM. Also do not
start by copying the entire `TaskShellStore` into a new SPI package. First
split engine-needed runtime-task ports from all-task control-plane queries,
then move the contract owner, retarget engine callers, retarget storage
adapters, remove the dependency, and add guards.

Each slice must compile and pass its targeted tests before the next slice
starts. No "break now, fix later" intermediate state.

## Verification Candidates

```powershell
mvn -pl xa-mass-engine -am test
```

```powershell
mvn -pl xa-mass-sdk,xa-mass-server,platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am test
```

```powershell
rg "com\.xa\.mass\.storage" xa-mass-engine/src/main
```

Expected result after ESD-5: no output.

```powershell
mvn -pl xa-mass-engine help:effective-pom | rg "mass-storage-(api|memory|jdbc)"
```

Expected result after ESD-5: no production-scope storage dependency. Test-scope
fixture dependencies must be explicitly classified if still present.
