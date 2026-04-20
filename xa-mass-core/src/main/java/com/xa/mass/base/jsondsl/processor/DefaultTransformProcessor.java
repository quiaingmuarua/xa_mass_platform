package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.Map;

/**
 * Default transformer for the standardized JSON DSL.
 */
class DefaultTransformProcessor implements TransformProcessor {

    @Override
    public <T> T transform(T input, JsonDslDefinition definition, ProcessingContext context) {
        ParameterValidator.validateTransformParams(input, definition, context);
        ParameterValidator.validateDslFieldOrCombine(definition);

        Map<String, Object> source = ProcessorDslSupport.toMap(input);
        T target = ProcessorDslSupport.copyInput(input);
        DslContext dslContext = ProcessorDslSupport.createDslContext(source, definition, context);

        if (definition.getFieldDsl() != null) {
            for (Map.Entry<String, Object> entry : definition.getFieldDsl().entrySet()) {
                String fieldName = entry.getKey();
                Object currentValue = source.get(fieldName);
                Object transformedValue = ProcessorDslSupport.evaluateRule(fieldName, currentValue, entry.getValue(), dslContext);
                ProcessorDslSupport.writeField(target, fieldName, transformedValue);
                source.put(fieldName, transformedValue);
                dslContext.setVariable(fieldName, transformedValue);
            }
        }

        if (definition.getCombineDsl() != null) {
            for (Map.Entry<String, Object> entry : definition.getCombineDsl().entrySet()) {
                Object result = ProcessorDslSupport.evaluateRule(entry.getKey(), null, entry.getValue(), dslContext);
                if (!ProcessorDslSupport.isTruthy(result)) {
                    throw new com.xa.mass.base.jsondsl.builtin.JsonDslException("组合条件转换失败: " + entry.getKey());
                }
            }
        }

        if (context.isDebug()) {
            System.out.println("[DefaultTransformProcessor] Transformation completed");
        }

        return target;
    }

    @Override
    public String getName() {
        return "DefaultTransformProcessor";
    }

    @Override
    public int getPriority() {
        return 300;
    }
}
