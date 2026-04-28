package com.xa.mass.base.jsondsl.model;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON-DSL 合并器
 * <p>
 * 支持多个 DSL 定义的合并，主要用于 filter 类型的 DSL，
 * 支持优先级机制和字段级别的合并策略。
 * </p>
 */
public class JsonDslMerger {

    /**
     * 合并多个 DSL 定义
     *
     * @param dsls 要合并的 DSL 定义列表，按优先级排序（优先级高的在前）
     * @param strategy 合并策略
     * @return 合并后的 DSL 定义
     */
    public static JsonDslDefinition merge(List<JsonDslDefinition> dsls, MergeStrategy strategy) {
        if (dsls == null || dsls.isEmpty()) {
            throw new JsonDslException("DSL 列表不能为空");
        }

        // 按优先级排序（数字越小优先级越高）
        List<JsonDslDefinition> sortedDsls = dsls.stream()
                .filter(dsl -> dsl != null && dsl.getEnabled())
                .sorted(Comparator.comparing(JsonDslDefinition::getPriority))
                .collect(Collectors.toList());

        if (sortedDsls.isEmpty()) {
            throw new JsonDslException("没有有效的 DSL 定义");
        }

        // 验证所有 DSL 类型一致
        JsonDslDefinition.DslType targetType = sortedDsls.get(0).getType();
        for (JsonDslDefinition dsl : sortedDsls) {
            if (dsl.getType() != targetType) {
                throw new JsonDslException("所有 DSL 类型必须一致，当前类型: " + targetType +
                        ", 发现不一致类型: " + dsl.getType());
            }
        }

        // 创建合并后的 DSL
        JsonDslDefinition mergedDsl = new JsonDslDefinition();
        mergedDsl.setType(targetType);
        mergedDsl.setUniqueId("merged_" + System.currentTimeMillis());
        mergedDsl.setDescription("合并后的 " + targetType.getDescription());
        mergedDsl.setPriority(0); // 合并后的优先级最高
        mergedDsl.setVersion("1.0");

        // 根据策略执行合并
        switch (strategy) {
            case OVERRIDE:
                mergeWithOverride(mergedDsl, sortedDsls);
                break;
            case MERGE:
                mergeWithMerge(mergedDsl, sortedDsls);
                break;
            case INTERSECT:
                mergeWithIntersect(mergedDsl, sortedDsls);
                break;
            case UNION:
                mergeWithUnion(mergedDsl, sortedDsls);
                break;
        }

        return mergedDsl;
    }

    /**
     * 覆盖策略合并：高优先级的完全覆盖低优先级的
     */
    private static void mergeWithOverride(JsonDslDefinition mergedDsl, List<JsonDslDefinition> sortedDsls) {
        // 取最高优先级的 DSL 作为基础
        JsonDslDefinition highestPriority = sortedDsls.get(sortedDsls.size() - 1);

        mergedDsl.setContext(highestPriority.getContext());
        mergedDsl.setFieldDsl(highestPriority.getFieldDsl() != null ? new HashMap<>(highestPriority.getFieldDsl()) : new HashMap<>());
        mergedDsl.setCombineDsl(highestPriority.getCombineDsl() != null ? new HashMap<>(highestPriority.getCombineDsl()) : new HashMap<>());
        mergedDsl.setExtensions(highestPriority.getExtensions() != null ? new HashMap<>(highestPriority.getExtensions()) : new HashMap<>());
        mergedDsl.setTags(highestPriority.getTags());
        mergedDsl.setAuthor(highestPriority.getAuthor());
    }

    /**
     * 合并策略：合并所有字段，高优先级字段优先
     */
    private static void mergeWithMerge(JsonDslDefinition mergedDsl, List<JsonDslDefinition> sortedDsls) {
        Map<String, Object> mergedFieldDsl = new HashMap<>();
        Map<String, Object> mergedCombineDsl = new HashMap<>();
        Map<String, Object> mergedExtensions = new HashMap<>();

        // 从低优先级到高优先级合并，高优先级的会覆盖低优先级的
        for (JsonDslDefinition dsl : sortedDsls) {
            if (dsl.getFieldDsl() != null) {
                mergedFieldDsl.putAll(dsl.getFieldDsl());
            }
            if (dsl.getCombineDsl() != null) {
                mergedCombineDsl.putAll(dsl.getCombineDsl());
            }
            if (dsl.getExtensions() != null) {
                mergedExtensions.putAll(dsl.getExtensions());
            }
        }

        // 设置合并后的字段
        mergedDsl.setFieldDsl(mergedFieldDsl);
        mergedDsl.setCombineDsl(mergedCombineDsl);
        mergedDsl.setExtensions(mergedExtensions);

        // 使用最高优先级的上下文和其他配置
        JsonDslDefinition highestPriority = sortedDsls.get(sortedDsls.size() - 1);
        mergedDsl.setContext(highestPriority.getContext());
        mergedDsl.setTags(highestPriority.getTags());
        mergedDsl.setAuthor(highestPriority.getAuthor());
    }

    /**
     * 交集策略：只保留所有 DSL 都包含的字段
     */
    private static void mergeWithIntersect(JsonDslDefinition mergedDsl, List<JsonDslDefinition> sortedDsls) {
        Map<String, Object> mergedFieldDsl = new HashMap<>();
        Map<String, Object> mergedCombineDsl = new HashMap<>();
        Map<String, Object> mergedExtensions = new HashMap<>();

        // 获取所有 DSL 的字段集合
        Set<String> allFieldKeys = new HashSet<>();
        Set<String> allCombineKeys = new HashSet<>();
        Set<String> allExtensionKeys = new HashSet<>();

        for (JsonDslDefinition dsl : sortedDsls) {
            if (dsl.getFieldDsl() != null) {
                allFieldKeys.addAll(dsl.getFieldDsl().keySet());
            }
            if (dsl.getCombineDsl() != null) {
                allCombineKeys.addAll(dsl.getCombineDsl().keySet());
            }
            if (dsl.getExtensions() != null) {
                allExtensionKeys.addAll(dsl.getExtensions().keySet());
            }
        }

        // 只保留所有 DSL 都包含的字段，使用最高优先级的值
        for (String fieldKey : allFieldKeys) {
            boolean allContain = sortedDsls.stream()
                    .allMatch(dsl -> dsl.getFieldDsl() != null && dsl.getFieldDsl().containsKey(fieldKey));
            if (allContain) {
                // 找到最高优先级的 DSL 中该字段的值
                for (int i = sortedDsls.size() - 1; i >= 0; i--) {
                    JsonDslDefinition dsl = sortedDsls.get(i);
                    if (dsl.getFieldDsl() != null && dsl.getFieldDsl().containsKey(fieldKey)) {
                        mergedFieldDsl.put(fieldKey, dsl.getFieldDsl().get(fieldKey));
                        break;
                    }
                }
            }
        }

        // 对 combineDsl 和 extensions 执行相同的逻辑
        for (String combineKey : allCombineKeys) {
            boolean allContain = sortedDsls.stream()
                    .allMatch(dsl -> dsl.getCombineDsl() != null && dsl.getCombineDsl().containsKey(combineKey));
            if (allContain) {
                for (int i = sortedDsls.size() - 1; i >= 0; i--) {
                    JsonDslDefinition dsl = sortedDsls.get(i);
                    if (dsl.getCombineDsl() != null && dsl.getCombineDsl().containsKey(combineKey)) {
                        mergedCombineDsl.put(combineKey, dsl.getCombineDsl().get(combineKey));
                        break;
                    }
                }
            }
        }

        for (String extensionKey : allExtensionKeys) {
            boolean allContain = sortedDsls.stream()
                    .allMatch(dsl -> dsl.getExtensions() != null && dsl.getExtensions().containsKey(extensionKey));
            if (allContain) {
                for (int i = sortedDsls.size() - 1; i >= 0; i--) {
                    JsonDslDefinition dsl = sortedDsls.get(i);
                    if (dsl.getExtensions() != null && dsl.getExtensions().containsKey(extensionKey)) {
                        mergedExtensions.put(extensionKey, dsl.getExtensions().get(extensionKey));
                        break;
                    }
                }
            }
        }

        mergedDsl.setFieldDsl(mergedFieldDsl);
        mergedDsl.setCombineDsl(mergedCombineDsl);
        mergedDsl.setExtensions(mergedExtensions);

        // 使用最高优先级的上下文和其他配置
        JsonDslDefinition highestPriority = sortedDsls.get(sortedDsls.size() - 1);
        mergedDsl.setContext(highestPriority.getContext());
        mergedDsl.setTags(highestPriority.getTags());
        mergedDsl.setAuthor(highestPriority.getAuthor());
    }

    /**
     * 并集策略：保留所有字段，冲突时高优先级优先
     */
    private static void mergeWithUnion(JsonDslDefinition mergedDsl, List<JsonDslDefinition> sortedDsls) {
        // 并集策略与合并策略相同，因为高优先级会覆盖低优先级
        mergeWithMerge(mergedDsl, sortedDsls);
    }

    /**
     * 合并多个 DSL 定义（使用默认合并策略）
     */
    public static JsonDslDefinition merge(List<JsonDslDefinition> dsls) {
        return merge(dsls, MergeStrategy.MERGE);
    }

    /**
     * 合并两个 DSL 定义
     */
    public static JsonDslDefinition merge(JsonDslDefinition dsl1, JsonDslDefinition dsl2, MergeStrategy strategy) {
        return merge(Arrays.asList(dsl1, dsl2), strategy);
    }

    /**
     * 合并两个 DSL 定义（使用默认合并策略）
     */
    public static JsonDslDefinition merge(JsonDslDefinition dsl1, JsonDslDefinition dsl2) {
        return merge(Arrays.asList(dsl1, dsl2), MergeStrategy.MERGE);
    }

    /**
     * 检查 DSL 是否可以合并
     */
    public static boolean canMerge(List<JsonDslDefinition> dsls) {
        if (dsls == null || dsls.isEmpty()) {
            return false;
        }

        // 检查是否有有效的 DSL
        boolean hasValidDsl = dsls.stream()
                .anyMatch(dsl -> dsl != null && dsl.getEnabled());

        if (!hasValidDsl) {
            return false;
        }

        // 检查类型是否一致
        JsonDslDefinition.DslType firstType = null;
        for (JsonDslDefinition dsl : dsls) {
            if (dsl != null && dsl.getEnabled()) {
                if (firstType == null) {
                    firstType = dsl.getType();
                } else if (dsl.getType() != firstType) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 获取合并冲突信息
     */
    public static Map<String, Object> getMergeConflicts(List<JsonDslDefinition> dsls) {
        Map<String, Object> conflicts = new HashMap<>();

        if (!canMerge(dsls)) {
            conflicts.put("canMerge", false);
            conflicts.put("reason", "DSL 类型不一致或没有有效的 DSL");
            return conflicts;
        }

        // 分析字段冲突
        Map<String, List<String>> fieldConflicts = new HashMap<>();
        Map<String, List<String>> combineConflicts = new HashMap<>();

        List<JsonDslDefinition> validDsls = dsls.stream()
                .filter(dsl -> dsl != null && dsl.getEnabled())
                .collect(Collectors.toList());

        // 检查 fieldDsl 冲突
        for (JsonDslDefinition dsl : validDsls) {
            if (dsl.getFieldDsl() != null) {
                for (String field : dsl.getFieldDsl().keySet()) {
                    fieldConflicts.computeIfAbsent(field, k -> new ArrayList<>())
                            .add(dsl.getUniqueId());
                }
            }
        }

        // 检查 combineDsl 冲突
        for (JsonDslDefinition dsl : validDsls) {
            if (dsl.getCombineDsl() != null) {
                for (String combine : dsl.getCombineDsl().keySet()) {
                    combineConflicts.computeIfAbsent(combine, k -> new ArrayList<>())
                            .add(dsl.getUniqueId());
                }
            }
        }

        // 只保留有冲突的字段（多个 DSL 都包含的字段）
        fieldConflicts.entrySet().removeIf(entry -> entry.getValue().size() <= 1);
        combineConflicts.entrySet().removeIf(entry -> entry.getValue().size() <= 1);

        conflicts.put("canMerge", true);
        conflicts.put("fieldConflicts", fieldConflicts);
        conflicts.put("combineConflicts", combineConflicts);

        return conflicts;
    }

    /**
     * 合并策略枚举
     */
    public enum MergeStrategy {
        /**
         * 覆盖策略：高优先级的完全覆盖低优先级的
         */
        OVERRIDE("override", "完全覆盖"),

        /**
         * 合并策略：合并所有字段，高优先级字段优先
         */
        MERGE("merge", "字段合并"),

        /**
         * 交集策略：只保留所有 DSL 都包含的字段
         */
        INTERSECT("intersect", "字段交集"),

        /**
         * 并集策略：保留所有字段，冲突时高优先级优先
         */
        UNION("union", "字段并集");

        private final String code;
        private final String description;

        MergeStrategy(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public static MergeStrategy fromCode(String code) {
            for (MergeStrategy strategy : values()) {
                if (strategy.code.equalsIgnoreCase(code)) {
                    return strategy;
                }
            }
            return MERGE; // 默认使用合并策略
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }
} 