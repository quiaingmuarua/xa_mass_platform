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
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import com.xa.mass.core.getway.server.MassServerStater;
import com.xa.mass.core.getway.dispatcher.BasicMessageHandlerRegister;
import com.xa.mass.core.getway.exception.ValidationException;
import com.xa.mass.core.getway.exception.CommandException;
import com.xa.mass.core.getway.exception.ErrorCode;
import com.xa.mass.core.getway.middleware.LegacyBusinessMiddleware;

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

        // 4. builder 构建 server，自动注册默认中间件
        MassServerConfig server = MassServerBuilder.create()
                .withPort(18088)
                .withWebSocketPath("/ws")
                .withInputQueue(inputQueue)
                .withOutputQueue(outputQueue)
                .registerHandler("whatsapp", MessageType.TASK, taskHandler)
                .withSessionManager(sessionManager)
                .withDefaultMiddlewares(true)
                // 如需移除默认认证中间件：.removeInputMiddleware(10)
                // 如需添加自定义中间件：.registerInputMiddleware(30, customBizMiddleware)
                .build();

        log.info(server.describe());
        log.info("Starting MassServer with builder...");
        MassServerStater stater = new MassServerStater(server);
        stater.start();
    }
}
