package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;

import java.util.ArrayList;
import java.util.List;

/**
 * Default rule definitions for worker matching.
 */
public class RuleConfig {

    public static List<RuleDefinition> getDefaultWorkerMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_worker_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("hasWorkerSchedulingResource == true");
        basicRule.setDescription("Worker scheduling resource identity must be known");
        rules.add(basicRule);

        RuleDefinition schedulingResourceRule = new RuleDefinition();
        schedulingResourceRule.setId("worker_scheduling_resource_check");
        schedulingResourceRule.setType(RuleType.QL_EXPRESS);
        schedulingResourceRule.setContent("matchesTargetWorkerAttributes == true");
        schedulingResourceRule.setDescription("Target worker attributes, when required, must match worker attributes");
        rules.add(schedulingResourceRule);

        RuleDefinition routingRule = new RuleDefinition();
        routingRule.setId("routing_code_match");
        routingRule.setType(RuleType.QL_EXPRESS);
        routingRule.setContent(
                "taskHasRoutingRequirement == false || workerSchedulingMatchesRoutingCode == true");
        routingRule.setDescription("Routing code, when required, must match one of the worker scheduling routing tags");
        rules.add(routingRule);

        return rules;
    }

    public static List<RuleDefinition> getAdvancedWorkerMatchRules() {
        return getDefaultWorkerMatchRules();
    }

    public static List<RuleDefinition> getProjectSpecificRules(String projectName) {
        return getDefaultWorkerMatchRules();
    }

    public static List<RuleDefinition> getLooseWorkerMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_worker_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("hasWorkerSchedulingResource == true");
        basicRule.setDescription("Worker scheduling resource identity must be known");
        rules.add(basicRule);

        RuleDefinition routingRule = new RuleDefinition();
        routingRule.setId("routing_code_match");
        routingRule.setType(RuleType.QL_EXPRESS);
        routingRule.setContent(
                "taskHasRoutingRequirement == false || workerSchedulingMatchesRoutingCode == true");
        routingRule.setDescription("Routing code, when required, must match one of the worker scheduling routing tags");
        rules.add(routingRule);

        return rules;
    }
}
