package com.xa.mass.base.jsondsl.processor;

import com.google.gson.Gson;

import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.eval.DslExprExecutor;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.filter.DslFilterFactory;
import com.xa.mass.base.jsondsl.filter.JsonDslFilter;


import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import com.xa.mass.base.jsondsl.processor.FilterReport.FilterFail;


/**
 * 默认过滤处理器实现
 * <p>
 * 负责根据 DSL 定义过滤对象列表
 * </p>
 */
class DefaultFilterProcessor implements FilterProcessor {
    private static final Gson gson = GsonConfig.buildGson();

    @Override
    public <T> FilterResult<T> filter(List<T> data, JsonDslDefinition def, ProcessingContext ctx) {
        // 参数校验
        if (data == null) throw new IllegalArgumentException("input data cannot be null");
        if (def == null) throw new IllegalArgumentException("definition cannot be null");
        if (ctx == null) throw new IllegalArgumentException("context cannot be null");
        if (def.getType() != JsonDslDefinition.DslType.FILTER) {
            throw new com.xa.mass.base.jsondsl.builtin.JsonDslException("DSL type must be FILTER");
        }
        if (def.getFieldDsl() == null || def.getFieldDsl().isEmpty()) {
            throw new com.xa.mass.base.jsondsl.builtin.JsonDslException("fieldDsl must not be empty");
        }
        boolean includeFailed = Boolean.TRUE.equals(ctx.getParameter("includeFailedDetail", true));
        // 内联原filterWithReport逻辑
        List<T> passed = new java.util.ArrayList<>();
        java.util.List<FilterReport.FilterFail<T>> failed = new java.util.ArrayList<>();

        // 直接构造 JsonObject，避免不必要的 JSON 转换
        com.google.gson.JsonObject filterConfig = new com.google.gson.JsonObject();
        if (def.getFieldDsl() != null) {
            filterConfig.add("fieldDsl", GsonConfig.buildGson().toJsonTree(def.getFieldDsl()));
        }
        if (def.getCombineDsl() != null) {
            filterConfig.add("combineDsl", GsonConfig.buildGson().toJsonTree(def.getCombineDsl()));
        }
        com.xa.mass.base.jsondsl.filter.JsonDslFilter<T> filter = com.xa.mass.base.jsondsl.filter.DslFilterFactory.createJsonDslFilter(def.getUniqueId(), def.getDescription(), filterConfig);

        for (T obj : data) {
            java.util.List<String> failReasons = new java.util.ArrayList<>();
            java.util.Map<String, Object> objMap;
            try {
                objMap = filter.objectToMap(obj);
            } catch (Exception e) {
                failReasons.add("对象转Map失败: " + e.getMessage());
                failed.add(new FilterReport.FilterFail<>(obj, failReasons));
                continue;
            }
            java.util.Map<String, Object> fieldConds = def.getFieldDsl();
            if (fieldConds != null) {
                for (java.util.Map.Entry<String, Object> entry : fieldConds.entrySet()) {
                    String field = entry.getKey();
                    Object cond = entry.getValue();
                    Object val = objMap.get(field);
                    com.google.gson.JsonElement condJson = GsonConfig.buildGson().toJsonTree(cond);
                    if (condJson.isJsonObject()) {
                        Map<String, Object> condMap = GsonConfig.buildGson().fromJson(condJson, Map.class);
                        if (!BuiltinFunctions.evaluate(val, condMap)) {
                            failReasons.add(field + " 不满足条件: " + cond);
                        }
                    } else {
                        // 直接等值判断
                        if (val == null || !val.toString().equals(condJson.getAsString())) {
                            failReasons.add(field + " 不满足条件: " + cond);
                        }
                    }
                }
            }
            java.util.Map<String, Object> combineConds = def.getCombineDsl();
            if (combineConds != null) {
                for (java.util.Map.Entry<String, Object> entry : combineConds.entrySet()) {
                    String exprName = entry.getKey();
                    String expr = String.valueOf(entry.getValue());
                    try {
                        Object result = DslExprExecutor.execute(expr, objMap);
                        boolean ok = false;
                        if (result instanceof Boolean) ok = (Boolean) result;
                        else if (result instanceof Number) ok = ((Number) result).doubleValue() != 0;
                        else if (result instanceof String) ok = !((String) result).isEmpty();
                        else ok = result != null;
                        if (!ok) {
                            failReasons.add("组合条件 " + exprName + " 不满足: " + expr);
                        }
                    } catch (Exception e) {
                        failReasons.add("组合条件 " + exprName + " 执行异常: " + e.getMessage());
                    }
                }
            }
            if (failReasons.isEmpty()) {
                passed.add(obj);
            } else {
                failed.add(new FilterReport.FilterFail<>(obj, failReasons));
            }
        }
        if (includeFailed) {
            return new FilterResult<>(passed, failed, data.size());
        } else {
            return new FilterResult<>(passed, null, data.size());
        }
    }

    @Override
    public String getName() {
        return "DefaultFilterProcessor";
    }

    @Override
    public int getPriority() {
        return 200; // 过滤处理器优先级
    }
}