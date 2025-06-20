package com.xa.mass.core.getway.dispatcher.handler;

import java.util.Collections;

/**
 * Handler 解析结果
 * 包含解析状态、handler 实例和相关信息
 */
public class ResolutionResult {
    
    public enum Status {
        FOUND,           // 找到匹配的 handler
        FALLBACK,        // 使用 fallback handler
        NOT_FOUND        // 未找到任何 handler
    }
    
    private final Status status;
    private final MassMessageHandler handler;
    private final String project;
    private final String messageType;
    private final String subMessageType;
    private final String resolutionPath; // 解析路径，如 "project" 或 "global" 或 "fallback"
    
    private ResolutionResult(Status status, MassMessageHandler handler, String project, 
                           String messageType, String subMessageType, String resolutionPath) {
        this.status = status;
        this.handler = handler;
        this.project = project;
        this.messageType = messageType;
        this.subMessageType = subMessageType;
        this.resolutionPath = resolutionPath;
    }
    
    /**
     * 创建找到 handler 的结果
     */
    public static ResolutionResult found(MassMessageHandler handler, String project, 
                                       String messageType, String subMessageType, String resolutionPath) {
        return new ResolutionResult(Status.FOUND, handler, project, messageType, subMessageType, resolutionPath);
    }
    
    /**
     * 创建 fallback 结果
     */
    public static ResolutionResult fallback(String project, String messageType, String subMessageType) {
        return new ResolutionResult(Status.FALLBACK, createFallbackHandler(), project, messageType, subMessageType, "fallback");
    }
    
    /**
     * 创建未找到的结果
     */
    public static ResolutionResult notFound(String project, String messageType, String subMessageType) {
        return new ResolutionResult(Status.NOT_FOUND, null, project, messageType, subMessageType, "none");
    }
    
    /**
     * 创建 fallback handler
     */
    private static MassMessageHandler createFallbackHandler() {
        return msg -> {
            // 这里可以注入 logger，但为了简化，我们返回空列表
            // 实际使用时，调用方可以根据 status 决定是否记录日志
            return Collections.emptyList();
        };
    }
    
    // Getter 方法
    public Status getStatus() { return status; }
    public MassMessageHandler getHandler() { return handler; }
    public String getProject() { return project; }
    public String getMessageType() { return messageType; }
    public String getSubMessageType() { return subMessageType; }
    public String getResolutionPath() { return resolutionPath; }
    
    /**
     * 检查是否找到了 handler
     */
    public boolean isFound() { return status == Status.FOUND; }
    
    /**
     * 检查是否使用了 fallback
     */
    public boolean isFallback() { return status == Status.FALLBACK; }
    
    /**
     * 检查是否未找到
     */
    public boolean isNotFound() { return status == Status.NOT_FOUND; }
    
    /**
     * 获取可用的 handler（如果存在）
     */
    public MassMessageHandler getHandlerOrFallback() {
        return handler != null ? handler : createFallbackHandler();
    }
    
    @Override
    public String toString() {
        return String.format("ResolutionResult{status=%s, project='%s', messageType='%s', subMessageType='%s', resolutionPath='%s'}", 
                           status, project, messageType, subMessageType, resolutionPath);
    }
} 