package com.xa.mass.mock.runner;

import com.google.gson.Gson;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.dispatcher.MassMessageHandler;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.server.MassServerBuilder;
import com.xa.mass.core.getway.server.MassServerConfig;
import com.xa.mass.core.getway.server.MassServerStater;
import com.xa.mass.core.getway.session.ServerSessionManager;
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


    ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
    private Gson gson = new Gson();
    private static final Logger log = LoggerFactory.getLogger(WebSocketServerStarter.class);

    // 静态暴露 DispatcherContext
    public static DispatcherContext dispatcherContext;

    @Override
    public void run(String... args) throws Exception {
        // 1. 构建 dispatcher 上下文
        dispatcherContext = new DispatcherContext(inputQueue, outputQueue, sessionManager, gson);
        DispatcherContextRegistry.register(dispatcherContext);

        // 2. handler 示例
        MassMessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };

        // 3. builder 构建 server，自动注册默认中间件和处理器
        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .withDispatcherContext(dispatcherContext) // 注入上下文
                .registerHandler(MessageType.TASK, "", taskHandler) // 注册自定义 handler
                .withDefaultMiddlewares(true)
                // 如需移除默认认证中间件：.unregisterInputMiddleware(10)
                // 如需添加自定义中间件：.registerInputMiddleware(30, customBizMiddleware)
                .build();

        log.info(serverConfig.describe());
        log.info("Starting MassServer with builder...");
        MassServerStater stater = new MassServerStater(serverConfig);
        stater.start();
    }
}
