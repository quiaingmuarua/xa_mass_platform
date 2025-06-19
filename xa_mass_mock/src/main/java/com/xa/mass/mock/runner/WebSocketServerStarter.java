package com.xa.mass.mock.runner;

import com.xa.mass.core.getway.server.MassServerConfig;
import com.xa.mass.core.getway.server.MassServerBuilder;
import com.xa.mass.core.getway.queue.InMemoryMessageQueue;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.middleware.MessageMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("server")
public class WebSocketServerStarter implements CommandLineRunner {

    @Autowired
    ServerMessageDispatcher serverMessageDispatcher;

    @Autowired
    ServerSessionManager sessionManager;

    private static final Logger log = LoggerFactory.getLogger(WebSocketServerStarter.class);

    @Override
    public void run(String... args) throws Exception {
        // 1. 组装 middleware 链
        List<MessageMiddleware> inputMiddlewareList = new ArrayList<>();
        inputMiddlewareList.add(envelope -> {
            log.info("[InputMiddleware] Auth check for device: {}", envelope.getDeviceId());
            return true; // 通过
        });
        inputMiddlewareList.add(envelope -> {
            log.info("[InputMiddleware] Rate limit check for device: {}", envelope.getDeviceId());
            return true; // 通过
        });

        List<MessageMiddleware> outputMiddlewareList = new ArrayList<>();
        outputMiddlewareList.add(envelope -> {
            log.info("[OutputMiddleware] Logging for device: {}", envelope.getDeviceId());
            return true;
        });

        // 2. 构建队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();

        // 3. handler 示例
        MessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };


        // 5. builder 构建 server
        MassServerConfig server = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .withInputQueue(inputQueue)
                .withOutputQueue(outputQueue)
                .withHandler("whatsapp", MessageType.TASK, taskHandler)
                .withInputMiddlewareList(inputMiddlewareList)
                .withOutputMiddlewareList(outputMiddlewareList)
                .withSessionManager(sessionManager)
                .withDispatcher(serverMessageDispatcher)
                .build();

        log.info("Starting MassServer with builder...");
        server.start();
    }
}
