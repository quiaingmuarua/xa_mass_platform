package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.RuleOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/rules")
public class RuleApiController {

    private final RuleOperations ruleOperations;

    public RuleApiController(RuleOperations ruleOperations) {
        this.ruleOperations = ruleOperations;
    }

    @GetMapping("")
    public ApiResponse<Map<String, Object>> listRules() {
        List<Map<String, Object>> items = ruleOperations.listDefaultRules();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> getRuleMeta() {
        return ApiResponse.success(Map.of(
                "ruleTypes", ruleOperations.listRuleTypes(),
                "registeredEvaluatorTypes", ruleOperations.listRegisteredEvaluatorTypes()
        ));
    }
}
