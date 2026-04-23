package com.xa.mass.command.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.command.runtime.CommandLogger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandKernelTest {

    @BeforeAll
    static void setUp() {
        CommandContext.init(
                () -> CommandKernelTest.class.getClassLoader(),
                CommandKernelTest.class.getClassLoader(),
                new CommandLogger() {
                    @Override
                    public void info(String message) {
                    }

                    @Override
                    public void error(String message) {
                    }

                    @Override
                    public void error(String message, Throwable throwable) {
                    }
                },
                (response, targetApp, request) -> {
                }
        );
        CoreCommandRoutes.registerCommonRoutes();
    }

    @Test
    void commandListExposesCoreRoutes() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "command.list");

        CommandResponse<?> response = CommandDispatcher.dispatch(request);

        assertTrue(response.isSuccess());
        Map<?, ?> data = assertInstanceOf(Map.class, response.getData());
        assertTrue(((Number) data.get("count")).intValue() >= 2);
        List<?> events = assertInstanceOf(List.class, data.get("events"));
        assertTrue(events.stream()
                .filter(CommandDefinition.Descriptor.class::isInstance)
                .map(CommandDefinition.Descriptor.class::cast)
                .anyMatch(item -> "batch".equals(item.getEvent())));
    }

    @Test
    void batchRouteCanExportContextAcrossSteps() {
        String echoEvent = "test.echo." + System.nanoTime();
        if (!CommandRegistry.contains(echoEvent)) {
            CommandRegistry.register(CommandDefinition.<JsonObject, Map<String, Object>>builder(echoEvent)
                    .handler((request, context) -> Map.of("echo", request.get("input").getAsString()))
                    .resolver(json -> json)
                    .summary("Echo test helper")
                    .build());
        }

        JsonObject request = new JsonObject();
        request.addProperty("event", "batch");

        JsonObject context = new JsonObject();
        context.addProperty("seed", "alpha");
        request.add("context", context);

        JsonObject step1Params = new JsonObject();
        step1Params.addProperty("input", "$ctx.seed");
        JsonObject step1Export = new JsonObject();
        step1Export.addProperty("copied", "$result.echo");
        JsonObject step1 = new JsonObject();
        step1.addProperty("id", "step-1");
        step1.addProperty("event", echoEvent);
        step1.add("params", step1Params);
        step1.add("export", step1Export);

        JsonObject step2Params = new JsonObject();
        step2Params.addProperty("input", "$ctx.copied");
        JsonObject step2 = new JsonObject();
        step2.addProperty("id", "step-2");
        step2.addProperty("event", echoEvent);
        step2.add("params", step2Params);

        JsonArray events = new JsonArray();
        events.add(step1);
        events.add(step2);
        request.add("events", events);

        CommandResponse<?> response = CommandDispatcher.dispatch(request);

        assertTrue(response.isSuccess());
        Map<?, ?> data = assertInstanceOf(Map.class, response.getData());
        Map<?, ?> sharedContext = assertInstanceOf(Map.class, data.get("context"));
        assertEquals("alpha", sharedContext.get("copied"));
        List<?> results = assertInstanceOf(List.class, data.get("results"));
        Map<?, ?> secondStep = assertInstanceOf(Map.class, results.get(1));
        Map<?, ?> secondData = assertInstanceOf(Map.class, secondStep.get("data"));
        assertEquals("alpha", secondData.get("echo"));
        assertNotNull(secondStep.get("duration"));
    }
}
