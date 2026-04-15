package com.xa.mass.engine.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Default rule definitions for device matching.
 */
public class RuleConfig {

    public static List<RuleDefinition> getDefaultDeviceMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_device_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("isDeviceAvailable == true && isDeviceLocked == false");
        basicRule.setDesc("Device must be available and unlocked");
        rules.add(basicRule);

        RuleDefinition tokenRule = new RuleDefinition();
        tokenRule.setId("token_status_check");
        tokenRule.setType(RuleType.QL_EXPRESS);
        tokenRule.setContent("isTokenAllocatable == true && isTokenAvailable == true");
        tokenRule.setDesc("Token must be allocatable and available");
        rules.add(tokenRule);

        RuleDefinition routingCountryRule = new RuleDefinition();
        routingCountryRule.setId("routing_country_match");
        routingCountryRule.setType(RuleType.QL_EXPRESS);
        routingCountryRule.setContent(
                "tokenAttributeCountryMatchesRoutingCountry == true || tokenChannelMatchesRoutingCountry == true");
        routingCountryRule.setDesc("Routing country must be satisfied by token attribute or token channel");
        rules.add(routingCountryRule);

        RuleDefinition appRule = new RuleDefinition();
        appRule.setId("app_support_check");
        appRule.setType(RuleType.QL_EXPRESS);
        appRule.setContent("supportsProject == true");
        appRule.setDesc("Device must support the task project");
        rules.add(appRule);

        RuleDefinition loadRule = new RuleDefinition();
        loadRule.setId("device_load_check");
        loadRule.setType(RuleType.QL_EXPRESS);
        loadRule.setContent("appCount < 10");
        loadRule.setDesc("Device-supported project count must stay below 10");
        rules.add(loadRule);

        return rules;
    }

    public static List<RuleDefinition> getAdvancedDeviceMatchRules() {
        List<RuleDefinition> rules = getDefaultDeviceMatchRules();

        RuleDefinition versionRule = new RuleDefinition();
        versionRule.setId("agent_version_check");
        versionRule.setType(RuleType.QL_EXPRESS);
        versionRule.setContent("agentVersion != null && agentVersion.startsWith('1.')");
        versionRule.setDesc("Agent version must be 1.x");
        rules.add(versionRule);

        RuleDefinition frequencyRule = new RuleDefinition();
        frequencyRule.setId("usage_frequency_check");
        frequencyRule.setType(RuleType.QL_EXPRESS);
        frequencyRule.setContent("lastUsedTime == null || (System.currentTimeMillis() - lastUsedTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000) > 300000");
        frequencyRule.setDesc("Device must not have been used within the last 5 minutes");
        rules.add(frequencyRule);

        return rules;
    }

    public static List<RuleDefinition> getProjectSpecificRules(String projectName) {
        List<RuleDefinition> rules = getDefaultDeviceMatchRules();

        if ("demoApp".equals(projectName)) {
            RuleDefinition demoRule = new RuleDefinition();
            demoRule.setId("demo_app_specific");
            demoRule.setType(RuleType.QL_EXPRESS);
            demoRule.setContent("appCount <= 5 && agentVersion.startsWith('1.0')");
            demoRule.setDesc("demoApp requires appCount <= 5 and agentVersion 1.0.x");
            rules.add(demoRule);
        }

        return rules;
    }

    public static List<RuleDefinition> getLooseDeviceMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_device_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("isDeviceAvailable == true");
        basicRule.setDesc("Device must be available");
        rules.add(basicRule);

        RuleDefinition routingCountryRule = new RuleDefinition();
        routingCountryRule.setId("routing_country_match");
        routingCountryRule.setType(RuleType.QL_EXPRESS);
        routingCountryRule.setContent(
                "tokenAttributeCountryMatchesRoutingCountry == true || tokenChannelMatchesRoutingCountry == true");
        routingCountryRule.setDesc("Routing country must be satisfied by token attribute or token channel");
        rules.add(routingCountryRule);

        return rules;
    }
}
