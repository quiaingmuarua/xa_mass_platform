package com.xa.mass.mock.runner;

import com.google.gson.Gson;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.InMemoryMessageQueue;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.server.MassServerBuilder;
import com.xa.mass.core.getway.server.MassServerConfig;
import com.xa.mass.core.getway.server.MassServerStater;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.model.message.enums.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@Profile("server")
public class WebSocketServerStarter implements CommandLineRunner {

    ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
    private Gson gson = new Gson();
    private static final Logger log = LoggerFactory.getLogger(WebSocketServerStarter.class);

    @Override
    public void run(String... args) throws Exception {
        // 注册基础 handler
        // 2. 构建队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();
        DispatcherContext dispatcherContext = new DispatcherContext(
                inputQueue,
                outputQueue,
                sessionManager,
                gson
        );

        ServerMessageDispatcher serverMessageDispatcher=new ServerMessageDispatcher(dispatcherContext);

        // 3. handler 示例
        MessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };

        // 4. builder 构建 server，自动注册默认中间件
        MassServerConfig server = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .registerHandler(MessageType.TASK, "", taskHandler)
                .withDefaultMiddlewares(true)
                // 如需移除默认认证中间件：.removeInputMiddleware(10)
                // 如需添加自定义中间件：.registerInputMiddleware(30, customBizMiddleware)
                .build();

        log.info(server.describe());
        log.info("Starting MassServer with builder...");
        MassServerStater stater = new MassServerStater(server);
        serverMessageDispatcher.start();
        stater.start();
    }
}
