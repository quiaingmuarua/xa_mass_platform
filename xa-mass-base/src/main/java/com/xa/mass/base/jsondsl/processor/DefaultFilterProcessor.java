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
    public <T> List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context) {
        // 参数验证
        if (input == null) {
            throw new IllegalArgumentException("Input list cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        
        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 转换为传统格式
        String filterConfig = JsonDslParser.toLegacyFormat(definition);
        
        // 应用过滤器
        JsonDslFilter<Object> filter = DslFilterFactory.createJsonDslFilter(
            "autoFilter", "自动生成的过滤器", filterConfig
        );
        @SuppressWarnings("unchecked")
        List<T> result = (List<T>) filter.filterList((List<Object>) input);
        
        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 过滤完成，原始数量: " + input.size() + ", 过滤后数量: " + result.size());
        }
        
        return result;
    }
    
    @Override
    public String getName() {
        return "DefaultFilterProcessor";
    }
    
    @Override
    public int getPriority() {
        return 200; // 过滤处理器优先级
    }

    public <T> FilterReport<T> filterWithReport(List<T> data, JsonDslDefinition def, ProcessingContext ctx) {
        List<T> passed = new ArrayList<>();
        List<FilterFail<T>> failed = new ArrayList<>();
        JsonDslFilter<T> filter = DslFilterFactory.createJsonDslFilter(def.getUniqueId(), def.getDescription(), com.xa.mass.base.jsondsl.parser.JsonDslParser.toLegacyFormat(def));
        for (T obj : data) {
            List<String> failReasons = new ArrayList<>();
            Map<String, Object> objMap;
            try {
                objMap = filter.objectToMap(obj);
            } catch (Exception e) {
                failReasons.add("对象转Map失败: " + e.getMessage());
                failed.add(new FilterFail<>(obj, failReasons));
                continue;
            }
            // 字段条件
            Map<String, Object> fieldConds = def.getFieldDsl();
            if (fieldConds != null) {
                for (Map.Entry<String, Object> entry : fieldConds.entrySet()) {
                    String field = entry.getKey();
                    Object cond = entry.getValue();
                    Object val = objMap.get(field);
                    com.google.gson.JsonElement condJson = com.xa.mass.base.jsondsl.util.GsonConfig.buildGson().toJsonTree(cond);
                    if (!filter.evaluateFieldCondition(val, condJson)) {
                        failReasons.add(field + " 不满足条件: " + cond);
                    }
                }
            }
            // 组合条件
            Map<String, Object> combineConds = def.getCombineDsl();
            if (combineConds != null) {
                for (Map.Entry<String, Object> entry : combineConds.entrySet()) {
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
                failed.add(new FilterFail<>(obj, failReasons));
            }
        }
        return new FilterReport<>(passed, failed);
    }
} 