# Engine Storage API Detachment Roadmap

Status: implemented.

This roadmap removes the remaining production dependency from
`xa-mass-engine` to `mass-storage-api`. It follows
[`PROJECTION_INFRASTRUCTURE_RETIREMENT_ROADMAP.md`](./PROJECTION_INFRASTRUCTURE_RETIREMENT_ROADMAP.md):
PIR deleted review/projection infrastructure, but it intentionally left the
non-projection engine storage dependency in place. This roadmap handles that
next boundary.

PIR-1's stats-surface separation remains a distinct concern if it has not
landed when ESD starts. ESD is about removing engine production's storage
module dependency; it must not use PIR-1 status as a reason to keep
`mass-storage-api` coupled to the engine kernel.

## Problem Statement

`xa-mass-engine/pom.xml` still has a production dependency on
`mass-storage-api`.

Current production imports are limited, but they are still storage-owned:

- `TaskManager` imports `TaskShellStore` and `TaskShellLifecycleQuery`.
- `StorageBackedMatchingRuleSetProvider` imports `RuleStorage`.
- engine rule runtime classes import `RuleDefinition`, `RuleEvaluator`, and
  `RuleType` from `com.xa.mass.kernel.spi.rule`.

This keeps the engine kernel coupled to a DB/storage module even after
projection retirement. The remaining dependency is not review/projection
residue; it is a deeper contract ownership issue.

## Implementation Record

This roadmap has landed. The implemented shape is:

- `xa-mass-kernel-spi` owns the kernel-facing task shell ports and matching
  rule value contracts.
- `xa-mass-engine` production depends on `xa-mass-kernel-spi`, not on
  `mass-storage-api`, `mass-storage-memory`, or `mass-storage-jdbc`.
- `mass-storage-api` remains a persistence/control-plane storage contract
  module and depends on `xa-mass-kernel-spi` for shared rule value types.
- `mass-storage-memory` and `mass-storage-jdbc` implement the kernel-facing
  task shell ports as storage adapters.
- The `RuleStorage` to `MatchingRuleSetProvider` adapter lives in SDK
  assembly, not in engine production.
- Broad task list/status query methods were removed from the engine query
  port and are handled through SDK/storage assembly.
- `mass-storage-memory` remains in `xa-mass-engine` test scope only as a test
  fixture dependency.

## Pre-Implementation Code Facts

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
- `TaskQueryPort` currently still exposes `listTasksPaged(...)` and
  `getTasksByStatus(...)`; those methods delegate back to `TaskShellStore` in
  `TaskManager`. Detaching engine from storage therefore requires classifying
  the engine query surface itself, not only moving the storage interface.
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

Engine production must depend on kernel-facing contracts, not on DB/storage
modules.

Introduce a small kernel-facing SPI/contract module named
`xa-mass-kernel-spi` that owns only the contracts the runtime kernel needs
from outside:

```text
xa-mass-engine -> xa-mass-kernel-spi -> xa-mass-base
mass-storage-api -> xa-mass-kernel-spi       only if RuleStorage remains and
                                             references moved rule values
mass-storage-memory/jdbc -> xa-mass-kernel-spi
xa-mass-sdk/server -> xa-mass-engine + storage implementations
```

The SPI module is not an engine implementation module, not a DB module, and
not a new home for broad task CRUD. It contains runtime-kernel ports and
matching-rule value contracts needed by engine and storage adapters.

Storage modules become adapters that implement kernel-facing ports. Engine no
longer imports `com.xa.mass.storage.*` in production and no longer declares
`mass-storage-api` in production scope. Broad task list/status/project query
surfaces stay outside engine runtime-kernel ports in control-plane/server/SDK
query ownership. If `TaskQueryPort` retains bounded shell/debug query methods,
those methods must not require engine production to import storage contracts.

Rule CRUD and rule persistence remain outside engine. Engine should consume a
`MatchingRuleSetProvider`, not `RuleStorage`. The adapter from `RuleStorage` to
`MatchingRuleSetProvider` belongs in SDK/server assembly or an adapter module,
not in engine production. The rule value types used by matching are moved to
the kernel-facing SPI contract owner; do not create parallel same-shape rule
DTOs.

## Target Shape

Engine production:

```text
TaskManager -> narrow runtime-task shell port from xa-mass-kernel-spi
TaskManager -> deadline/lifecycle query port from xa-mass-kernel-spi
RuleBasedTaskWorkerMatchingStrategy -> MatchingRuleSetProvider / MatchingRuleEvaluator
```

Control-plane queries:

```text
server/SDK query surface -> storage/control-plane query owner
engine runtime kernel -> no all-task scan/list/status/project storage port
```

Storage adapters:

```text
InMemoryTaskShellStore implements kernel-spi runtime-task shell ports
JdbcTaskShellStore implements kernel-spi runtime-task shell ports
InMemoryRuleStorage persists kernel-spi RuleDefinition values
```

Assembly:

```text
SDK/server wire storage implementations into engine ports
SDK/server may adapt RuleStorage into MatchingRuleSetProvider
```

The module name is fixed as `xa-mass-kernel-spi`; the dependency shape is also
fixed: engine must not depend on `mass-storage-api`, `mass-storage-memory`, or
`mass-storage-jdbc` in production.

If rule value contracts move to the SPI module while `RuleStorage` remains in
`mass-storage-api`, `mass-storage-api` must depend on the SPI module for those
method signatures. That direction is allowed because `RuleStorage` is
persistence/control-plane shape depending on a shared value contract; it must
not cause engine to depend back on storage.

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
8. No wholesale move of `TaskShellStore` into `xa-mass-kernel-spi`. The current
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
6. Storage adapters may implement kernel-facing ports, but runtime decisions
   remain in engine/runtime, not in storage.
7. Test-scope storage dependencies are allowed only while classified. The final
   guard must distinguish production dependency removal from test fixture
   residue.
8. Kernel-facing task ports must not expose all-task list/status/project
   queries. Those are control-plane query surfaces.
9. Do not introduce duplicate `RuleDefinition` / `RuleType` /
   `RuleEvaluator` class families. Move ownership; do not recreate same-shape
   compatibility types.

## Slice ESD-0: Dependency And Caller Inventory

Goal: classify every current engine production dependency on
`mass-storage-api`, every non-engine caller affected by moving the contracts,
and the owner decisions that determine the SPI shape.

Scope:

1. Record every `xa-mass-engine/src/main` import from
   `com.xa.mass.storage.*`.
2. Record every `xa-mass-sdk/src/main` and `xa-mass-server/src/main` caller
   that exposes or wires `TaskShellStore`, `TaskShellLifecycleQuery`,
   `RuleStorage`, or `com.xa.mass.kernel.spi.rule.*`.
3. Record every storage implementation that implements task-shell or rule
   contracts.
4. Record test-only dependencies separately, especially engine tests using
   `InMemoryTaskShellStore` and `InMemoryRuleStorage`.
5. Split current `TaskShellStore` method usage into:
   - runtime-kernel shell operations actually used by engine lifecycle
   - lifecycle/deadline query operations
   - all-task control-plane query operations (`listTasksPaged`,
     `getTasksByStatus`, `getTasksByProject`)
6. Classify current engine query and command surfaces that expose storage-like
   methods:
   - `TaskQueryPort.listTasksPaged(...)`
   - `TaskQueryPort.getTasksByStatus(...)`
   - any SDK/server caller that reaches those methods for admin/review
     behavior
7. Decide the retirement path for `ScanningTaskShellLifecycleQuery`. The
   expected decision is delete: after the split, storage adapters provide the
   SPI lifecycle/deadline query port directly, and engine no longer falls back
   to all-task scans.
8. Use the `xa-mass-kernel-spi` module name and decide its package convention.
   - `mass-storage-api` is allowed to depend on this module for moved shared
     rule value contracts.
9. Decide that `RuleDefinition`, `RuleType`, and `RuleEvaluator` are moved to
   the SPI contract owner rather than recreated as parallel types.
10. Decide the dependency path for `RuleStorage` after the rule value move:
    either `mass-storage-api` depends on the SPI module for the moved value
    contracts, or `RuleStorage` moves/reforms in the same slice. The default is
    `mass-storage-api -> xa-mass-kernel-spi`.
11. Decide the home for the `RuleStorage` -> `MatchingRuleSetProvider`
    adapter before implementation starts. ESD-3 must move the adapter and its
    SDK `EngineConfig` creation point atomically.
12. Confirm whether `mass-storage-api` will remain as a rule-persistence API
   after engine detachment or whether it becomes empty and should be retired.

Acceptance:

1. Every `com.xa.mass.storage.*` production import in engine has one
   replacement decision.
2. Every SDK/server public or assembly caller has one migration decision.
3. Test-scope storage dependencies are classified separately from production
   dependencies.
4. All-task query operations are explicitly assigned outside engine before
   code moves start.
5. The exact method split between kernel-facing runtime task ports,
   lifecycle/deadline query ports, and control-plane query surfaces is
   documented before ESD-1 starts. Kernel-facing ports must exclude
   `listTasksPaged`, `getTasksByStatus`, and `getTasksByProject`.
6. `TaskQueryPort` all-task methods have one explicit decision: remove,
   relocate, or keep only behind a non-storage kernel-facing contract that does
   not require engine production to import storage.
7. `ScanningTaskShellLifecycleQuery` has an explicit retirement decision. If
   retained for a short transition, the transition must still compile without
   adding all-task scan methods to kernel-facing SPI.
8. `RuleDefinition`, `RuleType`, and `RuleEvaluator` ownership is documented
   as move-to-SPI, not recreate.
9. `RuleStorage`'s compile path after the value move is documented, including
   whether `mass-storage-api` depends on SPI.
10. The `RuleStorage` -> `MatchingRuleSetProvider` adapter home is documented,
   and the ESD-3 atomic move/update path is written down.
11. The `xa-mass-kernel-spi` package convention is documented before code
   moves start.

## Slice ESD-1: Create SPI Module And Move Rule Value Contracts

Goal: create the non-DB contract module and move matching-rule value contracts
in one compile-safe step.

Scope:

1. Add the `xa-mass-kernel-spi` module.
2. Define narrow runtime-kernel task shell ports in the SPI package using the
   ESD-0 method split. The shape must exclude broad all-task query methods.
   Candidate split:
   - by-id shell command/lookup port used by engine lifecycle
   - deadline/lifecycle query port used by max-runtime maintenance
3. Move matching-rule runtime value contracts needed by engine into the SPI
   package:
   - `RuleDefinition`
   - `RuleType`
   - `RuleEvaluator`
4. Update every production caller of the moved rule value contracts in the
   same slice, including:
   - engine matching/rule classes
   - `RuleStorage`
   - memory/JDBC rule storage implementations
   - SDK/server rule operations and boot wiring
5. If `RuleStorage` remains in `mass-storage-api`, update `mass-storage-api`
   to compile against the moved SPI rule value contracts. Do not keep old
   storage-owned rule value duplicates.
6. Do not move `RuleStorage` into `xa-mass-kernel-spi` unless ESD-0 proves it
   is a runtime-kernel port. The default assumption is that `RuleStorage`
   remains persistence/control-plane.
7. Keep task-shell behavior unchanged. At this slice boundary, storage-api may
   still contain old broad task-shell contracts until callers are migrated.

Acceptance:

1. SPI module compiles and depends only on stable lower modules such as
   `xa-mass-base`.
2. SPI module has no dependency on `mass-storage-*`, `xa-mass-engine`,
   `xa-mass-sdk`, or `xa-mass-server`.
3. No production behavior changes.
4. There is no second live rule-value class family with the same semantic
   shape.
5. Any required `mass-storage-api -> SPI` dependency for `RuleStorage`
   signatures is explicit and does not introduce an engine -> storage path.
6. `xa-mass-engine`, `xa-mass-sdk`, `xa-mass-server`, `mass-storage-api`,
   `mass-storage-memory`, and `mass-storage-jdbc` compile after the rule value
   move.

## Slice ESD-2: Teach Storage Adapters The SPI Task Ports

Goal: make storage implementations implement the kernel-facing task ports
before engine consumes those ports.

Scope:

1. Update `InMemoryTaskShellStore` and `JdbcTaskShellStore` to implement SPI
   runtime-kernel task shell ports and the SPI lifecycle/deadline query port.
2. Keep their existing `TaskShellStore` / `TaskShellLifecycleQuery`
   implementations temporarily so SDK/server callers still compile until
   ESD-4.
3. Keep or split broad all-task query storage outside engine. If a
   `TaskShellQueryStore` remains, it is control-plane query ownership, not an
   kernel-facing port.
4. Ensure storage adapter modules do not depend on `xa-mass-engine`; they
   depend only on the SPI module.

Acceptance:

1. Storage memory/JDBC modules compile against SPI contracts.
2. Storage adapters implement SPI runtime-kernel task shell and
   lifecycle/deadline ports.
3. Old `mass-storage-api` task-shell contracts that still serve SDK/server
   callers are documented as ESD-4/ESD-5 migration targets.
4. No storage adapter module depends on `xa-mass-engine`.
5. Any remaining all-task query storage contract is documented as
   control-plane/server/SDK query ownership, not engine runtime ownership.

## Slice ESD-3: Retarget Engine Main And SDK Assembly Atomically

Goal: make engine production compile against SPI contracts instead of
`mass-storage-api`, without leaving SDK assembly in an uncompilable middle
state.

Scope:

1. Update `TaskManager` to import SPI runtime-kernel task shell ports, not the
   broad storage `TaskShellStore`.
2. Update `TaskManager` constructors and the SDK `EngineConfig` creation path
   in the same slice so the objects passed into `TaskManager` are typed as SPI
   ports.
3. Move `StorageBackedMatchingRuleSetProvider` out of engine production to the
   ESD-0-selected non-engine home and update its current SDK creation point in
   `EngineConfig` in the same slice.
4. Apply the ESD-0 decision for `TaskQueryPort` all-task methods. Engine
   runtime-kernel ports must not regain all-task list/status/project storage
   queries through another name.
5. Retire `ScanningTaskShellLifecycleQuery` fallback in `TaskManager`. Storage
   adapters already implement the SPI lifecycle/deadline query port from
   ESD-2.
6. Update `EngineProofOwnershipGuardTest` to forbid all
   `com.xa.mass.storage.*` imports in engine main, not just projection
   imports.
7. Keep `mass-storage-api` dependency temporarily only if compilation still
   requires it during the migration slice; remove it in ESD-5.

Acceptance:

1. `xa-mass-engine/src/main` has no `com.xa.mass.storage.*` imports.
2. Engine and SDK production still compile in the same slice.
3. Matching code consumes matching-time provider/evaluator contracts, not
   storage CRUD.
4. Kernel-facing task ports do not expose `listTasksPaged`,
   `getTasksByStatus`, or `getTasksByProject`.
5. `TaskQueryPort` and max-runtime maintenance behavior still have explicit
   passing coverage after the storage import removal.
6. The storage-backed matching-rule adapter no longer lives in engine, and its
   SDK assembly creation path compiles in the same slice.
7. `ScanningTaskShellLifecycleQuery` no longer exists in `TaskManager`.

## Slice ESD-4: Retarget SDK And Server Assembly

Goal: finish SDK/server assembly and control-plane split after engine has
already detached from storage imports.

Scope:

1. Update remaining `MassEngineBuilder`, `MassApplicationBuilder`,
   `MassSdk.EngineOptions`, and `XaMassServerApplication` assembly surfaces to
   expose SPI runtime-kernel task shell ports where they assemble engine.
2. Keep public/internal control-plane query configuration separate if those
   callers still need broader storage query surfaces.
3. Verify the `RuleStorage` -> `MatchingRuleSetProvider` adapter move completed
   in ESD-3. If ESD-0 chose a temporary SDK home, finalize or document that
   placement here; do not reintroduce the adapter into engine.
4. Move all-task list/status/project query surfaces to control-plane/server or
   SDK query ownership if they currently route through engine.
5. Remove old `TaskShellStore` exposure from assembly-only paths once the SPI
   assembly path is proven.

Acceptance:

1. SDK/server production compiles with SPI contract imports.
2. No server or SDK path reintroduces engine -> storage production dependency.
3. Rule CRUD remains outside engine; matching-time rule consumption still goes
   through `MatchingRuleSetProvider`.
4. All-task list/status/project query surfaces do not require engine
   production to depend on storage contracts.
5. No duplicate live kernel-facing task-shell contracts remain solely because
   SDK/server assembly was left on old storage contracts.

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
5. No duplicate live kernel-facing task-shell contracts remain in
   `mass-storage-api`; any remaining storage contracts are documented as
   persistence/control-plane only.

## Slice ESD-6: Retire Or Reclassify `mass-storage-api`

Goal: avoid leaving `mass-storage-api` as a misleading half-empty contract
module.

Scope:

1. Inspect remaining contents of `mass-storage-api` after ESD-3/ESD-4.
2. If empty, remove it from the reactor and all POMs.
3. If it still owns persistence-only contracts, rename or document it as
   storage-adapter API rather than kernel-facing API.
4. Update `platform_infra/README.md`, `doc/AGENT_BASELINE.md`,
   `doc/INFRA_TRUTH_LAYERS.md`, and engine baseline docs.

Acceptance:

1. No active doc says engine depends on `mass-storage-api`.
2. `mass-storage-api` is either removed or explicitly documented as
   persistence/control-plane only.
3. No duplicate kernel-facing contracts exist in both SPI and storage API.

## Implementation Order

```text
ESD-0 -> ESD-1 -> ESD-2 -> ESD-3 -> ESD-4 -> ESD-5 -> ESD-6
```

**Do Not Start With**: deleting `mass-storage-api` from the engine POM,
copying the entire `TaskShellStore` into a new SPI package, or creating
parallel rule DTOs.

First split engine-needed runtime-task ports from all-task control-plane
queries, close the ESD-0 ownership decisions, move the contract owner, teach
storage adapters the new ports, retarget engine callers and SDK assembly
atomically, remove the dependency, and add guards.

Each slice must compile and pass its targeted tests before the next slice
starts. No "break now, fix later" intermediate state.

## Verification Candidates

After ESD-1:

```powershell
mvn -pl xa-mass-engine,xa-mass-sdk,xa-mass-server,platform_infra/mass-storage-api,platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am -DskipTests compile
```

```powershell
rg "com\.xa\.mass\.storage\.rule" xa-mass-engine/src/main xa-mass-sdk/src/main xa-mass-server/src/main platform_infra/mass-storage-api/src/main platform_infra/mass-storage-memory/src/main platform_infra/mass-storage-jdbc/src/main
```

Expected result after ESD-1: no production source imports from the old storage
rule-value package.

After ESD-2:

```powershell
mvn -pl platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am -DskipTests compile
```

After ESD-3:

```powershell
mvn -pl xa-mass-engine,xa-mass-sdk -am -DskipTests compile
```

```powershell
rg "com\.xa\.mass\.storage" xa-mass-engine/src/main
```

Expected result after ESD-3: no output.

After ESD-5:

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

## Landed Verification

Verified on 2026-05-30:

```powershell
mvn -pl xa-mass-engine,xa-mass-sdk,xa-mass-server,platform_infra/mass-storage-api,platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am -DskipTests compile
```

Result: passed.

```powershell
mvn -pl xa-mass-engine -am clean test
```

Result: passed.

```powershell
mvn -pl platform_infra/mass-runtime-redis -am clean test
```

Result: passed.

```powershell
mvn -pl xa-mass-sdk,xa-mass-server,platform_infra/mass-storage-jdbc -am test
```

Result: passed.

Residue checks:

```powershell
rg -n "com\.xa\.mass\.storage" xa-mass-engine/src/main
rg -n "com\.xa\.mass\.storage\.rule|package com\.xa\.mass\.storage\.rule" .
rg -n "StorageBackedMatchingRuleSetProvider" xa-mass-engine/src/main xa-mass-sdk/src/main
rg -n "mass-storage-(api|memory|jdbc)" xa-mass-engine/pom.xml
```

Result: engine production has no storage imports; old storage rule package has
no remaining source references; `StorageBackedMatchingRuleSetProvider` exists
only in SDK assembly; engine POM keeps only `mass-storage-memory` in test
scope.
