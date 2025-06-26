package com.xa.mass.starter;

import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 引擎组件
 * 负责任务调度、设备管理等核心业务逻辑的启动与聚合
 */

/**
 * 引擎组件（已简化）
 * 消息处理引擎功能已移至 MassGateway 中
 * 此类保留用于未来扩展其他引擎功能
 */
public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);

    private final EngineConfig config;
    private boolean running = false;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    /**
     * 启动引擎
     */
    public void start() {
        if (!config.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }

        logger.info("⚙️ Starting MassEngine with {} worker threads", config.getWorkerThreads());

        try {
            // TODO: 实现其他引擎功能
            // 例如：任务调度、规则引擎、数据分析等

            running = true;
            logger.info("✅ MassEngine started successfully");

        } catch (Exception e) {
            logger.error("❌ Failed to start MassEngine", e);
            throw new RuntimeException("Failed to start MassEngine", e);
        }
    }

    /**
     * 停止引擎
     */
    public void stop() {
        if (!running) {
            logger.info("MassEngine is not running, skipping stop");
            return;
        }

        logger.info("🛑 Stopping MassEngine...");

        try {
            // TODO: 实现引擎停止逻辑

            running = false;
            logger.info("✅ MassEngine stopped successfully");

        } catch (Exception e) {
            logger.error("❌ Error stopping MassEngine", e);
        }
    }

    /**
     * 检查引擎是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取引擎配置
     */
    public EngineConfig getConfig() {
        return config;
    }
}