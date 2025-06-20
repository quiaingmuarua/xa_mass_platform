package com.xa.mass.mock.runner;

import com.google.gson.Gson;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.dispatcher.MassMessageHandler;
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

    @Override
    public void run(String... args) throws Exception {
        // 注册基础 handler
        // 2. 构建队列
        DispatcherContext dispatcherContext = new DispatcherContext(inputQueue, outputQueue, sessionManager, gson);

        ServerMessageDispatcher serverMessageDispatcher=new ServerMessageDispatcher(dispatcherContext);

        // 3. handler 示例
        MassMessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };

        // 4. builder 构建 server，自动注册默认中间件
        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .registerHandler(MessageType.TASK, "", taskHandler)
                .withDefaultMiddlewares(true).withServerMessageDispatcher(serverMessageDispatcher)
                // 如需移除默认认证中间件：.removeInputMiddleware(10)
                // 如需添加自定义中间件：.registerInputMiddleware(30, customBizMiddleware)
                .build();

//        log.info(server.describe());
        log.info("Starting MassServer with builder...");
        MassServerStater stater = new MassServerStater(serverConfig, dispatcherContext);
        stater.start();
    }
}
