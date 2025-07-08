package com.xa.mass.base.jsondsl.processor;

import com.google.gson.Gson;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;
import java.util.Map;

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
        List<T> passed = new java.util.ArrayList<>();
        java.util.List<FilterReport.FilterFail<T>> failed = new java.util.ArrayList<>();
        DslContext dslContext = new DslContext();
        for (T obj : data) {
            java.util.List<String> failReasons = new java.util.ArrayList<>();
            java.util.Map<String, Object> objMap;
            try {
                if (obj instanceof Map) {
                    objMap = (Map<String, Object>) obj;
                } else {
                    objMap = gson.fromJson(gson.toJson(obj), Map.class);
                }
            } catch (Exception e) {
                failReasons.add("对象转Map失败: " + e.getMessage());
                failed.add(new FilterReport.FilterFail<>(obj, failReasons));
                continue;
            }
            Map<String, Object> fieldConds = def.getFieldDsl();
            if (fieldConds != null) {
                for (Map.Entry<String, Object> entry : fieldConds.entrySet()) {
                    String field = entry.getKey();
                    Object cond = entry.getValue();
                    Object val = objMap.get(field);
                    try {
                        dslContext.setVariable(field, val);
                        dslContext.setVariable("curFiledVal", val);
                        Object result = TemplateValueResolver.resolve(cond, dslContext);
                        // 只要有一个字段条件不通过就 fail
                        if (!(result instanceof Boolean) || !((Boolean) result)) {
                            failReasons.add(field + " 不满足条件: " + cond);
                        }
                    } catch (Exception e) {
                        failReasons.add(field + " 条件解析异常: " + e.getMessage());
                    }
                }
            }
            Map<String, Object> combineConds = def.getCombineDsl();
            if (combineConds != null) {
                for (Map.Entry<String, Object> entry : combineConds.entrySet()) {
                    String exprName = entry.getKey();
                    Object expr = entry.getValue();
                    try {
                        Object result = TemplateValueResolver.resolve(expr, dslContext);
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