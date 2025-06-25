package com.xa.mass.engine.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则配置类
 * 用于管理设备匹配规则的配置
 */
public class RuleConfig {

    /**
     * 获取默认的设备匹配规则
     */
    public static List<RuleDefinition> getDefaultDeviceMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        // 规则1: 基础设备状态检查
        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_device_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("isDeviceAvailable == true && isDeviceLocked == false");
        basicRule.setDesc("设备必须在线且未被锁定");
        rules.add(basicRule);

        // 规则2: Token状态检查
        RuleDefinition tokenRule = new RuleDefinition();
        tokenRule.setId("token_status_check");
        tokenRule.setType(RuleType.QL_EXPRESS);
        tokenRule.setContent("isTokenAllocatable == true && isTokenAvailable == true");
        tokenRule.setDesc("Token必须可分配且可用");
        rules.add(tokenRule);

        // 规则3: 国家/地区匹配
        RuleDefinition countryRule = new RuleDefinition();
        countryRule.setId("country_match");
        countryRule.setType(RuleType.QL_EXPRESS);
        countryRule.setContent("countryMatch == true || channelMatch == true");
        countryRule.setDesc("设备或Token的国家/地区必须与任务匹配");
        rules.add(countryRule);

        // 规则4: 应用支持检查
        RuleDefinition appRule = new RuleDefinition();
        appRule.setId("app_support_check");
        appRule.setType(RuleType.QL_EXPRESS);
        appRule.setContent("supportsProject == true");
        appRule.setDesc("设备必须支持任务所属的应用");
        rules.add(appRule);

        // 规则5: 设备负载检查
        RuleDefinition loadRule = new RuleDefinition();
        loadRule.setId("device_load_check");
        loadRule.setType(RuleType.QL_EXPRESS);
        loadRule.setContent("appCount < 10");
        loadRule.setDesc("设备支持的应用数量不能过多");
        rules.add(loadRule);

        return rules;
    }

    /**
     * 获取高级设备匹配规则（包含更多条件）
     */
    public static List<RuleDefinition> getAdvancedDeviceMatchRules() {
        List<RuleDefinition> rules = getDefaultDeviceMatchRules();

        // 规则6: 设备版本检查
        RuleDefinition versionRule = new RuleDefinition();
        versionRule.setId("agent_version_check");
        versionRule.setType(RuleType.QL_EXPRESS);
        versionRule.setContent("agentVersion != null && agentVersion.startsWith('1.')");
        versionRule.setDesc("设备Agent版本必须为1.x版本");
        rules.add(versionRule);

        // 规则7: 设备使用频率检查
        RuleDefinition frequencyRule = new RuleDefinition();
        frequencyRule.setId("usage_frequency_check");
        frequencyRule.setType(RuleType.QL_EXPRESS);
        frequencyRule.setContent("lastUsedTime == null || (System.currentTimeMillis() - lastUsedTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000) > 300000");
        frequencyRule.setDesc("设备最近5分钟内未被使用");
        rules.add(frequencyRule);

        return rules;
    }

    /**
     * 获取特定项目的设备匹配规则
     */
    public static List<RuleDefinition> getProjectSpecificRules(String projectName) {
        List<RuleDefinition> rules = getDefaultDeviceMatchRules();

        // 项目特定规则
        if ("demoApp".equals(projectName)) {
            RuleDefinition demoRule = new RuleDefinition();
            demoRule.setId("demo_app_specific");
            demoRule.setType(RuleType.QL_EXPRESS);
            demoRule.setContent("appCount <= 5 && agentVersion.startsWith('1.0')");
            demoRule.setDesc("demoApp项目要求设备支持应用数量不超过5个，且Agent版本为1.0.x");
            rules.add(demoRule);
        }

        return rules;
    }

    /**
     * 获取宽松的设备匹配规则（用于测试）
     */
    public static List<RuleDefinition> getLooseDeviceMatchRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        // 只保留最基本的规则
        RuleDefinition basicRule = new RuleDefinition();
        basicRule.setId("basic_device_check");
        basicRule.setType(RuleType.QL_EXPRESS);
        basicRule.setContent("isDeviceAvailable == true");
        basicRule.setDesc("设备必须在线");
        rules.add(basicRule);

        RuleDefinition countryRule = new RuleDefinition();
        countryRule.setId("country_match");
        countryRule.setType(RuleType.QL_EXPRESS);
        countryRule.setContent("countryMatch == true");
        countryRule.setDesc("设备国家必须与任务匹配");
        rules.add(countryRule);

        return rules;
    }
} 