package com.xa.mass.engine.example;

import com.xa.mass.engine.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构化日志示例类
 * 演示如何使用LogUtils工具类记录结构化JSON日志
 */
public class LoggingExample {
    
    private static final Logger logger = LoggerFactory.getLogger(LoggingExample.class);
    
    public static void main(String[] args) {
        System.out.println("=== 结构化JSON日志示例 ===");
        
        // 基本日志记录
        logger.info("应用启动");
        logger.warn("这是一个警告信息");
        logger.error("这是一个错误信息");
        
        // 使用LogUtils记录结构化日志
        LogUtils.logOperationStart("TASK_CREATION", "LoggingExample", 
                                 "taskName", "测试任务",
                                 "project", "demo");
        
        try {
            // 模拟业务操作
            Thread.sleep(100);
            
            // 记录成功日志
            LogUtils.logOperationSuccess("任务创建成功", 100);
            
        } catch (Exception e) {
            // 记录失败日志
            LogUtils.logOperationFailure("TASK_CREATE_ERROR", e.getMessage(), 100);
        }
        
        // 记录设备操作日志
        LogUtils.logDeviceOperation("device-001", "LOGIN", "SUCCESS");
        LogUtils.logDeviceOperation("device-002", "TASK_EXECUTE", "FAILED");
        
        // 记录任务操作日志
        LogUtils.logTaskOperation("task-001", "ASSIGN", "SUCCESS");
        LogUtils.logTaskOperation("task-002", "PAUSE", "SUCCESS");
        
        // 记录令牌操作日志
        LogUtils.logTokenOperation("token-001", "VALIDATE", "SUCCESS");
        LogUtils.logTokenOperation("token-002", "REFRESH", "FAILED");
        
        // 记录规则评估日志
        LogUtils.logRuleEvaluation("rule-001", "device-001", "task-001", true);
        LogUtils.logRuleEvaluation("rule-002", "device-002", "task-002", false);
        
        // 记录任务分配日志
        LogUtils.logTaskAssignment("task-001", "device-001", "SUCCESS");
        LogUtils.logTaskAssignment("task-002", "device-002", "FAILED");
        
        // 演示MDC字段的使用
        LogUtils.setTraceId("trace-12345");
        LogUtils.setUserId("user-001");
        LogUtils.setDeviceId("device-001");
        LogUtils.setTaskId("task-001");
        LogUtils.setTokenId("token-001");
        LogUtils.setOperation("COMPLEX_OPERATION");
        LogUtils.setModule("BUSINESS_LOGIC");
        LogUtils.setResult("SUCCESS");
        LogUtils.setDuration(150);
        
        logger.info("这是一个包含完整MDC字段的日志消息");
        
        // 清除MDC字段
        LogUtils.clearMdc();
        
        logger.info("MDC字段已清除");
        
        System.out.println("=== 日志示例完成 ===");
    }
} 