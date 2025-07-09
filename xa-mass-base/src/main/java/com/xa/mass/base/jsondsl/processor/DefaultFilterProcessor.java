package com.xa.mass.base.jsondsl.processor;

import com.google.gson.Gson;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

class DefaultFilterProcessor implements FilterProcessor {
    private static final Gson gson = GsonConfig.buildGson();

    @Override
    public <T> FilterResult<T> filter(List<T> data, JsonDslDefinition def, ProcessingContext ctx) {
        // 使用统一的参数校验
        ParameterValidator.validateFilterParams(data, def, ctx);
        
        List<T> passed = new ArrayList<>();
        List<FilterReport.FilterFail<T>> failed = new ArrayList<>();
        
        for (T obj : data) {
            if (filterSingle(obj, def, ctx)) {
                passed.add(obj);
            } else {
                // 如果需要失败详情，需要重新过滤一次获取失败原因
                if (Boolean.TRUE.equals(ctx.getParameter("includeFailedDetail", true))) {
                    List<String> failReasons = getFailReasons(obj, def, ctx);
                    failed.add(new FilterReport.FilterFail<>(obj, failReasons));
                }
            }
        }
        
        boolean includeFailed = Boolean.TRUE.equals(ctx.getParameter("includeFailedDetail", true));
        if (includeFailed) {
            return new FilterResult<>(passed, failed, data.size());
        } else {
            return new FilterResult<>(passed, null, data.size());
        }
    }
    
    /**
     * 过滤单个对象
     * 
     * @param data 单个对象
     * @param def DSL定义
     * @param ctx 处理上下文
     * @return 是否通过过滤
     */
    public <T> boolean filterSingle(T data, JsonDslDefinition def, ProcessingContext ctx) {
        ParameterValidator.notNull(data, "data");
        ParameterValidator.notNull(def, "definition");
        ParameterValidator.notNull(ctx, "context");
        
        // 将对象转换为Map
        Map<String, Object> objMap = convertToMap(data);
        if (objMap == null) {
            return false; // 转换失败视为过滤失败
        }
        
        return filterMap(objMap, def, ctx);
    }
    
    /**
     * 过滤Map对象
     * 
     * @param dataMap Map对象
     * @param def DSL定义
     * @param ctx 处理上下文
     * @return 是否通过过滤
     */
    public boolean filterMap(Map<String, Object> dataMap, JsonDslDefinition def, ProcessingContext ctx) {
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
    
    /**
     * 获取过滤失败的原因
     * 
     * @param obj 对象
     * @param def DSL定义
     * @param ctx 处理上下文
     * @return 失败原因列表
     */
    private <T> List<String> getFailReasons(T obj, JsonDslDefinition def, ProcessingContext ctx) {
        List<String> failReasons = new ArrayList<>();
        Map<String, Object> objMap = convertToMap(obj);
        
        if (objMap == null) {
            failReasons.add("对象转Map失败");
            return failReasons;
        }
        
        DslContext dslContext = new DslContext();
        
        // 检查字段条件
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
        
        return failReasons;
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