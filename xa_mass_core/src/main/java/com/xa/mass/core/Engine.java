package com.xa.mass.core;

import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息处理引擎
 * 负责消息的分发、处理和路由
 * 内部使用 ServerMessageDispatcher 实现具体的消息处理逻辑
 */
public class Engine {
    
    private static final Logger logger = LoggerFactory.getLogger(Engine.class);
    
    private final MassApplicationConfig.EngineConfig config;
    private final DispatchRuntimeContext dispatcherContext;
    private ServerMessageDispatcher messageDispatcher;
    private boolean running = false;
    
    public Engine(MassApplicationConfig.EngineConfig config, DispatchRuntimeContext dispatcherContext) {
        this.config = config;
        this.dispatcherContext = dispatcherContext;
    }
    
    /**
     * 启动引擎
     */
    public void start() {
        if (!config.isEnabled()) {
            logger.info("Engine is disabled, skipping start");
            return;
        }
        
        logger.info("⚙️ Starting Message Processing Engine with {} worker threads", config.getWorkerThreads());
        
        try {
            // 创建消息分发器
            messageDispatcher = new ServerMessageDispatcher(dispatcherContext);
            
            // 启动消息处理
            messageDispatcher.start();
            
            running = true;
            logger.info("✅ Message Processing Engine started successfully");
            
        } catch (Exception e) {
            logger.error("❌ Failed to start Message Processing Engine", e);
            throw new RuntimeException("Failed to start Message Processing Engine", e);
        }
    }
    
    /**
     * 停止引擎
     */
    public void stop() {
        if (!running) {
            logger.info("Engine is not running, skipping stop");
            return;
        }
        
        logger.info("🛑 Stopping Message Processing Engine...");
        
        try {
            // 停止消息分发器
            if (messageDispatcher != null) {
                messageDispatcher.stop();
            }
            
            running = false;
            logger.info("✅ Message Processing Engine stopped successfully");
            
        } catch (Exception e) {
            logger.error("❌ Error stopping Message Processing Engine", e);
        }
    }
    
    /**
     * 检查引擎是否正在运行
     */
    public boolean isRunning() {
        return running && messageDispatcher != null;
    }
    
    /**
     * 获取引擎配置
     */
    public MassApplicationConfig.EngineConfig getConfig() {
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