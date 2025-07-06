package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * JSON-DSL 处理器引擎
 * <p>
 * 新的 DSL 处理引擎，基于处理器模式，支持链式调用和扩展
 * </p>
 */
public class JsonDslProcessorEngine {
    
    /**
     * 处理单个 DSL 定义
     * 
     * @param definition DSL 定义
     * @return 处理结果
     */
    public static Object process(JsonDslDefinition definition) {
        return process(definition, new ProcessingContext());
    }
    
    /**
     * 处理单个 DSL 定义
     * 
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 处理结果
     */
    public static Object process(JsonDslDefinition definition, ProcessingContext context) {
        return ProcessorRegistry.processChain(definition, context);
    }
    
    /**
     * 处理多个 DSL 定义（链式调用）
     * 
     * @param definitions DSL 定义列表
     * @return 最终处理结果
     */
    public static Object processChain(List<JsonDslDefinition> definitions) {
        return processChain(definitions, new ProcessingContext());
    }
    
    /**
     * 处理多个 DSL 定义（链式调用）
     * 
     * @param definitions DSL 定义列表
     * @param context 处理上下文
     * @return 最终处理结果
     */
    public static Object processChain(List<JsonDslDefinition> definitions, ProcessingContext context) {
        return ProcessorRegistry.processChain(definitions, context);
    }
    
    /**
     * 从 JSON 字符串解析并处理 DSL
     * 
     * @param jsonDsl JSON 字符串
     * @return 处理结果
     */
    public static Object processFromJson(String jsonDsl) {
        return processFromJson(jsonDsl, new ProcessingContext());
    }
    
    /**
     * 从 JSON 字符串解析并处理 DSL
     * 
     * @param jsonDsl JSON 字符串
     * @param context 处理上下文
     * @return 处理结果
     */
    public static Object processFromJson(String jsonDsl, ProcessingContext context) {
        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);
        return process(definition, context);
    }
    
    /**
     * 从 JSON 字符串解析并链式处理多个 DSL
     * 
     * @param jsonDslList JSON 字符串列表
     * @return 最终处理结果
     */
    public static Object processChainFromJson(List<String> jsonDslList) {
        return processChainFromJson(jsonDslList, new ProcessingContext());
    }
    
    /**
     * 从 JSON 字符串解析并链式处理多个 DSL
     * 
     * @param jsonDslList JSON 字符串列表
     * @param context 处理上下文
     * @return 最终处理结果
     */
    public static Object processChainFromJson(List<String> jsonDslList, ProcessingContext context) {
        List<JsonDslDefinition> definitions = jsonDslList.stream()
            .map(JsonDslParser::parse)
            .toList();
        return processChain(definitions, context);
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
} 