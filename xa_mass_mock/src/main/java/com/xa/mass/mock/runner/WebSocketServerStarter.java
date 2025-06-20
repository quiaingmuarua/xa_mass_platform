package com.xa.mass.mock.runner;

import com.xa.mass.core.MassApplication;
import com.xa.mass.core.MassApplicationConfig;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.dispatcher.MassMessageHandler;
import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.queue.MessageTransporterFactory;
import com.xa.mass.core.model.message.enums.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@Profile("server")
public class WebSocketServerStarter implements CommandLineRunner {

    @Qualifier("outputQueue")
    @Autowired
    MessageQueue<Envelope> outputQueue;

    @Qualifier("inputQueue")
    @Autowired
    MessageQueue<Envelope> inputQueue;

    private static final Logger log = LoggerFactory.getLogger(WebSocketServerStarter.class);

    // 静态暴露 DispatchRuntimeContext（保持向后兼容）
    public static DispatchRuntimeContext dispatcherContext;

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 Starting WebSocket Server with MassApplication...");
        
        // 1. 创建应用配置
        MassApplicationConfig config = new MassApplicationConfig();
        
        // 2. 配置服务器
        config.setServerPort(18088);
        config.setWebSocketPath("/ws");
        
        // 3. 配置消息传输器（使用队列）
        config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
        config.setInputQueue(inputQueue);
        config.setOutputQueue(outputQueue);
        
        // 4. 配置网关
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        
        // 5. 配置引擎
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(8);
        
        // 6. 创建并启动应用
        MassApplication app = new MassApplication(config);
        
        // 7. 注册自定义处理器（在应用启动后）
        app.start();
        
        // 8. 获取 DispatchRuntimeContext 并注册自定义处理器
        dispatcherContext = app.getDispatcherContext();
        DispatcherContextRegistry.register(dispatcherContext);
        
        // 9. 注册自定义 handler
        MassMessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };
        
        // 注册到现有的 MessageHandlerRegistry
        dispatcherContext.getMessageHandlerRegistry().register("test",MessageType.TASK, "", taskHandler);
        
        log.info("✅ WebSocket Server started successfully on port {}", config.getServerPort());
        log.info("📊 Application status: {}", app.isRunning() ? "Running" : "Stopped");
        
        // 10. 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🛑 Shutting down WebSocket Server...");
            app.stop();
        }));
    }
}
