package com.xa.mass.base.jsondsl.processor;

import com.google.gson.Gson;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON-DSL 处理器引擎
 * <p>
 * 提供统一的 DSL 处理入口，支持单个 DSL 处理和链式处理
 * 默认处理器由 ProcessorManager 统一管理，自定义处理器通过 ProcessorRegistry 注册
 * 所有方法均为强类型，确保类型安全
 * </p>
 */
public class JsonDslProcessorEngine {

    private static final Gson gson = GsonConfig.buildGson();

    /**
     * 处理单个 DSL 定义（使用强类型处理器）
     *
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> process(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        // 基础参数校验
        ParameterValidator.notNull(definition, "definition");
        ParameterValidator.notNull(context, "context");
        ParameterValidator.notNull(targetType, "targetType");

        if (JsonDslDefinition.DslType.GENERATE.equals(definition.getType())) {
            // 优先使用注册的处理器，如果没有则使用默认处理器
            List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
            if (!processors.isEmpty()) {
                // 使用第一个注册的处理器
                GenerateProcessor processor = (GenerateProcessor) processors.get(0);
                return processor.generate(definition, context, targetType);
            } else {
                GenerateProcessor processor = ProcessorManager.getGenerateProcessor();
                return processor.generate(definition, context, targetType);
            }
        } else if (JsonDslDefinition.DslType.FILTER.equals(definition.getType())) {
            // 优先使用注册的处理器，如果没有则使用默认处理器
            List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
            FilterProcessor processor;
            if (!processors.isEmpty()) {
                processor = (FilterProcessor) processors.get(0);
            } else {
                processor = ProcessorManager.getFilterProcessor();
            }
            // 从上下文中获取输入数据
            @SuppressWarnings("unchecked")
            List<T> input = (List<T>) context.getParameter("input");
            ParameterValidator.notNull(input, "input parameter in context");
            FilterResult<T> result = processor.filterList(input, definition, context);
            return result.getPassed();
        } else if (JsonDslDefinition.DslType.TRANSFORM.equals(definition.getType())) {
            // 优先使用注册的处理器，如果没有则使用默认处理器
            List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
            TransformProcessor processor;
            if (!processors.isEmpty()) {
                processor = (TransformProcessor) processors.get(0);
            } else {
                processor = ProcessorManager.getTransformProcessor();
            }
            // 从上下文中获取输入数据
            @SuppressWarnings("unchecked")
            T input = (T) context.getParameter("input");
            ParameterValidator.notNull(input, "input parameter in context");
            T result = processor.transform(input, definition, context);
            return List.of(result);
        } else if (JsonDslDefinition.DslType.VALIDATE.equals(definition.getType())) {
            // 优先使用注册的处理器，如果没有则使用默认处理器
            List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
            ValidateProcessor processor;
            if (!processors.isEmpty()) {
                processor = (ValidateProcessor) processors.get(0);
            } else {
                processor = ProcessorManager.getValidateProcessor();
            }
            // 从上下文中获取输入数据
            @SuppressWarnings("unchecked")
            T input = (T) context.getParameter("input");
            ParameterValidator.notNull(input, "input parameter in context");
            List<String> errors = processor.validate(input, definition, context);
            if (!errors.isEmpty()) {
                throw new JsonDslException("校验失败: " + String.join(", ", errors));
            }
            return List.of(input);
        } else {
            throw new JsonDslException("不支持的 DSL 类型: " + definition.getType());
        }
    }

    /**
     * 链式处理多个 DSL 定义（使用强类型处理器）
     *
     * @param definitions DSL 定义列表
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> processChain(List<JsonDslDefinition> definitions, ProcessingContext context, Class<T> targetType) {
        ParameterValidator.notNull(definitions, "definitions");
        List<T> result = null;

        for (JsonDslDefinition definition : definitions) {
            if (context.isDebug()) {
                System.out.println("[JsonDslProcessorEngine] 处理 DSL: " + definition.getUniqueId() +
                        " (类型: " + definition.getType() + ")");
            }

            if (JsonDslDefinition.DslType.GENERATE.equals(definition.getType())) {
                // 优先使用注册的处理器，如果没有则使用默认处理器
                List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
                GenerateProcessor processor;
                if (!processors.isEmpty()) {
                    processor = (GenerateProcessor) processors.get(0);
                } else {
                    processor = ProcessorManager.getGenerateProcessor();
                }
                result = processor.generate(definition, context, targetType);
            } else if (JsonDslDefinition.DslType.FILTER.equals(definition.getType())) {
                // 优先使用注册的处理器，如果没有则使用默认处理器
                List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
                FilterProcessor processor;
                if (!processors.isEmpty()) {
                    processor = (FilterProcessor) processors.get(0);
                } else {
                    processor = ProcessorManager.getFilterProcessor();
                }
                ParameterValidator.notNull(result, "previous generation result");
                FilterResult<T> filterResult = processor.filterList(result, definition, context);
                result = filterResult.getPassed();
            } else if (JsonDslDefinition.DslType.TRANSFORM.equals(definition.getType())) {
                // 优先使用注册的处理器，如果没有则使用默认处理器
                List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
                TransformProcessor processor;
                if (!processors.isEmpty()) {
                    processor = (TransformProcessor) processors.get(0);
                } else {
                    processor = ProcessorManager.getTransformProcessor();
                }
                ParameterValidator.notEmpty(result, "previous generation result");
                List<T> transformed = result.stream()
                        .map(obj -> processor.transform(obj, definition, context))
                        .toList();
                result = transformed;
            } else if (JsonDslDefinition.DslType.VALIDATE.equals(definition.getType())) {
                // 优先使用注册的处理器，如果没有则使用默认处理器
                List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
                ValidateProcessor processor;
                if (!processors.isEmpty()) {
                    processor = (ValidateProcessor) processors.get(0);
                } else {
                    processor = ProcessorManager.getValidateProcessor();
                }
                ParameterValidator.notEmpty(result, "previous generation result");
                // 校验所有对象
                for (T obj : result) {
                    List<String> errors = processor.validate(obj, definition, context);
                    if (!errors.isEmpty()) {
                        throw new JsonDslException("校验失败: " + String.join(", ", errors));
                    }
                }
            } else {
                throw new JsonDslException("不支持的 DSL 类型: " + definition.getType());
            }
        }

        return result;
    }

    /**
     * 从 JSON 字符串处理 DSL（使用强类型处理器）
     *
     * @param jsonDsl JSON DSL 字符串
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> processFromJson(String jsonDsl, ProcessingContext context, Class<T> targetType) {
        JsonDslDefinition definition = gson.fromJson(jsonDsl, JsonDslDefinition.class);
        return process(definition, context, targetType);
    }

    /**
     * 从 JSON 字符串链式处理多个 DSL（使用强类型处理器）
     *
     * @param jsonDslList JSON DSL 字符串列表
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> processChainFromJson(List<String> jsonDslList, ProcessingContext context, Class<T> targetType) {
        List<JsonDslDefinition> definitions = jsonDslList.stream()
                .map(json -> gson.fromJson(json, JsonDslDefinition.class))
                .toList();
        return processChain(definitions, context, targetType);
    }

    /**
     * 批量过滤对象列表
     *
     * @param dataList 要过滤的对象列表
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 过滤后的对象列表
     */
    public static <T> List<T> filterBatch(List<T> dataList, JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        // 基础参数校验
        ParameterValidator.notNull(dataList, "dataList");
        ParameterValidator.notNull(definition, "definition");
        ParameterValidator.notNull(context, "context");
        ParameterValidator.notNull(targetType, "targetType");

        // 获取过滤处理器
        List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
        FilterProcessor processor;
        if (!processors.isEmpty()) {
            processor = (FilterProcessor) processors.get(0);
        } else {
            processor = ProcessorManager.getFilterProcessor();
        }

        // 批量过滤
        FilterResult<T> result = processor.filterList(dataList, definition, context);
        return result.getPassed();
    }

    /**
     * 批量过滤对象列表（带失败详情）
     *
     * @param dataList 要过滤的对象列表
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 过滤结果，包含通过和失败的对象
     */
    public static <T> FilterResult<T> filterBatchWithDetails(List<T> dataList, JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        // 基础参数校验
        ParameterValidator.notNull(dataList, "dataList");
        ParameterValidator.notNull(definition, "definition");
        ParameterValidator.notNull(context, "context");
        ParameterValidator.notNull(targetType, "targetType");

        // 获取过滤处理器
        List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(definition.getType());
        FilterProcessor processor;
        if (!processors.isEmpty()) {
            processor = (FilterProcessor) processors.get(0);
        } else {
            processor = ProcessorManager.getFilterProcessor();
        }

        // 批量过滤
        List<T> passed = new ArrayList<>();
        List<FilterResult.FilterFailure<T>> failed = new ArrayList<>();

        for (T item : dataList) {
            FilterResult<T> itemResult = processor.filter(item, definition, context);
            if (!itemResult.getPassed().isEmpty()) {
                passed.add(item);
            } else {
                // 这里可以扩展为获取具体的失败原因
                failed.add(new FilterResult.FilterFailure<>(item, List.of("未通过过滤条件")));
            }
        }

        return new FilterResult<>(passed, failed, dataList.size());
    }

    /**
     * 注册自定义处理器
     *
     * @param processor 自定义处理器
     */
    public static void registerProcessor(JsonDslProcessor processor) {
        ProcessorRegistry.register(processor);
    }

    /**
     * 获取所有处理器
     *
     * @return 处理器列表
     */
    public static List<JsonDslProcessor> getAllProcessors() {
        return ProcessorRegistry.getAllProcessors();
    }

    /**
     * 获取支持指定类型的处理器
     *
     * @param type DSL 类型
     * @return 处理器列表
     */
    public static List<JsonDslProcessor> getProcessors(JsonDslDefinition.DslType type) {
        return ProcessorRegistry.getProcessors(type);
    }

    /**
     * 获取强类型生成处理器
     *
     * @return 生成处理器
     */
    public static GenerateProcessor getGenerateProcessor() {
        return ProcessorManager.getGenerateProcessor();
    }

    /**
     * 获取强类型过滤处理器
     *
     * @return 过滤处理器
     */
    public static FilterProcessor getFilterProcessor() {
        return ProcessorManager.getFilterProcessor();
    }

    /**
     * 获取强类型转换处理器
     *
     * @return 转换处理器
     */
    public static TransformProcessor getTransformProcessor() {
        return ProcessorManager.getTransformProcessor();
    }

    /**
     * 获取强类型校验处理器
     *
     * @return 校验处理器
     */
    public static ValidateProcessor getValidateProcessor() {
        return ProcessorManager.getValidateProcessor();
    }
} 