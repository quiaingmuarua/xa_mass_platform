package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.queue.MessageTransporter;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.server.MassServerBuilder;
import com.xa.mass.gateway.server.MassServerConfig;
import com.xa.mass.gateway.server.MassServerStater;
import com.xa.mass.gateway.session.ServerSessionManager;

import com.xa.mass.starter.config.MassApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mass 应用主程序
 * 统一管理系统的启动流程，包括网关、引擎等组件的初始化
 */
public class MassApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);
    
    private final MassApplicationConfig config;
    private MassGateway massGateway;
    private MassEngine engine;
    private DispatchRuntimeContext dispatcherContext;
    private ServerMessageDispatcher messageDispatcher;
    private MassServerStater serverStater;
    
    public MassApplication(MassApplicationConfig config) {
        this.config = config;
    }
    
    /**
     * 启动整个 Mass 应用
     */
    public void start() {
        logger.info("🚀 Starting Mass Application...");
        
        try {
            // 1. 初始化核心组件
            initializeComponents();
            
            // 2. 启动网关
            startGateway();
            
            // 3. 启动引擎
            startEngine();
            
            // 4. 启动消息分发器
            startMessageDispatcher();
            
            // 5. 启动 WebSocket 服务器
            startWebSocketServer();
            
            logger.info("✅ Mass Application started successfully!");
            
        } catch (Exception e) {
            logger.error("❌ Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }
    
    /**
     * 停止整个 Mass 应用
     */
    public void stop() {
        logger.info("🛑 Stopping Mass Application...");
        
        try {
            if (serverStater != null) {
                serverStater.stop();
            }
            
            if (messageDispatcher != null) {
                // TODO: 添加停止方法到 ServerMessageDispatcher
                logger.info("Message dispatcher stopped");
            }
            
            if (engine != null) {
                engine.stop();
            }
            
            if (massGateway != null) {
                massGateway.stop();
            }
            
            logger.info("✅ Mass Application stopped successfully!");
            
        } catch (Exception e) {
            logger.error("❌ Error stopping Mass Application", e);
        }
    }
    
    /**
     * 初始化核心组件
     */
    private void initializeComponents() {
        logger.info("🔧 Initializing core components...");
        
        try {
            // 初始化会话管理器
            ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
            logger.info("✅ Session manager initialized");
            
            // 创建消息传输器
            MessageTransporter messageTransporter = config.createMessageTransporter();
            logger.info("✅ Message transporter created");
            
            // 创建消息编解码器
            MessageCodec messageCodec = config.createMessageCodec();
            logger.info("✅ Message codec created");
            
            // 创建分发器上下文
            dispatcherContext = new DispatcherContext(messageTransporter, sessionManager, messageCodec);
            logger.info("✅ Dispatcher context created");
            
            // 注册到注册表
            try {
                DispatcherContextRegistry.register(dispatcherContext);
                logger.info("✅ Dispatcher context registered");
            } catch (Exception e) {
                logger.error("❌ Failed to register dispatcher context", e);
                throw e;
            }
            
            // 注册消息处理器
            MessageHandlerRegistry messageHandlerRegistry = new MessageHandlerRegistry();
            messageHandlerRegistry.autoRegister();
            dispatcherContext.setMessageHandlerRegistry(messageHandlerRegistry);
            logger.info("✅ Message handler registry initialized");
            
            // 注册中间件
            MiddlewareRegistry.autoRegister();
            logger.info("✅ Middleware registry initialized");
            
        } catch (Exception e) {
            logger.error("❌ Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }
        
        logger.info("✅ Core components initialized");
    }
    
    /**
     * 启动网关
     */
    private void startGateway() {
        logger.info("🌐 Starting MassGateway...");
        massGateway = new MassGateway(config.getGatewayConfig(), dispatcherContext);
        massGateway.start();
        logger.info("✅ MassGateway started");
    }
    
    /**
     * 启动引擎
     */
    private void startEngine() {
        logger.info("⚙️ Starting MassEngine...");
        engine = new MassEngine(config.getEngineConfig());
        engine.start();
        logger.info("✅ MassEngine started");
    }
    
    /**
     * 启动消息分发器
     */
    private void startMessageDispatcher() {
        // 消息分发器现在由 MassGateway 管理，不需要单独启动
        logger.info("📨 Message Dispatcher is managed by MassGateway");
    }
    
    /**
     * 启动 WebSocket 服务器
     */
    private void startWebSocketServer() {
        logger.info("🔌 Starting WebSocket Server...");
        
        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(config.getServerPort())
                .withWebSocketPath(config.getWebSocketPath())
                .withDispatcherContext(dispatcherContext)
                .build();
        
        serverStater = new MassServerStater(serverConfig);
        serverStater.start();
        
        logger.info("✅ WebSocket Server started on port {}", config.getServerPort());
    }
    
    /**
     * 获取分发器上下文
     */
    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }
    
    /**
     * 检查应用是否正在运行
     */
    public boolean isRunning() {
        return serverStater != null && serverStater.isRunning();
    }
} 