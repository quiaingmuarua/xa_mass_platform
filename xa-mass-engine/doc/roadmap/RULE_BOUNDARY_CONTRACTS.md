# Rule Boundary Contracts

Status: RBC-1 contract and owner decision for
`RULE_BOUNDARY_CONVERGENCE_ROADMAP.md`.

This document defines the target rule contracts before code movement. It is
not proof that current implementation already follows these contracts.

## Owner Decisions

| Role | Owner | Current implementation | Target movement |
|---|---|---|---|
| Rule definition persistence | `mass-storage-api` contract, memory/JDBC implementations | `RuleStorage` mixes definition persistence and evaluator registry | Keep definition methods in storage; remove evaluator lifecycle. |
| Rule evaluator registry | `xa-mass-engine` rule runtime assembly | evaluator map inside `InMemoryRuleStorage` and `JdbcRuleStorage` | Move registration/lookup out of storage. |
| Matching rule-set provider | `xa-mass-engine` matching/rules boundary | `RuleManager.getDefaultRules()` | Provide active/default worker matching rules without CRUD verbs. |
| Matching rule evaluator | `xa-mass-engine` matching/rules boundary | `RuleManager.evaluate(rule, context)` | Evaluate a single `RuleDefinition` against `WorkerMatchContext`. |
| QLExpress rule evaluator implementation | `xa-mass-engine` rule runtime assembly for this roadmap | `mass-storage-memory` and `mass-storage-jdbc` evaluator classes | Move concrete evaluator out of storage implementations. |
| Rule admin/bootstrap writes | SDK/server/admin bootstrap surfaces | `RuleManagerFactory`, `EngineConfig`, `MassSdkApplication.replaceDefaultRules` | Move writes to explicit rule-definition setup surface. |

Decision: do not create a dedicated rule-runtime Maven module in this roadmap
unless implementation proves engine becomes too implementation-heavy. Start
with engine-owned rule runtime assembly because matching is the only production
consumer of rule evaluation today.

## Target Contract Sketch

Names may change during implementation. The important part is the shape.

```java
public interface MatchingRuleSetProvider {
    List<RuleDefinition> activeWorkerMatchingRules();
}
```

```java
public interface MatchingRuleEvaluator<C> {
    boolean evaluate(RuleDefinition rule, C context) throws Exception;
}
```

```java
public interface RuleEvaluatorRegistry<C> {
    void registerEvaluator(RuleType ruleType, RuleEvaluator<C> evaluator);

    Optional<RuleEvaluator<C>> evaluator(RuleType ruleType);

    List<RuleType> registeredEvaluatorTypes();

    boolean removeEvaluator(RuleType ruleType);
}
```

Definition storage keeps only definition persistence:

```java
public interface RuleDefinitionStore {
    void addRule(RuleDefinition rule);

    Optional<RuleDefinition> getRule(String ruleId);

    boolean updateRule(RuleDefinition rule);

    boolean deleteRule(String ruleId);

    List<RuleDefinition> getAllRules();

    List<RuleDefinition> getRulesByType(RuleType ruleType);

    void addRules(Collection<RuleDefinition> rules);

    void deleteRules(Collection<String> ruleIds);

    void clear();
}
```

Implementation may keep the current `RuleStorage` name for the definition
store during migration, but evaluator methods must move out.

## Matching Surface

`RuleBasedTaskWorkerMatchingStrategy` should only need:

```text
provider.activeWorkerMatchingRules()
evaluator.evaluate(rule, WorkerMatchContext)
```

It should not know:

- how rule definitions are persisted
- how rules are added, updated, deleted, or cleared
- how evaluator implementations are registered
- whether default/sample rules were seeded by SDK, server, tests, or admin
  bootstrap

## Evaluator Registry Surface

The registry is runtime assembly state, not durable storage truth.

It may expose registered evaluator types for diagnostics and admin read views,
but rule matching should not mutate evaluator registration.

The initial registry can be simple in-memory process state. No distributed
registry or plugin framework is part of this roadmap.

## QLExpress Dependency Decision

QLExpress remains the default evaluator language, but its dependency owner must
change.

Current blockers:

- `xa-mass-base` declares `qlexpress4`.
- `xa-mass-base` production code imports QLExpress in jsondsl runtime classes.
- storage implementation modules import QLExpress for rule evaluators.

Target:

- storage modules do not import QLExpress
- base model module does not carry QLExpress only for rule evaluation
- concrete QLExpress rule evaluator lives with engine rule runtime assembly

Open implementation decision before removing `qlexpress4` from
`xa-mass-base`:

- move jsondsl runtime/eval classes out of `xa-mass-base`, or
- split jsondsl API/model from jsondsl QLExpress runtime implementation, or
- explicitly declare jsondsl as the separate reason base still carries
  QLExpress and handle it in a follow-up module-boundary roadmap

Do not claim RBC-2 complete while `xa-mass-base` still carries `qlexpress4`
for unclassified production code.

## Route/API Boundary

Rule definitions are control-plane/admin data, not runtime truth.

The current route name `/api/v1/runtime/rules` should not survive RBC-5.

All route changes must cover:

- controller mapping
- route authorization catalog
- frontend console routing and redirects
- controller and routing tests
- internal API reference

No old-path compatibility alias is required in this pre-release internal
project.

## Engine Dependency Boundary

Engine may depend on storage contracts for current kernel truth and rule
definition reads during migration.

Engine must not depend on:

- storage implementations in production code
- runtime memory implementations in production code
- CRUD-shaped rule managers in matching code

`mass-runtime-memory` removal is tracked as RBC-6 because it is an engine
production dependency issue independent of rule evaluator extraction.
