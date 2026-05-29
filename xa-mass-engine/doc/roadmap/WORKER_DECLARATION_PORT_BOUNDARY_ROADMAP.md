# Worker Declaration Port Boundary Roadmap

Status: proposed direction document. No implementation slices have landed from
this roadmap yet.

This roadmap removes the remaining production `mass-storage-api` dependency
from `xa-mass-worker-runtime` by moving the worker declaration port into the
worker-runtime owner module.

It is intentionally separate from
[`PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md`](PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md)
because projection cleanup is task read-model residue in `xa-mass-engine`.
Worker declaration storage is worker lifecycle/control-plane ownership.

It is also separate from the completed rule-boundary convergence record in
[`../../../doc/archive/xa-mass-engine/RULE_BOUNDARY_CONVERGENCE_ROADMAP.md`](../../../doc/archive/xa-mass-engine/RULE_BOUNDARY_CONVERGENCE_ROADMAP.md)
because rule definitions are matching policy data, not worker lifecycle data.

This roadmap depends on the task/worker runtime-history boundary work:

- TWH-1 renamed broad storage contracts to shell/declaration names.
- TWH-3A/TWH-3B split worker declaration shape from mixed worker runtime
  state.

Both prerequisites have landed. `WorkerDeclarationStore` now persists
`WorkerDeclarationRecord`, and `WorkerResourceOwner` converts base `Worker`
registration input into declaration rows before writing.

## Current Code Observations

- `xa-mass-worker-runtime` has a production dependency on `mass-storage-api`.
- Main-source worker-runtime imports from `mass-storage-api` are limited to:
  - `WorkerDeclarationStore`
  - `WorkerDeclarationRecord`
- `WorkerManager` constructors accept `WorkerDeclarationStore`.
- `WorkerResourceOwner` persists worker declaration rows through
  `WorkerDeclarationStore` and converts between base `Worker`,
  `WorkerDeclarationRecord`, and registry `WorkerMeta`.
- `WorkerResourceRecord` and `WorkerResourceDeclarationRuntime` reference
  `WorkerDeclarationRecord` as the declaration row shape.
- `mass-storage-memory` currently implements `WorkerDeclarationStore` from
  `mass-storage-api`.
- `mass-storage-jdbc` does not currently have a worker declaration store
  implementation. It must not be treated as an existing migration target until
  WDP-0 verifies a real JDBC worker declaration adapter requirement.
- `xa-mass-worker-runtime` currently depends on `mass-storage-memory` in test
  scope for fixtures. WDP-2 may create the reverse production dependency
  `mass-storage-memory -> xa-mass-worker-runtime`; that production/test-scope
  crossing is allowed if Maven validates it and the production graph remains
  acyclic.
- SDK `EngineConfig` is the primary assembly caller that owns the default
  `InMemoryWorkerDeclarationStore` initialization.
- `StorageBoundaryGuardTest` currently guards worker declaration contracts
  from `mass-storage-api` test sources. After the port moves, those
  declaration-shape guards should move with the contracts into
  `xa-mass-worker-runtime` test sources.
- `mass-storage-memory` is only a test-scoped dependency of
  `xa-mass-worker-runtime`; that part is not the production boundary problem.

## Boundary Decision

Worker declaration is worker-runtime control-plane truth.

Storage modules are adapters for durable or in-memory persistence. They should
not define the worker lifecycle port that worker-runtime owns.

Target dependency direction:

```text
xa-mass-worker-runtime
  -> xa-mass-base
  -> mass-runtime-api

mass-storage-memory
  -> mass-storage-api          task/rule storage contracts
  -> xa-mass-worker-runtime    worker declaration adapter contract

mass-storage-jdbc
  -> mass-storage-api          task/rule storage contracts
  -> xa-mass-worker-runtime    only if/when it implements worker declarations

xa-mass-engine
  -> xa-mass-worker-runtime
```

`mass-storage-api` may continue to own task shell/detail storage and rule
definition storage until those boundaries are separately converged. It should
not own worker declaration contracts after this roadmap.

## Target Shape

Move these contracts into `xa-mass-worker-runtime`, package
`com.xa.mass.worker.runtime.resource`:

- `WorkerDeclarationStore`
- `WorkerDeclarationRecord`

Expected role after the move:

- `WorkerDeclarationRecord`: persisted declaration-only row shape for worker
  identity, WorkerGroup/node membership, adapter hints, static attributes, max
  concurrency, and timestamps.
- `WorkerDeclarationStore`: worker-runtime-owned persistence port used by
  `WorkerResourceOwner`.
- `mass-storage-memory`: current in-memory adapter implementation of that port.
- `mass-storage-jdbc`: no worker declaration adapter unless a later production
  assembly requirement introduces one explicitly.

The port must remain declaration-only. It must not gain heartbeat,
reachability, online/offline state, dispatch gate, reservation, lease, or
worker-level supported project/event capability fields.

## Non-Goals

1. No projection cleanup. Track task message/attempt projection work in
   `PROJECTION_BOUNDARY_CONVERGENCE_ROADMAP.md`.
2. No rule-definition movement. The completed rule-boundary convergence record
   is historical; track new rule domain/storage work in a follow-up rule-domain
   roadmap.
3. No task shell/detail storage movement.
4. No new generic repository/facade layer.
5. No compatibility aliases under the old `com.xa.mass.storage.api` worker
   declaration names after in-repo callers move.
6. No behavior change to worker registration, WorkerGroup binding, candidate
   source, admission, or dispatch gating.
7. No change to storage implementation semantics beyond package/import owner
   movement.

## WDP-0 Inventory And Exact Move Plan

Goal: verify every worker declaration caller and decide the exact type names
before moving code.

Scope:

1. Inventory all production and test imports of:
   - `com.xa.mass.storage.api.WorkerDeclarationStore`
   - `com.xa.mass.storage.api.WorkerDeclarationRecord`
2. Classify callers:
   - worker-runtime owner
   - SDK/server assembly
   - storage adapter implementation
   - test fixture
   - stale documentation
3. Explicitly classify `xa-mass-sdk` `EngineConfig` as the SDK primary
   assembly caller, including its default `InMemoryWorkerDeclarationStore`
   initialization.
4. Confirm whether the moved names stay as `WorkerDeclarationStore` and
   `WorkerDeclarationRecord` or become more explicit names such as
   `WorkerDeclarationPort`.
5. Confirm the target package remains
   `com.xa.mass.worker.runtime.resource`.

Acceptance:

1. Every caller is classified.
2. The exact final type names and package are recorded.
3. No behavior changes in this slice.

## WDP-1 Move Contract Types To Worker Runtime

Goal: make worker-runtime the owner of the worker declaration port.

Scope:

1. Move `WorkerDeclarationRecord` into
   `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/resource`.
2. Move `WorkerDeclarationStore` into the same package.
3. Update worker-runtime main sources to import the moved contracts.
4. Update `xa-mass-worker-runtime/README.md` and `CONTRACTS.md` so they no
   longer say the declaration row lives in `mass-storage-api`.

Acceptance:

1. `xa-mass-worker-runtime` main sources no longer import
   `com.xa.mass.storage.api.WorkerDeclaration*`.
2. `xa-mass-worker-runtime` no longer has a production dependency on
   `mass-storage-api`.
3. Worker declaration contracts remain declaration-only.
4. Worker-runtime focused tests still pass.

## WDP-2 Retarget Storage Implementations As Adapters

Goal: keep existing storage behavior while making storage modules implement the
worker-runtime-owned port only where they actually provide worker declaration
persistence.

Scope:

1. Update `mass-storage-memory` worker declaration implementation to implement
   `com.xa.mass.worker.runtime.resource.WorkerDeclarationStore`.
2. Do not add a JDBC worker declaration adapter in this roadmap unless WDP-0
   finds an existing production assembly path that requires one.
3. Update storage contract tests to use the moved worker declaration port or
   relocate worker declaration contract tests under worker-runtime test support.
4. If the worker declaration contract test base moves out of
   `mass-storage-api`, update `mass-storage-memory` test dependencies from the
   `mass-storage-api` test-jar to the `xa-mass-worker-runtime` test-jar.
5. Document the resulting dependency shape:
   - `mass-storage-memory -> xa-mass-worker-runtime` in production scope for
     the adapter contract
   - `xa-mass-worker-runtime -> mass-storage-memory` only in test scope for
     fixtures, if still needed
6. Keep task shell/detail/rule storage contracts in `mass-storage-api`.

Acceptance:

1. `mass-storage-memory` compiles with an adapter dependency on
   `xa-mass-worker-runtime`.
2. `mass-storage-jdbc` remains free of worker declaration code unless a real
   JDBC worker declaration adapter is explicitly introduced.
3. There is no duplicate worker declaration port in `mass-storage-api`.
4. Worker declaration persistence contract tests pass for implemented adapters.
5. No task/rule storage contract is moved by this slice.
6. Any production/test-scope crossing between `mass-storage-memory` and
   `xa-mass-worker-runtime` is intentional, documented, and validated by Maven.

## WDP-3 Update SDK, Server, And Test Assembly

Goal: update all in-repo assembly callers to the new port owner.

Scope:

1. Update SDK/server imports and constructor wiring for `WorkerManager`.
2. Update `EngineConfig` imports, setters/getters, and default
   `InMemoryWorkerDeclarationStore` initialization to use the moved port.
3. Update tests that build `WorkerDeclarationRecord` fixtures.
4. Update architecture guards that reference the old path.
5. Remove old worker declaration names from current docs outside historical
   inventories.

Acceptance:

1. No main or test source imports
   `com.xa.mass.storage.api.WorkerDeclarationStore` or
   `com.xa.mass.storage.api.WorkerDeclarationRecord`.
2. SDK/server compile without worker declaration imports from `mass-storage-api`.
3. Existing worker registration/control/report tests remain behaviorally
   equivalent.

## WDP-4 Guard And Proof

Goal: prevent worker declaration contracts from drifting back into
`mass-storage-api`.

Scope:

1. Add or update architecture guards:
   - `xa-mass-worker-runtime` production code must not depend on
     `mass-storage-api`.
   - `mass-storage-api` must not declare `WorkerDeclaration*` contracts.
   - worker declaration row shape must stay declaration-only.
2. Move existing worker declaration shape guard coverage from
   `mass-storage-api` tests to `xa-mass-worker-runtime` tests, or create
   equivalent worker-runtime-local guards before deleting the old coverage.
3. Keep a storage-api guard that fails if `WorkerDeclaration*` contracts are
   reintroduced under `com.xa.mass.storage.api`.
4. Update module docs to show storage modules as adapters for worker
   declaration persistence.
5. Keep `mass-storage-memory` test-scope dependency in worker-runtime only if
   it is still useful for tests; do not treat that as production boundary
   violation.

Acceptance:

1. Guard fails if worker-runtime reintroduces production
   `mass-storage-api` dependency.
2. Guard fails if `mass-storage-api` reintroduces worker declaration contracts.
3. Guard fails if `WorkerDeclarationRecord` gains runtime state or
   worker-level capability fields.
4. Full worker-runtime/storage focused verification passes.

## Suggested Implementation Order

```text
WDP-0 -> WDP-1 -> WDP-2 -> WDP-3 -> WDP-4
```

Do not start by deleting the Maven dependency. First move the contract owner,
then retarget adapter implementations, then remove the dependency.

## Verification Candidates

Initial focused proof set:

```powershell
mvn -pl xa-mass-worker-runtime -am '-Dtest=WorkerManagerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-engine -am '-Dtest=WorkerControlOwnerSliceTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl platform_infra/mass-storage-api,platform_infra/mass-storage-memory -am '-Dtest=WorkerDeclarationStoreContractTest,InMemoryWorkerDeclarationStoreContractTest,StorageBoundaryGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-sdk,xa-mass-server -am -DskipTests compile
```

The exact test list should be corrected in WDP-0 after the caller inventory is
complete.
