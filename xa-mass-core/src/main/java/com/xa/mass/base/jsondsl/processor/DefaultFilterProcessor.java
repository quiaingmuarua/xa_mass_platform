package com.xa.mass.base.jsondsl.processor;

import com.google.gson.Gson;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class DefaultFilterProcessor implements FilterProcessor {
    private static final Gson gson = GsonConfig.buildGson();

    @Override
    public <T> FilterResult<T> filter(T data, JsonDslDefinition def, ProcessingContext ctx) {
        // 参数校验
        ParameterValidator.notNull(data, "data");
        ParameterValidator.notNull(def, "definition");
        ParameterValidator.notNull(ctx, "context");
        ParameterValidator.validateDslType(def, JsonDslDefinition.DslType.FILTER);
        ParameterValidator.validateDslFieldOrCombine(def);

        if (ctx.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 开始处理单个对象 DSL: " + def.getUniqueId());
        }

        // 单对象处理
        Map<String, Object> objMap = convertToMap(data);
        if (objMap != null) {
            FilterResult<Map<String, Object>> result = filterMapWithDetails(objMap, def, ctx);
            if (result.getPassed().isEmpty()) {
                // 转换失败原因
                List<String> failReasons = result.getFailed().get(0).getReasons();
                return FilterResult.of(data, false, failReasons);
            } else {
                return FilterResult.of(data, true, null);
            }
        }
        throw new JsonDslException("不支持的数据类型: " + (data != null ? data.getClass().getSimpleName() : "null"));
    }

    /**
     * 过滤Map对象
     *
     * @param dataMap Map对象
     * @param def DSL定义
     * @param ctx 处理上下文
     * @return 是否通过过滤
     */
    private boolean filterMap(Map<String, Object> dataMap, JsonDslDefinition def, ProcessingContext ctx) {
        ParameterValidator.notNull(dataMap, "dataMap");
        ParameterValidator.notNull(def, "definition");
        ParameterValidator.notNull(ctx, "context");

        DslContext dslContext = new DslContext();
        // 先将所有字段都set到上下文，保证组合条件表达式能访问
        for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
            dslContext.setVariable(entry.getKey(), entry.getValue());
        }

        // 检查字段条件
        Map<String, Object> fieldConds = def.getFieldDsl();
        if (fieldConds != null) {
            for (Map.Entry<String, Object> entry : fieldConds.entrySet()) {
                String field = entry.getKey();
                Object cond = entry.getValue();
                Object val = dataMap.get(field);

                try {
                    dslContext.setVariable(field, val);
                    dslContext.setVariable("curFiledVal", val);
                    Object result = TemplateValueResolver.resolve(cond, dslContext);

                    // 只要有一个字段条件不通过就 fail
                    if (!(result instanceof Boolean) || !((Boolean) result)) {
                        return false;
                    }
                } catch (Exception e) {
                    if (ctx.isDebug()) {
                        System.out.println("[DefaultFilterProcessor] 字段 " + field + " 条件解析异常: " + e.getMessage());
                    }
                    return false;
                }
            }
        }

        // 检查组合条件
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
                        return false;
                    }
                } catch (Exception e) {
                    if (ctx.isDebug()) {
                        System.out.println("[DefaultFilterProcessor] 组合条件 " + exprName + " 执行异常: " + e.getMessage());
                    }
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 过滤Map对象并返回详细结果
     *
     * @param dataMap Map对象
     * @param def DSL定义
     * @param ctx 处理上下文
     * @return 过滤结果，包含详细的失败原因
     */
    private FilterResult<Map<String, Object>> filterMapWithDetails(Map<String, Object> dataMap, JsonDslDefinition def, ProcessingContext ctx) {
        ParameterValidator.notNull(dataMap, "dataMap");
        ParameterValidator.notNull(def, "definition");
        ParameterValidator.notNull(ctx, "context");

        DslContext dslContext = new DslContext();
        // 先将所有字段都set到上下文，保证组合条件表达式能访问
        for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
            dslContext.setVariable(entry.getKey(), entry.getValue());
        }

        List<String> failReasons = new ArrayList<>();

        // 检查字段条件
        Map<String, Object> fieldConds = def.getFieldDsl();
        if (fieldConds != null) {
            for (Map.Entry<String, Object> entry : fieldConds.entrySet()) {
                String field = entry.getKey();
                Object cond = entry.getValue();
                Object val = dataMap.get(field);

                try {
                    dslContext.setVariable(field, val);
                    dslContext.setVariable("curFiledVal", val);
                    Object result = TemplateValueResolver.resolve(cond, dslContext);

                    // 只要有一个字段条件不通过就记录失败原因
                    if (!(result instanceof Boolean) || !((Boolean) result)) {
                        failReasons.add(field + " 不满足条件: " + cond);
                    }
                } catch (Exception e) {
                    failReasons.add(field + " 条件解析异常: " + e.getMessage());
                }
            }
        }

        // 检查组合条件
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
            return new FilterResult<>(List.of(dataMap), null, 1);
        } else {
            return new FilterResult<>(List.of(), List.of(new FilterResult.FilterFailure<>(dataMap, failReasons)), 1);
        }
    }

    @Override
    public <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext ctx) {
        // 参数校验
        ParameterValidator.notNull(dataList, "dataList");
        ParameterValidator.notNull(definition, "definition");
        ParameterValidator.notNull(ctx, "context");
        ParameterValidator.validateDslType(definition, JsonDslDefinition.DslType.FILTER);
        ParameterValidator.validateDslFieldOrCombine(definition);

        if (ctx.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 开始批量过滤，数据量: " + dataList.size());
        }

        List<T> passed = new ArrayList<>();
        List<FilterResult.FilterFailure<T>> failed = new ArrayList<>();

        for (T item : dataList) {
            Map<String, Object> objMap = convertToMap(item);
            if (objMap != null) {
                FilterResult<Map<String, Object>> result = filterMapWithDetails(objMap, definition, ctx);
                if (result.getPassed().isEmpty()) {
                    // 获取详细的失败原因
                    List<String> failReasons = result.getFailed().get(0).getReasons();
                    failed.add(new FilterResult.FilterFailure<>(item, failReasons));
                } else {
                    passed.add(item);
                }
            } else {
                failed.add(new FilterResult.FilterFailure<>(item, List.of("对象转换失败")));
            }
        }

        if (ctx.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 批量过滤完成，通过: " + passed.size() + "/" + dataList.size());
        }

        return new FilterResult<>(passed, failed, dataList.size());
    }

    /**
     * 将对象转换为Map
     *
     * @param obj 要转换的对象
     * @return Map对象，转换失败返回null
     */
    @SuppressWarnings("unchecked")
    private <T> Map<String, Object> convertToMap(T obj) {
        try {
            if (obj instanceof Map) {
                return (Map<String, Object>) obj;
            } else {
                return gson.fromJson(gson.toJson(obj), Map.class);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.FILTER.equals(type);
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