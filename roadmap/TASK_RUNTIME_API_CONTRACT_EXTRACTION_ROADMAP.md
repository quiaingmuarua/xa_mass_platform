# Embedded SDK Engine DTO Import Closure Roadmap

Status: proposed closed-loop convergence task.

This roadmap has one bounded objective: remove the current main-source
`sdk/xa-mass-embedded-sdk -> xa-mass-engine` `com.xa.mass.engine.*`
DTO/config-symbol references and add guards so they cannot return.

The file name is retained for existing links from ECSP/TROM. The operative
scope is the title: this is an embedded SDK engine DTO import-closure roadmap,
not a complete task-runtime API extraction roadmap. Adjacent runtime DTOs such
as `TaskResultWindow` / `TaskResultRuntimeRow` are visible current debt, but
they are not completion criteria for this closed loop unless TRA-0 explicitly
promotes them into scope with a separate owner/proof decision.

`xa-mass-task-runtime-api` is the preferred implementation module for shared
task operation, diagnostic, and stage contracts, but creating that module alone
does not complete this roadmap. The roadmap is complete only when the current
embedded SDK source-level engine import leak is gone and guarded.

This roadmap does not close the broader ECSP `EngineConfig` crossing. If
`MassApplicationBuilder` or other embedded SDK assembly code still imports
`com.xa.mass.starter.config.EngineConfig`, that remains an ECSP hardening issue,
not evidence that this DTO closure failed or completed.

This is a hardening follow-up to
[ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
and a small prerequisite / companion to
[TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md).

## Current Code Observations

- `sdk/xa-mass-embedded-sdk` no longer imports engine services/runtime owners
  after ECSP, but main sources still import engine-owned DTO/config types:
  `TaskAppendReceipt`, `TaskDefinitionPatch`, `TaskResumeResult`,
  `TaskStateValidationResult`, `TaskStateResolutionResult`,
  `TaskStageEvidenceResult`, `TaskStageProjection`, and
  `PollingIdleBackoffPolicy`.
- `xa-mass-engine-starter` exposes behavior methods that still return
  engine-owned task operation and stage DTOs to embedded SDK callers.
- `MassApplication` / `MassSdkApplication` also consume result-read runtime DTOs
  from `platform_infra/mass-runtime-api`, currently `TaskResultWindow` and
  `TaskResultRuntimeRow`. Those are adjacent TROM debt, not engine DTO imports;
  this roadmap must classify them as out of scope unless it deliberately
  broadens TRA-0.
- `sdk/xa-mass-embedded-sdk-api` already contains SDK-owned read models such as
  `TaskItemBatchAppendReceipt`, `TaskStageProjectionSnapshot`, and
  `TaskStageEvidenceSnapshot`, but those models are SDK-facing snapshots. They
  do not by themselves create a shared task-runtime API owner.
- `sdk/xa-mass-public-contract` owns narrow HTTP Controller wire DTOs and must
  not absorb embedded runtime operation results just to break imports.
- `xa-mass-kernel-spi` owns low-level kernel/storage/rule SPI and must not
  absorb caller-facing task runtime DTOs just to break imports.
- `EngineConfig` crossing from `engine-starter` into `embedded-sdk` is an ECSP
  boundary hardening issue, not solved by this DTO import closure roadmap.
- Existing architecture guard coverage still treats these engine DTO imports as
  classified exceptions in `MassEngineAssemblyBoundaryTest`. This roadmap must
  flip that proof from "classified exception allowed" to "DTO/config-symbol
  import forbidden" before it can claim completion.
- Some downstream tests consume current SDK diagnostic results as engine DTOs,
  especially server E2E support that imports `TaskStateValidationResult` through
  `taskDiagnostics()`. TRA-2 must update those test callers or prove they are no
  longer in the compile lane.

## Owner Review

The current leak is source-level: embedded SDK main code sees engine DTO/config
types. That must close regardless of the final long-term task-runtime module
shape.

Task operation, diagnostic, and stage contracts should be owned by a
task-runtime-facing API owner. The preferred target is
`xa-mass-task-runtime-api`, because the contracts are neither HTTP wire DTOs,
kernel SPI, nor embedded SDK-only snapshots.

`xa-mass-engine` may continue implementing the old behavior while TROM is not
landed, but it should adapt to the shared contract at the starter/SDK boundary
instead of exporting engine DTOs.

`xa-mass-engine-starter` may depend on engine and convert engine-local objects
into the shared contract. It is not the contract owner.

Result-read runtime DTOs such as `TaskResultWindow` / `TaskResultRuntimeRow`
remain owned by the current runtime API until TROM decides their successor.
They must not be hidden inside this roadmap as if engine DTO import closure had
completed the full task-runtime API boundary.

## Boundary Decision

This roadmap closes only the current `embedded-sdk` main-source
`com.xa.mass.engine.*` type leak.

The preferred contract landing zone is a new top-level module:
`xa-mass-task-runtime-api`.

The module may be created in this roadmap if it is the smallest clean way to
remove the imports. If an existing SDK-owned snapshot already provides the
right public shape, this roadmap may adapt to that snapshot instead. Either
way, the completion proof is the same: embedded SDK main sources must have zero
`com.xa.mass.engine.*` type references and focused tests/guards must pass.

If `xa-mass-task-runtime-api` is created, it must not become a re-export of SDK
snapshots. It owns only shared task-runtime-facing contracts. Its dependency
allowlist should stay intentionally narrow: JDK plus truly shared base/kernel
contracts only when required. It must not depend on `xa-mass-engine`,
`xa-mass-engine-starter`, `xa-mass-embedded-sdk`,
`xa-mass-embedded-sdk-api`, server, transport implementation modules, or infra
runtime implementation modules.

`TaskResultWindow` / `TaskResultRuntimeRow` cleanup is a separate TROM/result
runtime contract decision by default. TRA-0 may record an explicit follow-up, or
may include a narrow result-read contract only if it proves this is required to
close an engine DTO signature path without broadening the roadmap into full
task-runtime migration.

## Current Import Closure Set

| Current engine type | Closure decision |
| --- | --- |
| `TaskAppendReceipt` | Replace at embedded SDK boundary with task-runtime API append receipt or existing SDK `TaskItemBatchAppendReceipt`. |
| `TaskResumeResult` | Replace at embedded SDK boundary with SDK/task-runtime resume outcome. Do not expose engine enum. |
| `TaskStateValidationResult` | Replace with SDK/task-runtime diagnostic validation snapshot. |
| `TaskStateResolutionResult` | Replace with SDK/task-runtime diagnostic resolution snapshot. |
| `TaskStageEvidenceResult` | Replace with SDK/task-runtime stage evidence outcome. |
| `TaskStageProjection` | Replace with SDK/task-runtime stage projection snapshot; do not expose engine-only `recentEvidence` by default. |
| `TaskDefinitionPatch` | Classify before implementation. If only SDK update input needs it, use SDK-owned request shape; if it is shared task-runtime command input, move to task-runtime API. |
| `PollingIdleBackoffPolicy` | Do not move to task-runtime API. TRA-0 must choose one explicit public API outcome: delete the SDK builder hook and update SDK/integration docs/tests, or replace it with a starter-owned public config/callback type that has no engine dependency. No silent deletion and no compatibility alias. |

## Adjacent Runtime DTO Decision

The following current SDK/starter signatures are adjacent to this work but are
not engine DTO import closure by themselves:

| Current type | Current caller | Default decision |
| --- | --- | --- |
| `TaskResultWindow` | `MassApplication`, `MassSdkApplication` result-read adapter | Defer to TROM/result-runtime API unless TRA-0 explicitly broadens result-read closure. |
| `TaskResultRuntimeRow` | `MassApplication`, `MassSdkApplication` result item mapping | Defer to TROM/result-runtime API unless TRA-0 explicitly broadens result-read closure. |

These rows exist to prevent a false completion claim. Removing engine DTO
imports is enough to complete this roadmap only if these runtime DTOs are
recorded as deliberately out of scope or separately closed by the chosen TRA-0
shape.

## Public API Compatibility Decision

`PollingIdleBackoffPolicy` is not just an internal DTO import: it is currently
part of the SDK builder surface. TRA-0 must record the compatibility posture
before TRA-2 changes code:

- `breaking removal`: delete `runtimeReadyDispatchIdleBackoffPolicy(...)`,
  preserve primitive/max-backoff tuning, update SDK docs and tests, and do not
  add an alias;
- `replacement contract`: introduce a starter-owned public callback/config type
  outside `xa-mass-engine`, retarget SDK/starter builder APIs to it, and guard
  the replacement module against engine dependencies.

Leaving the engine-owned `PollingIdleBackoffPolicy` visible from
`sdk/xa-mass-embedded-sdk/src/main/java` is not an allowed completion state.

## Non-Goals

- Do not redesign task-runtime semantics.
- Do not move `TaskWorkRuntime` or `TaskResultRuntime`.
- Do not silently treat `TaskResultWindow` / `TaskResultRuntimeRow` cleanup as
  complete; classify them as out of scope for this roadmap or explicitly add a
  result-read contract slice.
- Do not move HTTP Controller DTOs out of `xa-mass-public-contract`.
- Do not move rule/storage SPI out of `xa-mass-kernel-spi`.
- Do not solve all future task-runtime API shape decisions.
- Do not solve the `EngineConfig` service-locator boundary issue here; that is
  ECSP hardening.
- Do not claim ECSP is complete from this roadmap while embedded SDK assembly
  still imports `EngineConfig`.
- Do not require server route or frontend changes unless a route directly
  exposes one of these engine DTOs.
- Do not preserve old engine DTO surfaces through compatibility aliases after
  in-repo callers move.
- Do not silently remove public SDK builder methods. Public/starter API changes
  must be called out and documented in the owning SDK docs.

## Do Not Start With

Do not start by creating an empty `xa-mass-task-runtime-api` module and calling
the roadmap done. A new module is only useful if current callers move to it.

Do not start by banning the transitive Maven path to `xa-mass-engine`.
`engine-starter` still composes engine during this transition. Close
source-level imports first, then decide whether transitive visibility must be
hidden.

Do not dump the DTOs into `public-contract`, `kernel-spi`, or
`embedded-sdk-api` merely because those modules already exist.

Do not add `xa-mass-task-runtime-api` as a wrapper around
`xa-mass-embedded-sdk-api` snapshots. If SDK snapshots are reused, reuse them
directly as SDK boundary models; if a shared task-runtime contract is needed,
own it in the new module without depending on SDK modules.

## TRA-0 Import Inventory And Shape Decision

Goal: classify the current import leak and choose the minimal contract shape.

Scope:

- Inventory every production `sdk/xa-mass-embedded-sdk/src/main/java` import or
  type reference to `com.xa.mass.engine.*`, including fully qualified names in
  method signatures, generics, Javadocs that define public behavior, and nested
  enum references.
- Separate engine DTO imports from `EngineConfig` / starter-loop config issues,
  and record `EngineConfig` as ECSP residue rather than this roadmap's closure
  target.
- Inventory downstream in-repo compile callers that currently consume these
  engine DTOs through SDK/starter APIs, especially server E2E diagnostic
  support and SDK tests.
- Record adjacent runtime DTOs (`TaskResultWindow`, `TaskResultRuntimeRow`) as
  out of scope, follow-up, or explicitly in scope with a narrow result-read
  contract shape.
- Decide for each symbol whether to:
  - reuse an existing SDK snapshot,
  - add a type to `xa-mass-task-runtime-api`,
  - replace with primitive/simple config,
  - remove the public option,
  - or leave for ECSP hardening because it is not a DTO contract.

Acceptance:

- Inventory lists every current embedded SDK main-source engine import and its
  closure action.
- `TaskDefinitionPatch` and `PollingIdleBackoffPolicy` have explicit actions.
- `PollingIdleBackoffPolicy` includes a public API compatibility decision:
  breaking removal or replacement contract, with doc/test impact named.
- `TaskResultWindow` / `TaskResultRuntimeRow` are classified as out of scope,
  follow-up, or deliberately included with a separate result-read contract.
- The inventory proves whether `xa-mass-task-runtime-api` is necessary for this
  closure or whether existing SDK snapshots cover part of the surface.
- Inventory includes the downstream test/source callers that must move with the
  cutover, or explicitly proves none remain in the compile lane.
- Inventory records that `EngineConfig` crossing remains an ECSP follow-up and
  is not a completion criterion for this roadmap.

## TRA-1 Contract Landing And Adapters

Goal: add only the contract types needed to remove current imports.

Scope:

- If needed, create `xa-mass-task-runtime-api` with only the first required
  contract set.
- Add README and dependency guard if the new module is created.
- If `xa-mass-task-runtime-api` is created, define a narrow dependency
  allowlist. It must not depend on `xa-mass-engine`, `xa-mass-engine-starter`,
  `xa-mass-embedded-sdk`, `xa-mass-embedded-sdk-api`, server, transport
  implementation modules, or infra runtime implementation modules.
- Add engine-starter or SDK adapters that convert engine-local results into
  the chosen SDK/task-runtime contracts.
- Avoid copying engine DTOs wholesale when a narrower snapshot is sufficient.
- If `PollingIdleBackoffPolicy` is replaced rather than removed, add the
  replacement to a starter-owned public contract location and keep it
  dependency-clean from `xa-mass-engine`.

Acceptance:

- Any new module compiles and has no dependency on `xa-mass-engine`.
- Any new `xa-mass-task-runtime-api` module also has no dependency on
  `xa-mass-engine-starter`, `xa-mass-embedded-sdk`,
  `xa-mass-embedded-sdk-api`, server, transport implementation modules, or
  infra runtime implementation modules.
- Added contracts are limited to the current import closure set.
- Engine-local DTOs do not leak through newly added public methods or retained
  starter/SDK public signatures.
- Any replacement for `PollingIdleBackoffPolicy` has no dependency on
  `xa-mass-engine`.

## TRA-2 Embedded SDK Import Cutover

Goal: remove the current engine DTO/config imports from embedded SDK main
sources.

Scope:

- Update `MassApplication`, `MassSdkApplication`, diagnostics operations,
  builder APIs, and related tests to consume the chosen SDK/task-runtime
  contracts.
- Update downstream in-repo callers that compile against changed SDK diagnostic
  or task-operation return types, including server E2E support and tests that
  currently import `TaskStateValidationResult`.
- Remove or replace `runtimeReadyDispatchIdleBackoffPolicy(...)` if it is the
  only reason `PollingIdleBackoffPolicy` crosses into embedded SDK.
- Keep behavior stable for append, resume, diagnostics, and stage evidence
  paths.
- If `runtimeReadyDispatchIdleBackoffPolicy(...)` is removed or reshaped, update
  `sdk/README.md` and `integrations/README.md` when they mention the old caller
  behavior.

Acceptance:

- `rg -n "^import com\.xa\.mass\.engine" sdk/xa-mass-embedded-sdk/src/main/java -g "*.java"`
  returns no matches.
- `rg -n "com\.xa\.mass\.engine\." sdk/xa-mass-embedded-sdk/src/main/java -g "*.java"`
  returns no matches except comments that explicitly point to this roadmap or a
  completed removal note.
- Public signatures in `MassApplication`, `MassSdkApplication`,
  `TaskDiagnosticOperations`, diagnostics implementations, and SDK builder
  option types do not accept, return, or expose engine DTO/config types.
- `MassSdkTest` or narrower focused tests still cover append, resume,
  diagnostics, and stage evidence behavior.
- Downstream server test compile is green for callers that consume SDK
  diagnostics or task-operation results.
- No compatibility aliases preserve the old engine DTO surface.
- SDK/integration docs are updated when a public builder option is removed or
  replaced.

## TRA-3 Guards And Completion Proof

Goal: make the closed-loop claim fail-fast.

Scope:

- Upgrade embedded SDK architecture guard to fail on any main-source
  `com.xa.mass.engine.` type reference, not only import declarations.
- Delete or invert the old `MassEngineAssemblyBoundaryTest`
  `CLASSIFIED_ENGINE_IMPORTS` allowlist entries for `TaskAppendReceipt`,
  `TaskDefinitionPatch`, `TaskResumeResult`, `TaskStateValidationResult`,
  `TaskStateResolutionResult`, `TaskStageEvidenceResult`,
  `TaskStageProjection`, and `PollingIdleBackoffPolicy`. These symbols must not
  remain allowed classified exceptions after TRA-2.
- If `xa-mass-task-runtime-api` is created, add a guard/enforcer that prevents
  dependencies on engine, engine-starter, embedded SDK, embedded SDK API,
  server, transport runtime/implementation modules, and infra implementation
  modules.
- Add a focused guard or reflection check preventing starter-facing public
  methods consumed by embedded SDK from accepting or returning engine DTOs.
- Add a focused guard or inventory check that fails if `TaskResultWindow` /
  `TaskResultRuntimeRow` remain unclassified after TRA-0.
- Add a focused guard or source check that fails if `PollingIdleBackoffPolicy`
  remains visible from embedded SDK main source after TRA-2.
- Record whether Maven transitive `xa-mass-engine` visibility remains deferred
  and why.

Acceptance:

- Embedded SDK main-source engine imports and type references are zero.
- Guards fail when an engine DTO import, fully qualified reference, method
  parameter, return type, or nested enum reference is reintroduced.
- No guard retains an allowlist path where the current engine DTO/config symbols
  can remain classified exceptions in embedded SDK main source.
- Contract module dependency cleanliness is guarded if the module exists.
- `PollingIdleBackoffPolicy` removal/replacement is guarded.
- Adjacent runtime DTOs are either out-of-scope with an explicit inventory row or
  covered by a named result-read contract decision.
- Maven transitive visibility is either closed or recorded as a follow-up with
  a named blocker.

## Suggested Implementation Order

1. Execute TRA-0 and decide exact shapes.
2. Land only the required contract/adapters from TRA-1.
3. Cut over embedded SDK imports in TRA-2.
4. Add TRA-3 guards and residue scan.
5. Update ECSP/TROM wording from temporary exception to completed closure.

## Verification Candidates

```powershell
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am -Dtest=MassSdkTest test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=MassEngineAssemblyBoundaryTest,*GuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl xa-mass-engine-starter -am test
.\mvnw.cmd -q -pl xa-mass-server -am -DskipTests test-compile
```

If `xa-mass-task-runtime-api` is created:

```powershell
.\mvnw.cmd -q -pl xa-mass-task-runtime-api -am test
```

Residue scan:

```powershell
rg -n "^import com\.xa\.mass\.engine" sdk/xa-mass-embedded-sdk/src/main/java -g "*.java"
rg -n "com\.xa\.mass\.engine\." sdk/xa-mass-embedded-sdk/src/main/java -g "*.java"
rg -n "TaskAppendReceipt|TaskDefinitionPatch|TaskResumeResult|TaskStateValidationResult|TaskStateResolutionResult|TaskStageEvidenceResult|TaskStageProjection|PollingIdleBackoffPolicy" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-engine-starter/src/main/java -g "*.java"
rg -n "TaskAppendReceipt|TaskDefinitionPatch|TaskResumeResult|TaskStateValidationResult|TaskStateResolutionResult|TaskStageEvidenceResult|TaskStageProjection|PollingIdleBackoffPolicy" sdk/xa-mass-embedded-sdk/src/test/java xa-mass-server/src/test/java -g "*.java" -g "!**/MassEngineAssemblyBoundaryTest.java" -g "!**/architecture/**"
rg -n "CLASSIFIED_ENGINE_IMPORTS|classified engine import|engine DTO" sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/starter/MassEngineAssemblyBoundaryTest.java sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/sdk/architecture -g "*.java"
rg -n "TaskResultWindow|TaskResultRuntimeRow" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-engine-starter/src/main/java -g "*.java"
```

## Roadmap Completion Criteria

- `sdk/xa-mass-embedded-sdk/src/main/java` has zero
  `com.xa.mass.engine.*` imports and type references.
- Append, resume, diagnostic, and stage evidence SDK paths use SDK-owned or
  task-runtime API contracts at the embedded SDK boundary.
- `PollingIdleBackoffPolicy` no longer appears in embedded SDK main-source
  imports or public signatures, and any public SDK builder behavior change is
  documented.
- Starter/SDK public signatures consumed by embedded SDK do not accept or return
  engine DTO/config types.
- `TaskResultWindow` / `TaskResultRuntimeRow` are either explicitly out of scope
  with a TROM/result-runtime follow-up or removed through an explicitly chosen
  result-read contract slice.
- Guards prevent source-level engine DTO/config imports and type references from
  returning.
- Existing guard allowlists no longer preserve the removed engine DTO/config
  symbols as classified exceptions.
- If created, `xa-mass-task-runtime-api` is documented and dependency-clean.
- ECSP/TROM no longer list these DTO imports as open temporary exceptions.
- ECSP may still list `EngineConfig` crossing as open residue; that residue does
  not block this roadmap's completion unless this roadmap explicitly broadens.
