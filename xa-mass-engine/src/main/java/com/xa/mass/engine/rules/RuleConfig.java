package com.xa.mass.engine.rules;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;

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
        basicRule.setContent("isWorkerAvailable == true && isWorkerLocked == false");
        basicRule.setDescription("Worker must be available and unlocked");
        rules.add(basicRule);

        RuleDefinition workerContextRule = new RuleDefinition();
        workerContextRule.setId("worker_context_status_check");
        workerContextRule.setType(RuleType.QL_EXPRESS);
        workerContextRule.setContent("hasWorkerContext == false || isWorkerContextAllocatable == true");
        workerContextRule.setDescription("Worker without context is allowed; otherwise worker context must be allocatable");
        rules.add(workerContextRule);

        RuleDefinition routingRule = new RuleDefinition();
        routingRule.setId("routing_code_match");
        routingRule.setType(RuleType.QL_EXPRESS);
        routingRule.setContent(
                "taskHasRoutingRequirement == false || workerContextMatchesRoutingCode == true");
        routingRule.setDescription("Routing code, when required, must match one of the worker context routing tags");
        rules.add(routingRule);

        RuleDefinition capabilityRule = new RuleDefinition();
        capabilityRule.setId("worker_capability_check");
        capabilityRule.setType(RuleType.QL_EXPRESS);
        capabilityRule.setContent(
                "((taskEventCode == null || taskEventCode == '') && supportsProject == true) "
                        + "|| ((taskEventCode != null && taskEventCode != '') && supportsEvent == true)");
        capabilityRule.setDescription(
                "Project support gates non-SDK tasks; SDK event tasks are matched by explicit worker event capability");
        rules.add(capabilityRule);

        RuleDefinition loadRule = new RuleDefinition();
        loadRule.setId("worker_load_check");
        loadRule.setType(RuleType.QL_EXPRESS);
        loadRule.setContent("appCount < 10");
        loadRule.setDescription("Worker-supported project count must stay below 10");
        rules.add(loadRule);

        return rules;
    }

    public static List<RuleDefinition> getAdvancedWorkerMatchRules() {
        List<RuleDefinition> rules = getDefaultWorkerMatchRules();

        RuleDefinition versionRule = new RuleDefinition();
        versionRule.setId("agent_version_check");
        versionRule.setType(RuleType.QL_EXPRESS);
        versionRule.setContent("agentVersion != null && agentVersion.startsWith('1.')");
        versionRule.setDescription("Agent version must be 1.x");
        rules.add(versionRule);

        RuleDefinition frequencyRule = new RuleDefinition();
        frequencyRule.setId("usage_frequency_check");
        frequencyRule.setType(RuleType.QL_EXPRESS);
        frequencyRule.setContent("lastUsedTime == null || (System.currentTimeMillis() - lastUsedTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000) > 300000");
        frequencyRule.setDescription("Worker must not have been used within the last 5 minutes");
        rules.add(frequencyRule);

        return rules;
    }

    public static List<RuleDefinition> getProjectSpecificRules(String projectName) {
        List<RuleDefinition> rules = getDefaultWorkerMatchRules();

        if ("demoApp".equals(projectName)) {
            RuleDefinition demoRule = new RuleDefinition();
            demoRule.setId("demo_app_specific");
            demoRule.setType(RuleType.QL_EXPRESS);
            demoRule.setContent("appCount <= 5 && agentVersion.startsWith('1.0')");
            demoRule.setDescription("demoApp requires appCount <= 5 and agentVersion 1.0.x");
            rules.add(demoRule);
        }

        return rules;
    }

    public static List<RuleDefinition> getLooseWorkerMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_worker_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("isWorkerAvailable == true");
        basicRule.setDescription("Worker must be available");
        rules.add(basicRule);

        RuleDefinition routingRule = new RuleDefinition();
        routingRule.setId("routing_code_match");
        routingRule.setType(RuleType.QL_EXPRESS);
        routingRule.setContent(
                "taskHasRoutingRequirement == false || workerContextMatchesRoutingCode == true");
        routingRule.setDescription("Routing code, when required, must match one of the worker context routing tags");
        rules.add(routingRule);

        return rules;
    }
}
