# JSON-DSL Boundary Convergence Roadmap

Status: active direction document.

This roadmap owns the remaining `xa-mass-base` `qlexpress4` dependency after
rule evaluator extraction. It is intentionally separate from
`xa-mass-engine/doc/roadmap/RULE_BOUNDARY_CONVERGENCE_ROADMAP.md`: matching
rules no longer need storage-owned evaluator code, while JSON-DSL is a legacy
generator/runtime concern under the base module.

## Current Code Observations

- `xa-mass-base` contains 38 production JSON-DSL classes under
  `com.xa.mass.base.jsondsl`.
- Direct QLExpress imports in base production code are:
  - `jsondsl/eval/QLExpressEngine.java`
  - `jsondsl/builtin/BuiltinFunctions.java`
- The only production caller outside `xa-mass-base` is
  `xa-mass-engine.monkey.MonkeyGenerator`.
- `MonkeyGenerator` is a dev/mock fixture generator and has no current
  production call sites.
- Engine has a guard preventing matching packages from depending on JSON-DSL or
  monkey fixtures.

## Boundary Decision

`xa-mass-base` should own stable shared models and base infrastructure. It
should not own an executable expression runtime unless that runtime is a core
platform contract.

JSON-DSL is currently support/generator infrastructure, not task kernel truth,
worker matching truth, storage truth, or transport truth.

Implications:

- do not use JSON-DSL as a new mainline extension point
- do not make matching, assignment, result convergence, or transport depend on
  JSON-DSL
- do not keep QLExpress in `xa-mass-base` merely for fixture generation
- remove unused production fixture paths instead of preserving compatibility
  aliases

## Target Shape

Preferred target:

```text
xa-mass-base
  -> no qlexpress4 dependency
  -> no executable JSON-DSL runtime
  -> keeps only stable base model/enums/infrastructure

fixture/demo generation
  -> server/bootstrap/test/integration owner if still needed
  -> deterministic data generation, not engine kernel dependency

JSON-DSL runtime
  -> removed if no live owner exists
  -> otherwise moved to a dedicated support module outside base
```

Do not introduce a generic expression-plugin framework for JSON-DSL. The goal
is boundary cleanup, not a new DSL product surface.

## Non-Goals

1. No rewrite of rule matching.
2. No replacement of QLExpress for matching rules.
3. No public JSON-DSL API design.
4. No compatibility package aliases for `com.xa.mass.base.jsondsl`.
5. No new fixture generation path inside engine matching or runtime kernel.

## Slice JDB-0: Inventory Live Usage

Goal: prove whether JSON-DSL has any live production owner.

Scope:

1. List all production and test callers of `com.xa.mass.base.jsondsl`.
2. Classify each caller as:
   - kernel/runtime path
   - demo/bootstrap fixture path
   - test-only helper
   - deprecated example
3. Confirm whether `MonkeyGenerator` has any production caller.
4. Record which JSON-DSL packages are pure model/parser code and which execute
   expressions.

Acceptance:

1. Every non-base JSON-DSL caller is classified.
2. Every direct QLExpress import is listed.
3. The roadmap states whether the next step is delete or move.

## Slice JDB-1: Remove Engine Production Fixture Residue

Goal: stop engine production code from depending on JSON-DSL fixture generation.

Scope:

1. Delete `engine.monkey.MonkeyGenerator` if it has no production callers.
2. If a fixture generator is still needed, move it to test, server bootstrap,
   or integrations according to the real caller.
3. Update guards so engine matching and runtime kernel cannot import JSON-DSL.

Acceptance:

1. `xa-mass-engine` main sources no longer import
   `com.xa.mass.base.jsondsl`.
2. Existing demo/bootstrap tests either use their owning fixture path or no
   longer reference JSON-DSL.

## Slice JDB-2: Remove Or Move JSON-DSL Runtime From Base

Goal: make `xa-mass-base` stop owning executable expression runtime code.

Scope:

1. If JDB-0 proves JSON-DSL has no live owner, delete base JSON-DSL production
   code and its tests.
2. If JSON-DSL still has a live support owner, move it to a dedicated support
   module outside `xa-mass-base`.
3. Keep any retained module out of engine kernel and worker matching.

Acceptance:

1. `xa-mass-base` main sources no longer import QLExpress.
2. `xa-mass-base` no longer declares `qlexpress4`.
3. Any retained JSON-DSL module has an explicit non-kernel owner.

## Slice JDB-3: Guard And Proof

Goal: prevent JSON-DSL from drifting back into base/kernel ownership.

Scope:

1. Add or update architecture guards:
   - `xa-mass-base` must not depend on `qlexpress4`
   - engine matching packages must not depend on JSON-DSL
   - engine runtime kernel packages must not depend on fixture generators
2. Run focused base/engine compile and affected tests.

Acceptance:

1. Guard fails if base regains QLExpress.
2. Guard fails if matching imports JSON-DSL or monkey fixtures.
3. A dependency proof confirms `xa-mass-base` has no direct `qlexpress4`
   dependency.

## Verification Candidates

```powershell
mvn -pl xa-mass-base -am test
```

```powershell
mvn -pl xa-mass-engine -am '-Dtest=JsonDslBoundaryGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-base dependency:tree
```
