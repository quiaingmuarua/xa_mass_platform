package com.xa.mass.mock.runner;

import com.xa.mass.core.MassApplication;
import com.xa.mass.core.MassApplicationConfig;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.core.getway.queue.*;
import com.xa.mass.core.getway.model.enums.MessageType;
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
        
        // 使用开发环境默认配置，简化启动流程
        MassApplicationConfig config = MassApplicationConfig.createDevelopment(18088, inputQueue, outputQueue);
        
        // 创建并启动应用
        MassApplication app = new MassApplication(config);
        app.start();
        
        // 获取 DispatchRuntimeContext 并注册自定义处理器
        dispatcherContext = app.getDispatcherContext();
        DispatcherContextRegistry.register(dispatcherContext);
        
        // 注册自定义 handler
        MassMessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };
        
        // 注册到现有的 MessageHandlerRegistry
        dispatcherContext.getMessageHandlerRegistry().register("test", MessageType.TASK, "", taskHandler);
        
        log.info("✅ WebSocket Server started successfully on port {}", config.getServerPort());
        log.info("📊 Application status: {}", app.isRunning() ? "Running" : "Stopped");
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🛑 Shutting down WebSocket Server...");
            app.stop();
        }));
    }
}
