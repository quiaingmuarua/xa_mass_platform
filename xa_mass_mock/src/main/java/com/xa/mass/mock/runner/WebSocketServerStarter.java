package com.xa.mass.mock.runner;

import com.xa.mass.core.server.MassServer;
import com.xa.mass.core.server.MassServerBuilder;
import com.xa.mass.core.queue.InMemoryMessageQueue;
import com.xa.mass.core.queue.Envelope;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.middleware.MessageMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.dispatcher.MessageHandler;
import com.xa.mass.core.session.ServerSessionManager;
import com.xa.mass.core.dispatcher.ServerMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("server")
public class WebSocketServerStarter implements CommandLineRunner {
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

        // 4. sessionManager/dispatcher 示例（可用真实实现替换）
        ServerSessionManager sessionManager = new ServerSessionManager();
        ServerMessageDispatcher dispatcher = new ServerMessageDispatcher();

        // 5. builder 构建 server
        MassServer server = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .withInputQueue(inputQueue)
                .withOutputQueue(outputQueue)
                .withHandler("whatsapp", MessageType.TASK, taskHandler)
                .withInputMiddlewareList(inputMiddlewareList)
                .withOutputMiddlewareList(outputMiddlewareList)
                .withSessionManager(sessionManager)
                .withDispatcher(dispatcher)
                .build();

        log.info("Starting MassServer with builder...");
        server.start();
    }
}
