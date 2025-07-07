package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.JsonDslEngine;
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
    
    @Override
    public <T> FilterResult<T> filter(List<T> data, JsonDslDefinition def, ProcessingContext ctx) {
        boolean includeFailed = ctx != null && Boolean.TRUE.equals(ctx.getParameter("includeFailedDetail", true));
        // 内联原filterWithReport逻辑
        List<T> passed = new java.util.ArrayList<>();
        java.util.List<FilterReport.FilterFail<T>> failed = new java.util.ArrayList<>();
        com.xa.mass.base.jsondsl.filter.JsonDslFilter<T> filter = com.xa.mass.base.jsondsl.filter.DslFilterFactory.createJsonDslFilter(def.getUniqueId(), def.getDescription(), com.xa.mass.base.jsondsl.parser.JsonDslParser.toLegacyFormat(def));
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
                    com.google.gson.JsonElement condJson = com.xa.mass.base.jsondsl.util.GsonConfig.buildGson().toJsonTree(cond);
                    if (!filter.evaluateFieldCondition(val, condJson)) {
                        failReasons.add(field + " 不满足条件: " + cond);
                    }
                }
            }
            java.util.Map<String, Object> combineConds = def.getCombineDsl();
            if (combineConds != null) {
                for (java.util.Map.Entry<String, Object> entry : combineConds.entrySet()) {
                    String exprName = entry.getKey();
                    String expr = String.valueOf(entry.getValue());
                    try {
                        Object result = filter.exprExecutor.execute(expr, objMap);
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