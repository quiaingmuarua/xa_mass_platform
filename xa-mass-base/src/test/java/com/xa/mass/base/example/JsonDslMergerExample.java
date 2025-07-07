package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslMerger;

import java.util.*;

/**
 * JSON-DSL 合并器使用示例
 * <p>
 * 演示如何使用 JsonDslMerger 合并多个 DSL 定义，
 * 包括不同合并策略的使用场景。
 * </p>
 */
public class JsonDslMergerExample {
    
    public static void main(String[] args) {
        System.out.println("=== JSON-DSL 合并器示例 ===\n");
        
        // 示例1：项目级别的设备筛选规则
        example1_ProjectLevelFilter();
        
        // 示例2：用户级别的设备筛选规则
        example2_UserLevelFilter();
        
        // 示例3：任务级别的设备筛选规则
        example3_TaskLevelFilter();
        
        // 示例4：合并多个筛选规则
        example4_MergeMultipleFilters();
        
        // 示例5：不同合并策略对比
        example5_MergeStrategies();
        
        // 示例6：冲突检测
        example6_ConflictDetection();
    }
    
    /**
     * 示例1：项目级别的设备筛选规则
     */
    private static void example1_ProjectLevelFilter() {
        System.out.println("--- 示例1：项目级别的设备筛选规则 ---");
        
        // 项目级别的筛选规则（优先级最低）
        JsonDslDefinition projectFilter = new JsonDslDefinition();
        projectFilter.setUniqueId("project_device_filter");
        projectFilter.setType(JsonDslDefinition.DslType.FILTER);
        projectFilter.setPriority(100); // 低优先级
        projectFilter.setDescription("项目级别的设备筛选规则");
        
        // 设置字段筛选条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("region", "华东");
        fieldDsl.put("deviceType", "CAMERA");
        
        // 设置组合筛选条件
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("AND", Arrays.asList(
            Map.of("field", "batteryLevel", "operator", ">=", "value", 20),
            Map.of("field", "lastHeartbeat", "operator", ">", "value", "now-300s")
        ));
        
        projectFilter.setFieldDsl(fieldDsl);
        projectFilter.setCombineDsl(combineDsl);
        
        System.out.println("项目筛选规则：");
        System.out.println("- 优先级：" + projectFilter.getPriority());
        System.out.println("- 字段筛选：" + projectFilter.getFieldDsl());
        System.out.println("- 组合筛选：" + projectFilter.getCombineDsl());
        System.out.println();
    }
    
    /**
     * 示例2：用户级别的设备筛选规则
     */
    private static void example2_UserLevelFilter() {
        System.out.println("--- 示例2：用户级别的设备筛选规则 ---");
        
        // 用户级别的筛选规则（中等优先级）
        JsonDslDefinition userFilter = new JsonDslDefinition();
        userFilter.setUniqueId("user_device_filter");
        userFilter.setType(JsonDslDefinition.DslType.FILTER);
        userFilter.setPriority(50); // 中等优先级
        userFilter.setDescription("用户级别的设备筛选规则");
        
        // 设置字段筛选条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("deviceType", "CAMERA");
        fieldDsl.put("permission", "READ"); // 用户特有的权限要求
        
        // 设置组合筛选条件
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("OR", Arrays.asList(
            Map.of("field", "batteryLevel", "operator", ">=", "value", 30),
            Map.of("field", "powerSource", "operator", "=", "value", "AC")
        ));
        
        userFilter.setFieldDsl(fieldDsl);
        userFilter.setCombineDsl(combineDsl);
        
        System.out.println("用户筛选规则：");
        System.out.println("- 优先级：" + userFilter.getPriority());
        System.out.println("- 字段筛选：" + userFilter.getFieldDsl());
        System.out.println("- 组合筛选：" + userFilter.getCombineDsl());
        System.out.println();
    }
    
    /**
     * 示例3：任务级别的设备筛选规则
     */
    private static void example3_TaskLevelFilter() {
        System.out.println("--- 示例3：任务级别的设备筛选规则 ---");
        
        // 任务级别的筛选规则（最高优先级）
        JsonDslDefinition taskFilter = new JsonDslDefinition();
        taskFilter.setUniqueId("task_device_filter");
        taskFilter.setType(JsonDslDefinition.DslType.FILTER);
        taskFilter.setPriority(10); // 高优先级
        taskFilter.setDescription("任务级别的设备筛选规则");
        
        // 设置字段筛选条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("deviceType", "CAMERA");
        fieldDsl.put("resolution", "4K"); // 任务特有的分辨率要求
        fieldDsl.put("nightVision", true); // 任务特有的夜视要求
        
        // 设置组合筛选条件
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("AND", Arrays.asList(
            Map.of("field", "batteryLevel", "operator", ">=", "value", 50),
            Map.of("field", "signalStrength", "operator", ">=", "value", 80)
        ));
        
        taskFilter.setFieldDsl(fieldDsl);
        taskFilter.setCombineDsl(combineDsl);
        
        System.out.println("任务筛选规则：");
        System.out.println("- 优先级：" + taskFilter.getPriority());
        System.out.println("- 字段筛选：" + taskFilter.getFieldDsl());
        System.out.println("- 组合筛选：" + taskFilter.getCombineDsl());
        System.out.println();
    }
    
    /**
     * 示例4：合并多个筛选规则
     */
    private static void example4_MergeMultipleFilters() {
        System.out.println("--- 示例4：合并多个筛选规则 ---");
        
        // 创建三个不同优先级的筛选规则
        JsonDslDefinition projectFilter = createProjectFilter();
        JsonDslDefinition userFilter = createUserFilter();
        JsonDslDefinition taskFilter = createTaskFilter();
        
        // 合并所有筛选规则
        List<JsonDslDefinition> allFilters = Arrays.asList(projectFilter, userFilter, taskFilter);
        JsonDslDefinition mergedFilter = JsonDslMerger.merge(allFilters, JsonDslMerger.MergeStrategy.MERGE);
        
        System.out.println("合并后的筛选规则：");
        System.out.println("- 唯一ID：" + mergedFilter.getUniqueId());
        System.out.println("- 优先级：" + mergedFilter.getPriority());
        System.out.println("- 字段筛选：" + mergedFilter.getFieldDsl());
        System.out.println("- 组合筛选：" + mergedFilter.getCombineDsl());
        System.out.println();
        
        // 分析合并结果
        System.out.println("合并结果分析：");
        System.out.println("- 字段 'status' 被所有规则覆盖，最终值为：" + mergedFilter.getFieldDsl().get("status"));
        System.out.println("- 字段 'deviceType' 被所有规则覆盖，最终值为：" + mergedFilter.getFieldDsl().get("deviceType"));
        System.out.println("- 字段 'region' 来自项目规则：" + mergedFilter.getFieldDsl().get("region"));
        System.out.println("- 字段 'permission' 来自用户规则：" + mergedFilter.getFieldDsl().get("permission"));
        System.out.println("- 字段 'resolution' 来自任务规则：" + mergedFilter.getFieldDsl().get("resolution"));
        System.out.println("- 字段 'nightVision' 来自任务规则：" + mergedFilter.getFieldDsl().get("nightVision"));
        System.out.println();
    }
    
    /**
     * 示例5：不同合并策略对比
     */
    private static void example5_MergeStrategies() {
        System.out.println("--- 示例5：不同合并策略对比 ---");
        
        JsonDslDefinition projectFilter = createProjectFilter();
        JsonDslDefinition userFilter = createUserFilter();
        JsonDslDefinition taskFilter = createTaskFilter();
        
        List<JsonDslDefinition> allFilters = Arrays.asList(projectFilter, userFilter, taskFilter);
        
        // 测试不同的合并策略
        JsonDslMerger.MergeStrategy[] strategies = {
            JsonDslMerger.MergeStrategy.OVERRIDE,
            JsonDslMerger.MergeStrategy.MERGE,
            JsonDslMerger.MergeStrategy.INTERSECT,
            JsonDslMerger.MergeStrategy.UNION
        };
        
        for (JsonDslMerger.MergeStrategy strategy : strategies) {
            JsonDslDefinition merged = JsonDslMerger.merge(allFilters, strategy);
            System.out.println("策略：" + strategy.getDescription() + " (" + strategy.getCode() + ")");
            System.out.println("- 字段数量：" + merged.getFieldDsl().size());
            System.out.println("- 字段列表：" + merged.getFieldDsl().keySet());
            System.out.println();
        }
    }
    
    /**
     * 示例6：冲突检测
     */
    private static void example6_ConflictDetection() {
        System.out.println("--- 示例6：冲突检测 ---");
        
        JsonDslDefinition projectFilter = createProjectFilter();
        JsonDslDefinition userFilter = createUserFilter();
        JsonDslDefinition taskFilter = createTaskFilter();
        
        List<JsonDslDefinition> allFilters = Arrays.asList(projectFilter, userFilter, taskFilter);
        
        // 检查是否可以合并
        boolean canMerge = JsonDslMerger.canMerge(allFilters);
        System.out.println("是否可以合并：" + canMerge);
        
        // 获取冲突信息
        Map<String, Object> conflicts = JsonDslMerger.getMergeConflicts(allFilters);
        System.out.println("合并冲突信息：");
        System.out.println("- 可以合并：" + conflicts.get("canMerge"));
        
        if (conflicts.containsKey("fieldConflicts")) {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> fieldConflicts = (Map<String, List<String>>) conflicts.get("fieldConflicts");
            System.out.println("- 字段冲突：" + fieldConflicts);
        }
        
        if (conflicts.containsKey("combineConflicts")) {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> combineConflicts = (Map<String, List<String>>) conflicts.get("combineConflicts");
            System.out.println("- 组合冲突：" + combineConflicts);
        }
        System.out.println();
    }
    
    // ==================== 辅助方法 ====================
    
    private static JsonDslDefinition createProjectFilter() {
        JsonDslDefinition filter = new JsonDslDefinition();
        filter.setUniqueId("project_device_filter");
        filter.setType(JsonDslDefinition.DslType.FILTER);
        filter.setPriority(100);
        filter.setDescription("项目级别的设备筛选规则");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("region", "华东");
        fieldDsl.put("deviceType", "CAMERA");
        
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("AND", Arrays.asList(
            Map.of("field", "batteryLevel", "operator", ">=", "value", 20),
            Map.of("field", "lastHeartbeat", "operator", ">", "value", "now-300s")
        ));
        
        filter.setFieldDsl(fieldDsl);
        filter.setCombineDsl(combineDsl);
        return filter;
    }
    
    private static JsonDslDefinition createUserFilter() {
        JsonDslDefinition filter = new JsonDslDefinition();
        filter.setUniqueId("user_device_filter");
        filter.setType(JsonDslDefinition.DslType.FILTER);
        filter.setPriority(50);
        filter.setDescription("用户级别的设备筛选规则");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("deviceType", "CAMERA");
        fieldDsl.put("permission", "READ");
        
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("OR", Arrays.asList(
            Map.of("field", "batteryLevel", "operator", ">=", "value", 30),
            Map.of("field", "powerSource", "operator", "=", "value", "AC")
        ));
        
        filter.setFieldDsl(fieldDsl);
        filter.setCombineDsl(combineDsl);
        return filter;
    }
    
    private static JsonDslDefinition createTaskFilter() {
        JsonDslDefinition filter = new JsonDslDefinition();
        filter.setUniqueId("task_device_filter");
        filter.setType(JsonDslDefinition.DslType.FILTER);
        filter.setPriority(10);
        filter.setDescription("任务级别的设备筛选规则");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("deviceType", "CAMERA");
        fieldDsl.put("resolution", "4K");
        fieldDsl.put("nightVision", true);
        
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("AND", Arrays.asList(
            Map.of("field", "batteryLevel", "operator", ">=", "value", 50),
            Map.of("field", "signalStrength", "operator", ">=", "value", 80)
        ));
        
        filter.setFieldDsl(fieldDsl);
        filter.setCombineDsl(combineDsl);
        return filter;
    }
} 