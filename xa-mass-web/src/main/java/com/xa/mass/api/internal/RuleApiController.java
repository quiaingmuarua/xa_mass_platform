package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/status/api/rules")
public class RuleApiController {

    private final RuleManager<?> ruleManager;

    public RuleApiController(RuleManager<?> ruleManager) {
        this.ruleManager = ruleManager;
    }

    @GetMapping("")
    public ApiResponse<Map<String, Object>> listRules() {
        List<Map<String, Object>> items = ruleManager.getDefaultRules().stream()
                .sorted(Comparator.comparing(RuleDefinition::getId, Comparator.nullsLast(String::compareTo)))
                .map(this::toRuleItem)
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> getRuleMeta() {
        return ApiResponse.success(Map.of(
                "ruleTypes", List.of(RuleType.values()).stream().map(Enum::name).toList(),
                "registeredEvaluatorTypes", ruleManager.getRegisteredEvaluatorTypes().stream().map(Enum::name).toList()
        ));
    }

    private Map<String, Object> toRuleItem(RuleDefinition rule) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ruleId", rule.getId());
        item.put("name", rule.getName());
        item.put("type", rule.getType() != null ? rule.getType().name() : null);
        item.put("content", rule.getContent());
        item.put("description", rule.getDescription());
        item.put("enabled", rule.isEnabled());
        item.put("priority", rule.getPriority());
        return item;
    }
}
