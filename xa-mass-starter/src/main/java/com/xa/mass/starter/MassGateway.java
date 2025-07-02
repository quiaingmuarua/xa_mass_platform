package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.starter.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关组件
 * 负责处理外部连接、消息路由和消息处理引擎
 */
public class MassGateway {

    private static final Logger logger = LoggerFactory.getLogger(MassGateway.class);

    private final GatewayConfig config;
    private final DispatchRuntimeContext dispatcherContext;
    private ServerMessageDispatcher messageDispatcher;
    private boolean running = false;

    public MassGateway(GatewayConfig config, DispatchRuntimeContext dispatcherContext) {
        this.config = config;
        this.dispatcherContext = dispatcherContext;
    }

    /**
     * 启动网关
     */
    public void start() {
        if (!config.isEnabled()) {
            logger.info("MassGateway is disabled, skipping start");
            return;
        }

        logger.info("🌐 Starting MassGateway with max connections: {}", config.getMaxConnections());

        try {
            // 1. 启动消息处理引擎
            startMessageEngine();

            // 2. 初始化连接管理
            initializeConnectionManagement();

            running = true;
            logger.info("✅ MassGateway started successfully");

        } catch (Exception e) {
            logger.error("❌ Failed to start MassGateway", e);
            throw new RuntimeException("Failed to start MassGateway", e);
        }
    }

    /**
     * 停止网关
     */
    public void stop() {
        if (!running) {
            logger.info("MassGateway is not running, skipping stop");
            return;
        }

        logger.info("🛑 Stopping MassGateway...");

        try {
            // 1. 停止消息处理引擎
            stopMessageEngine();

            // 2. 关闭连接管理
            shutdownConnectionManagement();

            running = false;
            logger.info("✅ MassGateway stopped successfully");

        } catch (Exception e) {
            logger.error("❌ Error stopping MassGateway", e);
        }
    }

    /**
     * 启动消息处理引擎
     */
    private void startMessageEngine() {
        logger.info("⚙️ Starting Message Processing MassEngine...");

        try {
            // 创建消息分发器
            messageDispatcher = new ServerMessageDispatcher(dispatcherContext);

            // 启动消息处理
            messageDispatcher.start();

            logger.info("✅ Message Processing MassEngine started successfully");

        } catch (Exception e) {
            logger.error("❌ Failed to start Message Processing MassEngine", e);
            throw new RuntimeException("Failed to start Message Processing MassEngine", e);
        }
    }

    /**
     * 停止消息处理引擎
     */
    private void stopMessageEngine() {
        logger.info("🛑 Stopping Message Processing MassEngine...");

        try {
            if (messageDispatcher != null) {
                messageDispatcher.stop();
                logger.info("✅ Message Processing MassEngine stopped successfully");
            }
        } catch (Exception e) {
            logger.error("❌ Error stopping Message Processing MassEngine", e);
        }
    }

    /**
     * 初始化连接管理
     */
    private void initializeConnectionManagement() {
        logger.info("🔌 Initializing connection management...");

        try {
            // TODO: 实现连接管理初始化
            // 例如：初始化连接池、设置最大连接数等

            logger.info("✅ Connection management initialized");

        } catch (Exception e) {
            logger.error("❌ Failed to initialize connection management", e);
            throw new RuntimeException("Failed to initialize connection management", e);
        }
    }

    /**
     * 关闭连接管理
     */
    private void shutdownConnectionManagement() {
        logger.info("🔌 Shutting down connection management...");

        try {
            // TODO: 实现连接管理关闭
            // 例如：关闭所有连接、释放资源等

            logger.info("✅ Connection management shut down");

        } catch (Exception e) {
            logger.error("❌ Error shutting down connection management", e);
        }
    }

    /**
     * 检查网关是否正在运行
     */
    public boolean isRunning() {
        return running && messageDispatcher != null && messageDispatcher.isRunning();
    }

    /**
     * 获取网关配置
     */
    public GatewayConfig getConfig() {
        return config;
    }

    /**
     * 获取消息分发器
     */
    public ServerMessageDispatcher getMessageDispatcher() {
        return messageDispatcher;
    }

    /**
     * 获取分发器上下文
     */
    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }
}