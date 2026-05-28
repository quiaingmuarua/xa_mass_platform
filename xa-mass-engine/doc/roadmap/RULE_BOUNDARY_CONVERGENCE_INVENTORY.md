# Rule Boundary Convergence Inventory

Status: RBC-0 inventory for `RULE_BOUNDARY_CONVERGENCE_ROADMAP.md`.

This document records current rule call sites, method-level usage, route
wiring, evaluator ownership, and engine dependency residues before any behavior
or package movement.

## Summary

Current state:

- Matching needs only `RuleManager.getDefaultRules()` and
  `RuleManager.evaluate(rule, context)`.
- Rule CRUD and evaluator lifecycle are exposed through the same broad
  `RuleManager` / `RuleStorage` surfaces.
- SDK and bootstrap code use rule storage as an admin/setup surface.
- Server rule API is read-only, but route naming says runtime.
- QLExpress ownership is split incorrectly:
  - concrete rule evaluators live in storage implementation modules
  - `qlexpress4` is declared by `xa-mass-base`
  - `xa-mass-base` also has jsondsl runtime classes that directly import
    QLExpress, so moving the dependency requires an owner decision beyond rule
    evaluator files
- `xa-mass-engine` production depends on `mass-runtime-memory` only because
  `TaskManager` convenience constructors instantiate
  `InMemoryTaskResultRuntime` directly.

## RuleManager Callers

| Caller | Methods used | Classification | Required movement |
|---|---|---|---|
| `RuleBasedTaskWorkerMatchingStrategy` | `getDefaultRules`, `evaluate` | matching-time evaluation | Replace with `MatchingRuleSetProvider` and `MatchingRuleEvaluator`. |
| `EngineConfig.getRuleManager()` | constructs `new RuleManager<>(getRuleStorage())` after default seed | bootstrap/runtime assembly | Replace with explicit rule setup/query surface; stop exposing engine manager. |
| `EngineConfig.ensureDefaultRulesInitialized()` | `RuleManagerFactory.getDefaultRuleManager(getRuleStorage())` | bootstrap/sample fixture setup | Move default rule seeding to bootstrap/admin setup owner. |
| `MassEngine.start()` | obtains `config.getRuleManager()` and passes it into matching strategy | runtime assembly | Pass narrow matching rule contracts instead. |
| `TaskSchedulingTestHarness` | constructs `RuleManager` with `InMemoryRuleStorage` | test-only convenience | Replace with test fixture for narrow matching contracts. |
| `RuleManagerTest` | all CRUD/evaluator/evaluation manager methods | test-only coverage of broad manager | Split into definition-store, evaluator-registry, and matching-evaluator tests. |
| `RuleBasedTaskWorkerMatchingStrategyTest` | constructs `RuleManager`; strategy uses matching methods | matching test setup | Replace setup with narrow matching contracts. |
| `MassSdkTest` | `config.getRuleManager()` in reflection/default-rule tests | SDK bootstrap tests | Update when SDK no longer exposes engine manager. |

## RuleStorage Callers

| Caller | Methods used | Classification | Required movement |
|---|---|---|---|
| `RuleManager` | `addRule`, `addRules`, `deleteRule`, `getAllRules`, `getEvaluator`, `getRule`, `updateRule`, `getRulesByType`, `registerEvaluator`, `getRegisteredEvaluatorTypes`, `removeEvaluator`, `clear` | broad mixed facade | Delete or narrow after replacement contracts exist. |
| `RuleManagerFactory` | passes storage to manager, then adds configured rules through manager | bootstrap/sample fixture setup | Replace with explicit rule-definition setup path. |
| `EngineConfig` | stores `RuleStorage`, resets default seeding on replacement | bootstrap/runtime assembly | Keep definition store config, remove seeded manager dependency. |
| `MassEngineBuilder` | config setter for `RuleStorage` | SDK assembly | Keep as definition-store configuration or rename after contract split. |
| `MassApplicationBuilder.EngineBuilder` | config setter for `RuleStorage` | SDK assembly | Keep as definition-store configuration or rename after contract split. |
| `MassSdk.EngineOptions` | config setter for `RuleStorage` | SDK assembly | Keep as definition-store configuration or rename after contract split. |
| `MassSdkApplication.listDefaultRules()` | `getAllRules` | admin/control-plane definition read | Move to rule-definition query surface. |
| `MassSdkApplication.listRegisteredEvaluatorTypes()` | `getRegisteredEvaluatorTypes` | evaluator assembly/diagnostic | Move to evaluator registry query if still needed. |
| `MassSdkApplication.replaceDefaultRules()` | `clear`, `addRules` | admin/bootstrap rule write | Move to rule-definition setup surface. |
| `JdbcStorageRuntime` | owns and exposes `RuleStorage` implementation | storage assembly | Keep definition-store ownership, remove evaluator auto-registration from storage. |
| `InMemoryRuleStorage` | implements all `RuleStorage` methods and owns evaluator map | storage implementation + evaluator leak | Keep definition storage, remove evaluator registry ownership. |
| `JdbcRuleStorage` | implements all `RuleStorage` methods and owns evaluator map | storage implementation + evaluator leak | Keep definition storage, remove evaluator registry ownership. |
| Storage tests | definition and evaluator metadata assertions | storage tests | Update evaluator metadata assertions to new registry owner. |

## RuleOperations And Server Route Callers

| Caller | Methods/routes used | Classification | Required movement |
|---|---|---|---|
| `RuleApiController` | `GET /api/v1/runtime/rules`, `GET /api/v1/runtime/rules/meta`; calls `RuleOperations.listDefaultRules`, `listRuleTypes`, `listRegisteredEvaluatorTypes` | admin/control-plane read with runtime route name | Rename/remove route in RBC-5. |
| `ApiRouteAuthorizationCatalog` | hardcoded GET checks for `/api/v1/runtime/rules` and `/api/v1/runtime/rules/meta` | route/auth wiring | Update with renamed route. |
| `RuleApiControllerTest` | tests old runtime rule routes | route test | Update to renamed route or deleted route behavior. |
| `FrontendConsoleController` | redirect/resource mappings for `/status/rules` and `/resources/rules` | console route wiring | Update if console route naming changes with rule API. |
| `FrontendConsoleRoutingService` | route set includes `/status/rules`, `/resources/rules` | console route wiring | Update with console route decision. |
| `ControlConsoleRoutingIntegrationTest` | asserts `/status/rules` redirects to `/resources/rules` | console routing test | Update with route decision. |
| `MassSdkApplication` | implements `RuleOperations` | SDK admin/query surface | Replace storage-backed broad calls with split definition/evaluator surfaces. |

## QLExpress Dependency And Implementation Locations

Current implementation locations:

- `platform_infra/mass-storage-memory/.../QLExpressRuleEvaluator.java`
- `platform_infra/mass-storage-jdbc/.../JdbcQlExpressRuleEvaluator.java`
- `InMemoryRuleStorage` auto-registers `RuleType.QL_EXPRESS`.
- `JdbcRuleStorage` auto-registers `RuleType.QL_EXPRESS`.

Current Maven dependency location:

- `xa-mass-base/pom.xml` declares `com.alibaba:qlexpress4`.

Current direct QLExpress imports outside storage evaluators:

- `xa-mass-base/src/main/java/com/xa/mass/base/jsondsl/eval/QLExpressEngine.java`
- `xa-mass-base/src/main/java/com/xa/mass/base/jsondsl/builtin/BuiltinFunctions.java`
- `xa-mass-base/src/test/java/com/xa/mass/base/jsondsl/QLExpressBuiltinTest.java`

RBC-1 decision required:

- If `jsondsl` is still a base-level model utility, then removing QLExpress
  from `xa-mass-base` requires moving jsondsl runtime/eval classes to a runtime
  or rule/evaluator module first.
- If `jsondsl` is only a generator/test/support facility, it should not keep
  QLExpress in base production scope.
- QLExpress evaluator ownership cannot be solved by moving only the two storage
  evaluator classes while base still owns QLExpress through jsondsl.

## Engine Runtime-Memory Dependency Residue

Current direct main-source references:

- `TaskManager(TaskStorage, TaskDetailStore, TaskWorkRuntime)`
- `TaskManager(TaskStorage, TaskDetailStore, TaskTerminalPolicy, TaskWorkRuntime)`
- `TaskManager(TaskStorage, TaskDetailStore, TaskWorkRuntime, ExecutionEventSink)`
- `TaskManager(TaskStorage, TaskDetailStore, TaskTerminalPolicy, TaskWorkRuntime, ExecutionEventSink)`

Each constructor instantiates:

```java
new com.xa.mass.runtime.memory.InMemoryTaskResultRuntime()
```

Classification:

- assembly/default-constructor residue

Required movement:

- remove these convenience constructors or require explicit
  `TaskResultRuntime`
- keep in-memory result runtime defaults in server/SDK/test composition
- change `xa-mass-engine` `mass-runtime-memory` dependency to test scope or
  remove it after main sources stop referencing it

## Call Sites That Mutate Rule Definitions Through Engine Package Types

- `RuleManager.addDefaultRule(...)`
- `RuleManager.addDefaultRules(...)`
- `RuleManager.removeDefaultRule(...)`
- `RuleManager.updateRule(...)`
- `RuleManager.deleteRule(...)`
- `RuleManager.clear()`
- `RuleManagerFactory.getDefaultRuleManager(...)`
- `RuleManagerFactory.getProjectRuleManager(...)`
- `RuleManagerFactory.getLooseRuleManager(...)`
- `EngineConfig.ensureDefaultRulesInitialized()`
- SDK tests that call `EngineConfig.getRuleManager()` or depend on seeded
  manager behavior

These are the call sites that must move before `RuleManager` can be deleted or
narrowed to a pure matching contract.

## Evaluator Registration Stored Inside RuleStorage

Current dependents:

- `RuleManager.evaluate(...)` calls `ruleStorage.getEvaluator(...)`.
- `RuleManager.registerEvaluator(...)`, `getEvaluator(...)`,
  `getRegisteredEvaluatorTypes(...)`, and `removeEvaluator(...)` pass through
  to storage.
- `MassSdkApplication.listRegisteredEvaluatorTypes()` reads evaluator metadata
  from `RuleStorage`.
- `InMemoryRuleStorage.clear()` re-registers QLExpress.
- `JdbcRuleStorage.clear()` re-registers QLExpress.
- Storage tests assert evaluator availability through storage implementations.

This confirms RBC-2 must split evaluator registry before deleting broad
manager methods.

## RBC-0 Completion Notes

This inventory satisfies RBC-0 acceptance:

1. Current rule callers are classified.
2. Method-level usage is recorded for the broad rule surfaces.
3. Required movement before `RuleManager` narrowing is named.
4. Engine dependency residues are separated from kernel contract dependencies.
5. In-repo route/auth/console callers for rule route rename are named.
6. No behavior changes are made by this inventory.
