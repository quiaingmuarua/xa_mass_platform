package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class DefaultFilterProcessor implements FilterProcessor {

    @Override
    public <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context) {
        ParameterValidator.notNull(data, "data");
        ParameterValidator.notNull(definition, "definition");
        ParameterValidator.notNull(context, "context");
        ParameterValidator.validateDslType(definition, JsonDslDefinition.DslType.FILTER);
        ParameterValidator.validateDslFieldOrCombine(definition);

        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] Filtering single object: " + definition.getUniqueId());
        }

        Map<String, Object> source = toMap(data);
        List<String> reasons = evaluateReasons(source, definition, context);
        return FilterResult.of(data, reasons.isEmpty(), reasons.isEmpty() ? null : reasons);
    }

    @Override
    public <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context) {
        ParameterValidator.validateFilterParams(dataList, definition, context);

        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] Filtering batch, size=" + dataList.size());
        }

        List<T> passed = new ArrayList<>();
        List<FilterResult.FilterFailure<T>> failed = new ArrayList<>();

        for (T item : dataList) {
            FilterResult<T> itemResult = filter(item, definition, context);
            if (itemResult.isAllPassed()) {
                passed.add(item);
            } else {
                failed.add(itemResult.getFailed().get(0));
            }
        }

        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] Filtering completed, passed=" + passed.size() + "/" + dataList.size());
        }

        return new FilterResult<>(passed, failed, dataList.size());
    }

    private Map<String, Object> toMap(Object data) {
        try {
            return ProcessorDslSupport.toMap(data);
        } catch (JsonDslException e) {
            throw new JsonDslException("filter input is not mappable: " + data.getClass().getName(), e);
        }
    }

    private List<String> evaluateReasons(Map<String, Object> source, JsonDslDefinition definition, ProcessingContext context) {
        List<String> reasons = new ArrayList<>();
        DslContext dslContext = ProcessorDslSupport.createDslContext(source, definition, context);

        if (definition.getFieldDsl() != null) {
            for (Map.Entry<String, Object> entry : definition.getFieldDsl().entrySet()) {
                String fieldName = entry.getKey();
                Object rule = entry.getValue();
                Object currentValue = source.get(fieldName);
                try {
                    Object result = ProcessorDslSupport.evaluateRule(fieldName, currentValue, rule, dslContext);
                    if (!ProcessorDslSupport.isTruthy(result)) {
                        reasons.add("field '" + fieldName + "' failed: " + rule);
                    }
                } catch (Exception e) {
                    reasons.add("field '" + fieldName + "' error: " + e.getMessage());
                }
            }
        }

        if (definition.getCombineDsl() != null) {
            for (Map.Entry<String, Object> entry : definition.getCombineDsl().entrySet()) {
                String ruleName = entry.getKey();
                Object rule = entry.getValue();
                try {
                    Object result = ProcessorDslSupport.evaluateRule(ruleName, null, rule, dslContext);
                    if (!ProcessorDslSupport.isTruthy(result)) {
                        reasons.add("combine rule '" + ruleName + "' failed: " + rule);
                    }
                } catch (Exception e) {
                    reasons.add("combine rule '" + ruleName + "' error: " + e.getMessage());
                }
            }
        }

        return reasons;
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
        return 200;
    }
}
