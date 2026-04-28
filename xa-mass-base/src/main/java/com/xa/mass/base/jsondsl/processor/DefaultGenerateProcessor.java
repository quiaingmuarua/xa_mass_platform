package com.xa.mass.base.jsondsl.processor;

import com.google.gson.JsonObject;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.generate.DslObjectBuilder;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default generator for the standardized JSON DSL.
 */
class DefaultGenerateProcessor implements GenerateProcessor {

    @Override
    public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        ParameterValidator.validateGenerateParams(definition, context, targetType);

        int count = definition.getContext() != null && definition.getContext().getCount() != null
                ? definition.getContext().getCount() : 1;
        String modelName = definition.getContext().getModel();
        Map<String, Object> fieldDsl = definition.getFieldDsl() == null ? Map.of() : definition.getFieldDsl();

        List<T> result = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            DslContext dslContext = new DslContext();
            dslContext.setScopeName(modelName);
            dslContext.setStrict(Boolean.TRUE.equals(definition.getContext().getStrict()));
            dslContext.setVariable("&index", i);
            dslContext.setVariable("&" + modelName + ".index", i);
            hydrateDslContext(dslContext, definition, context);

            JsonObject dsl = new JsonObject();
            dsl.addProperty("MODEL", modelName);
            if (!fieldDsl.isEmpty()) {
                dsl.add("FIELDS", GsonConfig.buildGson().toJsonTree(fieldDsl));
            }

            if (Map.class.isAssignableFrom(targetType)) {
                Map<String, Object> resultMap = new HashMap<>();
                for (Map.Entry<String, Object> entry : fieldDsl.entrySet()) {
                    Object value = DslObjectBuilder.mockFieldValue(entry.getValue(), dslContext);
                    if (value != null) {
                        resultMap.put(entry.getKey(), value);
                    }
                }
                result.add((T) resultMap);
            } else {
                T obj = DslObjectBuilder.mockFromDsl(dsl, dslContext, targetType);
                result.add(obj);
            }
        }

        if (context.isDebug()) {
            System.out.println("[DefaultGenerateProcessor] Generated objects: " + result.size());
        }

        return result;
    }

    private void hydrateDslContext(DslContext dslContext, JsonDslDefinition definition, ProcessingContext context) {
        if (context != null) {
            context.getParameters().forEach(dslContext::setVariable);
            context.getVariables().forEach(dslContext::setVariable);
        }
        if (definition.getContext() != null && definition.getContext().getParameters() != null) {
            definition.getContext().getParameters().forEach(dslContext::setVariable);
        }
    }

    @Override
    public String getName() {
        return "DefaultGenerateProcessor";
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
