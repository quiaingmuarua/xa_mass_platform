package com.xa.mass.engine.example;

import com.xa.mass.engine.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON日志测试类
 */
public class JsonLogTest {

    private static final Logger logger = LoggerFactory.getLogger(JsonLogTest.class);

    public static void main(String[] args) {
        System.out.println("Testing JSON Logging Configuration...");

        // 测试基本日志
        logger.info("This is an info message");
        logger.warn("This is a warning message");
        logger.error("This is an error message");

        // 测试异常日志
        try {
            throw new RuntimeException("Test exception");
        } catch (Exception e) {
            logger.error("Caught exception", e);
        }

        // 测试MDC字段
        LogUtils.setTraceId("trace-12345");
        LogUtils.setUserId("user-001");
        LogUtils.setDeviceId("device-001");
        LogUtils.setTaskId("task-001");
        LogUtils.setOperation("TEST_OPERATION");
        LogUtils.setModule("TEST_MODULE");
        LogUtils.setResult("SUCCESS");
        LogUtils.setDuration(100);

        logger.info("This is a structured log message with MDC fields");

        // 测试结构化日志方法
        LogUtils.logOperationStart("CREATE_TASK", "JsonLogTest",
                "taskName", "Test Task",
                "project", "Demo");

        LogUtils.logOperationSuccess("Task created successfully", 150);

        LogUtils.logDeviceOperation("device-001", "LOGIN", "SUCCESS");
        LogUtils.logTaskOperation("task-001", "ASSIGN", "SUCCESS");

        // 清除MDC字段
        LogUtils.clearMdc();
        logger.info("MDC fields cleared");

        System.out.println("JSON Logging test completed. Check console output for JSON format.");
    }
} 