package com.xa.mass.mock.runner;

import com.xa.mass.core.getway.server.MassServerConfig;
import com.xa.mass.core.getway.server.MassServerBuilder;
import com.xa.mass.core.getway.queue.InMemoryMessageQueue;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.ExceptionMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import com.xa.mass.core.getway.server.MassServerStater;
import com.xa.mass.core.getway.dispatcher.BasicMessageHandlerRegister;
import com.xa.mass.core.getway.exception.ValidationException;
import com.xa.mass.core.getway.exception.CommandException;
import com.xa.mass.core.getway.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("server")
public class WebSocketServerStarter implements CommandLineRunner {

    ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
    private static final Logger log = LoggerFactory.getLogger(WebSocketServerStarter.class);

    @Override
    public void run(String... args) throws Exception {
        // 注册基础 handler
        BasicMessageHandlerRegister.registerBasicHandlers();

        // 1. 组装 middleware 链
        List<EnvelopeMiddleware> inputMiddlewareList = new ArrayList<>();
        inputMiddlewareList.add((envelope, context) -> {
            log.info("[InputMiddleware] Auth check for device: {}", envelope.getDeviceId());
            return true;
        });
        inputMiddlewareList.add((envelope, context) -> {
            log.info("[InputMiddleware] Rate limit check for device: {}", envelope.getDeviceId());
            return true;
        });

        List<EnvelopeMiddleware> outputMiddlewareList = new ArrayList<>();
        outputMiddlewareList.add((envelope, context) -> {
            log.info("[OutputMiddleware] Logging for device: {}", envelope.getDeviceId());
            return true;
        });

        List<ExceptionMiddleware> exceptionMiddlewareList = new ArrayList<>();
        exceptionMiddlewareList.add((envelope, context, ex) -> {
            if (ex instanceof ValidationException) {
                log.warn("[ExceptionMiddleware] Validation failed: {}", ex.getMessage());
                return false;
            } else if (ex instanceof CommandException) {
                CommandException ce = (CommandException) ex;
                ErrorCode code = ce.getErrorCode();
                log.warn("[CommandException] code={}, msg={}", code.code, ce.getMessage());
                return false;
            } else {
                log.error("[ExceptionMiddleware] System error: ", ex);
                return false;
            }
        });

        // 2. 构建队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();

        // 3. handler 示例
        MessageHandler taskHandler = msg -> {
            log.info("[Handler] Handling TASK message: {}", msg);
            return new ArrayList<>();
        };

        // 4. DispatcherContext
        DispatcherContext dispatcherContext = new DispatcherContext(
                inputQueue,
                outputQueue,
                sessionManager,
                new com.google.gson.Gson()
        );

        // 5. dispatcher
        ServerMessageDispatcher dispatcher = new ServerMessageDispatcher(
                inputMiddlewareList,
                outputMiddlewareList,
                exceptionMiddlewareList,
                dispatcherContext
        );

        // 6. builder 构建 server
        MassServerConfig server = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .withInputQueue(inputQueue)
                .withOutputQueue(outputQueue)
                .withHandler("whatsapp", MessageType.TASK, taskHandler)
                .withSessionManager(sessionManager)
                .build();

        log.info("Starting MassServer with builder...");
        MassServerStater stater = new MassServerStater(server);
        stater.start();
    }
}
