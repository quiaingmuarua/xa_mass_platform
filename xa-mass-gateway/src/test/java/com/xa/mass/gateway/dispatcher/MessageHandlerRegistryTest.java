package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.handler.ResolutionResult;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class MessageHandlerRegistryTest {

    private MessageHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MessageHandlerRegistry();
    }

    // ---- register + resolve ----

    @Test
    void resolveRegisteredProjectHandler() {
        MassMessageHandler handler = msg -> Collections.emptyList();
        registry.register("myApp", MessageType.TASK, "sub1", handler);

        ResolutionResult result = registry.resolve("myApp", MessageType.TASK, "sub1");

        assertTrue(result.isFound());
        assertSame(handler, result.getHandler());
    }

    @Test
    void projectHandlerTakesPriorityOverGlobal() {
        MassMessageHandler globalHandler = msg -> Collections.emptyList();
        MassMessageHandler projectHandler = msg -> Collections.emptyList();
        registry.register(null, MessageType.TASK, "", globalHandler);      // global
        registry.register("proj", MessageType.TASK, "", projectHandler);   // project

        ResolutionResult result = registry.resolve("proj", MessageType.TASK, "");
        assertTrue(result.isFound());
        assertSame(projectHandler, result.getHandler());
    }

    @Test
    void fallsBackToGlobalHandlerWhenNoProjectMatch() {
        MassMessageHandler globalHandler = msg -> Collections.emptyList();
        registry.register(null, MessageType.PING, "heartbeat", globalHandler);

        ResolutionResult result = registry.resolve("anyProject", MessageType.PING, "heartbeat");

        assertTrue(result.isFound());
        assertSame(globalHandler, result.getHandler());
    }

    @Test
    void fallbackResultWhenNoHandlerAndFallbackEnabled() {
        registry.setEnableFallback(true);

        ResolutionResult result = registry.resolve("x", MessageType.STATUS, "unknownSub");

        assertTrue(result.isFallback());
        assertFalse(result.isFound());
    }

    @Test
    void notFoundResultWhenNoHandlerAndFallbackDisabled() {
        registry.setEnableFallback(false);

        ResolutionResult result = registry.resolve("x", MessageType.STATUS, "unknownSub");

        assertFalse(result.isFound());
        assertFalse(result.isFallback());
    }

    // ---- autoRegister built-in handlers ----

    @Test
    void autoRegisteredPingHandlerReturnsPong() {
        registry.autoRegister();
        MassMessage ping = massMessage("GLOBAL", MessageType.PING, "heartbeat");

        ResolutionResult result = registry.resolve(ping);
        assertTrue(result.isFound());

        var responses = result.getHandler().handle(ping);
        assertEquals(1, responses.size());
        assertEquals(MessageType.PONG, responses.get(0).getMsgType());
    }

    @Test
    void autoRegisteredPongHandlerReturnsEmpty() {
        registry.autoRegister();
        MassMessage pong = massMessage("GLOBAL", MessageType.PONG, "heartbeat");

        ResolutionResult result = registry.resolve(pong);
        assertTrue(result.isFound());
        assertTrue(result.getHandler().handle(pong).isEmpty());
    }

    // ---- resolve via MassMessage ----

    @Test
    void resolveViaMassMessageDelegates() {
        registry.autoRegister();
        MassMessage msg = massMessage("GLOBAL", MessageType.TASK, "");

        ResolutionResult result = registry.resolve(msg);
        assertTrue(result.isFound());
    }

    // ---- helpers ----

    private MassMessage massMessage(String project, MessageType type, String subType) {
        MassMessage msg = new MassMessage();
        msg.setProject(project);
        msg.setMsgType(type);
        msg.setSubMsgType(subType);
        MessageContext ctx = new MessageContext();
        ctx.setWorkerId("worker-1");
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        msg.setContext(ctx);
        return msg;
    }
}
