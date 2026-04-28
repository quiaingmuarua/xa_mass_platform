package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * Canonical typed entry point for the standardized JSON DSL processors.
 *
 * <p>This engine is the recommended mainline path for typed JSON DSL
 * processing. Legacy/mock JSON DSL compatibility belongs to
 * {@link com.xa.mass.base.jsondsl.JsonDslEngine}.
 */
public class JsonDslProcessorEngine {

    public static <T> List<T> process(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        validateEntryArgs(definition, context, targetType);
        definition.validate();

        if (JsonDslDefinition.DslType.GENERATE.equals(definition.getType())) {
            return resolveGenerateProcessor().generate(definition, context, targetType);
        }
        if (JsonDslDefinition.DslType.FILTER.equals(definition.getType())) {
            List<T> inputList = requireInputList(context);
            return resolveFilterProcessor().filterList(inputList, definition, context).getPassed();
        }
        if (JsonDslDefinition.DslType.TRANSFORM.equals(definition.getType())) {
            T transformed = resolveTransformProcessor().transform(requireInputObject(context), definition, context);
            return List.of(transformed);
        }
        T input = requireInputObject(context);
        return validateSingle(input, definition, context);
    }

    public static <T> List<T> processChain(List<JsonDslDefinition> definitions, ProcessingContext context, Class<T> targetType) {
        ParameterValidator.notNull(definitions, "definitions");
        ParameterValidator.notNull(context, "context");
        ParameterValidator.notNull(targetType, "targetType");

        if (definitions.isEmpty()) {
            return List.of();
        }

        List<T> result = null;
        for (JsonDslDefinition definition : definitions) {
            ParameterValidator.notNull(definition, "definition");
            definition.validate();

            if (context.isDebug()) {
                System.out.println("[JsonDslProcessorEngine] Processing DSL: " + definition.getUniqueId()
                        + " (" + definition.getType() + ")");
            }

            switch (definition.getType()) {
                case GENERATE -> result = resolveGenerateProcessor().generate(definition, context, targetType);
                case FILTER -> {
                    requireChainInput(result);
                    result = resolveFilterProcessor().filterList(result, definition, context).getPassed();
                }
                case TRANSFORM -> {
                    requireChainInput(result);
                    if (!result.isEmpty()) {
                        result = result.stream()
                                .map(item -> resolveTransformProcessor().transform(item, definition, context))
                                .toList();
                    }
                }
                case VALIDATE -> {
                    requireChainInput(result);
                    if (!result.isEmpty()) {
                        for (T item : result) {
                            List<String> errors = resolveValidateProcessor().validate(item, definition, context);
                            if (!errors.isEmpty()) {
                                throw new JsonDslException("validation failed: " + String.join(", ", errors));
                            }
                        }
                    }
                }
            }
        }

        return result == null ? List.of() : result;
    }

    public static <T> List<T> processFromJson(String jsonDsl, ProcessingContext context, Class<T> targetType) {
        return process(JsonDslParser.parse(jsonDsl), context, targetType);
    }

    public static <T> List<T> processChainFromJson(List<String> jsonDslList, ProcessingContext context, Class<T> targetType) {
        ParameterValidator.notNull(jsonDslList, "jsonDslList");
        List<JsonDslDefinition> definitions = jsonDslList.stream()
                .map(JsonDslParser::parse)
                .toList();
        return processChain(definitions, context, targetType);
    }

    public static <T> List<T> filterBatch(List<T> dataList, JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        ParameterValidator.notNull(targetType, "targetType");
        ParameterValidator.validateFilterParams(dataList, definition, context);
        definition.validate();
        return resolveFilterProcessor().filterList(dataList, definition, context).getPassed();
    }

    public static <T> FilterResult<T> filterBatchWithDetails(List<T> dataList, JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        ParameterValidator.notNull(targetType, "targetType");
        ParameterValidator.validateFilterParams(dataList, definition, context);
        definition.validate();
        return resolveFilterProcessor().filterList(dataList, definition, context);
    }

    public static void registerProcessor(JsonDslProcessor processor) {
        ProcessorRegistry.register(processor);
    }

    public static List<JsonDslProcessor> getAllProcessors() {
        return ProcessorRegistry.getAllProcessors();
    }

    public static List<JsonDslProcessor> getProcessors(JsonDslDefinition.DslType type) {
        return ProcessorRegistry.getProcessors(type);
    }

    public static GenerateProcessor getGenerateProcessor() {
        return ProcessorManager.getGenerateProcessor();
    }

    public static FilterProcessor getFilterProcessor() {
        return ProcessorManager.getFilterProcessor();
    }

    public static TransformProcessor getTransformProcessor() {
        return ProcessorManager.getTransformProcessor();
    }

    public static ValidateProcessor getValidateProcessor() {
        return ProcessorManager.getValidateProcessor();
    }

    private static void validateEntryArgs(JsonDslDefinition definition, ProcessingContext context, Class<?> targetType) {
        ParameterValidator.notNull(definition, "definition");
        ParameterValidator.notNull(context, "context");
        ParameterValidator.notNull(targetType, "targetType");
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> requireInputList(ProcessingContext context) {
        Object input = context.getParameter("input");
        ParameterValidator.notNull(input, "input parameter in context");
        if (!(input instanceof List<?> list)) {
            throw new JsonDslException("input parameter in context must be a List");
        }
        return (List<T>) list;
    }

    @SuppressWarnings("unchecked")
    private static <T> T requireInputObject(ProcessingContext context) {
        T input = (T) context.getParameter("input");
        ParameterValidator.notNull(input, "input parameter in context");
        return input;
    }

    private static <T> List<T> validateSingle(T input, JsonDslDefinition definition, ProcessingContext context) {
        List<String> errors = resolveValidateProcessor().validate(input, definition, context);
        if (!errors.isEmpty()) {
            throw new JsonDslException("validation failed: " + String.join(", ", errors));
        }
        return List.of(input);
    }

    private static void requireChainInput(List<?> result) {
        ParameterValidator.notNull(result, "previous chain result");
    }

    private static GenerateProcessor resolveGenerateProcessor() {
        return (GenerateProcessor) ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.GENERATE).get(0);
    }

    private static FilterProcessor resolveFilterProcessor() {
        return (FilterProcessor) ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.FILTER).get(0);
    }

    private static TransformProcessor resolveTransformProcessor() {
        return (TransformProcessor) ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.TRANSFORM).get(0);
    }

    private static ValidateProcessor resolveValidateProcessor() {
        return (ValidateProcessor) ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.VALIDATE).get(0);
    }
}
