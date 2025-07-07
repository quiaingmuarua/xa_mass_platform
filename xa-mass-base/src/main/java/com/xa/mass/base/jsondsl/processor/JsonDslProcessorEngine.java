package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * JSON-DSL 处理器引擎
 * <p>
 * 新的 DSL 处理引擎，基于强类型处理器模式，支持链式调用和扩展
 * </p>
 */
public class JsonDslProcessorEngine {
    
    /**
     * 处理单个 DSL 定义（使用强类型处理器）
     * 
     * @param definition DSL 定义
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> process(JsonDslDefinition definition, Class<T> targetType) {
        return process(definition, new ProcessingContext(), targetType);
    }
    
    /**
     * 处理单个 DSL 定义（使用强类型处理器）
     * 
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> process(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        if (JsonDslDefinition.DslType.GENERATE.equals(definition.getType())) {
            GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
            return processor.generate(definition, context, targetType);
        } else if (JsonDslDefinition.DslType.FILTER.equals(definition.getType())) {
            FilterProcessor processor = ProcessorRegistry.getFilterProcessor();
            // 从上下文中获取输入数据
            @SuppressWarnings("unchecked")
            List<T> input = (List<T>) context.getParameter("objects");
            if (input == null) {
                throw new IllegalArgumentException("过滤处理器需要上下文中的 'objects' 参数");
            }
            return processor.filter(input, definition, context);
        } else if (JsonDslDefinition.DslType.TRANSFORM.equals(definition.getType())) {
            TransformProcessor processor = ProcessorRegistry.getTransformProcessor();
            // 从上下文中获取输入数据
            @SuppressWarnings("unchecked")
            T input = (T) context.getParameter("object");
            if (input == null) {
                throw new IllegalArgumentException("转换处理器需要上下文中的 'object' 参数");
            }
            T result = processor.transform(input, definition, context);
            return List.of(result);
        } else if (JsonDslDefinition.DslType.VALIDATE.equals(definition.getType())) {
            ValidateProcessor processor = ProcessorRegistry.getValidateProcessor();
            // 从上下文中获取输入数据
            @SuppressWarnings("unchecked")
            T input = (T) context.getParameter("object");
            if (input == null) {
                throw new IllegalArgumentException("校验处理器需要上下文中的 'object' 参数");
            }
            List<String> errors = processor.validate(input, definition, context);
            if (errors.isEmpty()) {
                return List.of(input);
            } else {
                throw new IllegalArgumentException("校验失败: " + String.join(", ", errors));
            }
        } else {
            throw new IllegalArgumentException("不支持的 DSL 类型: " + definition.getType());
        }
    }
    
    /**
     * 处理多个 DSL 定义（链式调用，使用强类型处理器）
     * 
     * @param definitions DSL 定义列表
     * @param targetType 目标类型
     * @return 最终处理结果
     */
    public static <T> List<T> processChain(List<JsonDslDefinition> definitions, Class<T> targetType) {
        return processChain(definitions, new ProcessingContext(), targetType);
    }
    
    /**
     * 处理多个 DSL 定义（链式调用，使用强类型处理器）
     * 
     * @param definitions DSL 定义列表
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 最终处理结果
     */
    public static <T> List<T> processChain(List<JsonDslDefinition> definitions, ProcessingContext context, Class<T> targetType) {
        return ProcessorRegistry.processGenerateChain(definitions, context, targetType);
    }
    
    /**
     * 从 JSON 字符串解析并处理 DSL（使用强类型处理器）
     * 
     * @param jsonDsl JSON 字符串
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> processFromJson(String jsonDsl, Class<T> targetType) {
        return processFromJson(jsonDsl, new ProcessingContext(), targetType);
    }
    
    /**
     * 从 JSON 字符串解析并处理 DSL（使用强类型处理器）
     * 
     * @param jsonDsl JSON 字符串
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 处理结果
     */
    public static <T> List<T> processFromJson(String jsonDsl, ProcessingContext context, Class<T> targetType) {
        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);
        return process(definition, context, targetType);
    }
    
    /**
     * 从 JSON 字符串解析并链式处理多个 DSL（使用强类型处理器）
     * 
     * @param jsonDslList JSON 字符串列表
     * @param targetType 目标类型
     * @return 最终处理结果
     */
    public static <T> List<T> processChainFromJson(List<String> jsonDslList, Class<T> targetType) {
        return processChainFromJson(jsonDslList, new ProcessingContext(), targetType);
    }
    
    /**
     * 从 JSON 字符串解析并链式处理多个 DSL（使用强类型处理器）
     * 
     * @param jsonDslList JSON 字符串列表
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 最终处理结果
     */
    public static <T> List<T> processChainFromJson(List<String> jsonDslList, ProcessingContext context, Class<T> targetType) {
        List<JsonDslDefinition> definitions = jsonDslList.stream()
            .map(JsonDslParser::parse)
            .toList();
        return processChain(definitions, context, targetType);
    }
    
    /**
     * 注册自定义处理器
     * 
     * @param processor 处理器
     */
    public static void registerProcessor(JsonDslProcessor processor) {
        ProcessorRegistry.register(processor);
    }
    
    /**
     * 获取所有已注册的处理器
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
        return ProcessorRegistry.getGenerateProcessor();
    }
    
    /**
     * 获取强类型过滤处理器
     * 
     * @return 过滤处理器
     */
    public static FilterProcessor getFilterProcessor() {
        return ProcessorRegistry.getFilterProcessor();
    }
    
    /**
     * 获取强类型转换处理器
     * 
     * @return 转换处理器
     */
    public static TransformProcessor getTransformProcessor() {
        return ProcessorRegistry.getTransformProcessor();
    }
    
    /**
     * 获取强类型校验处理器
     * 
     * @return 校验处理器
     */
    public static ValidateProcessor getValidateProcessor() {
        return ProcessorRegistry.getValidateProcessor();
    }
} 