package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default validator for the standardized JSON DSL.
 */
class DefaultValidateProcessor implements ValidateProcessor {

    @Override
    public <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context) {
        ParameterValidator.validateValidateParams(input, definition, context);
        ParameterValidator.validateDslFieldOrCombine(definition);

        Map<String, Object> source = ProcessorDslSupport.toMap(input);
        DslContext dslContext = ProcessorDslSupport.createDslContext(source, definition, context);
        List<String> errors = new ArrayList<>();

        if (definition.getFieldDsl() != null) {
            for (Map.Entry<String, Object> entry : definition.getFieldDsl().entrySet()) {
                String fieldName = entry.getKey();
                Object currentValue = source.get(fieldName);
                try {
                    Object result = ProcessorDslSupport.evaluateRule(fieldName, currentValue, entry.getValue(), dslContext);
                    if (!ProcessorDslSupport.isTruthy(result)) {
                        errors.add(fieldName + " validation failed: " + entry.getValue());
                    }
                } catch (Exception e) {
                    errors.add(fieldName + " validation error: " + e.getMessage());
                }
            }
        }

        if (definition.getCombineDsl() != null) {
            for (Map.Entry<String, Object> entry : definition.getCombineDsl().entrySet()) {
                try {
                    Object result = ProcessorDslSupport.evaluateRule(entry.getKey(), null, entry.getValue(), dslContext);
                    if (!ProcessorDslSupport.isTruthy(result)) {
                        errors.add("combine rule '" + entry.getKey() + "' failed: " + entry.getValue());
                    }
                } catch (Exception e) {
                    errors.add("combine rule '" + entry.getKey() + "' error: " + e.getMessage());
                }
            }
        }

        if (context.isDebug()) {
            System.out.println("[DefaultValidateProcessor] Validation completed, errors: " + errors.size());
        }

        return errors;
    }

    @Override
    public String getName() {
        return "DefaultValidateProcessor";
    }

    @Override
    public int getPriority() {
        return 400;
    }
}
