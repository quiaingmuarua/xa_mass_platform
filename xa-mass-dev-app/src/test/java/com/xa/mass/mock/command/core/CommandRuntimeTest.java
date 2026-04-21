package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.ApiResponse;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRuntimeTest {

    @Test
    void shouldExecuteClientInfoCommand() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "client.info");
        request.addProperty("workerId", "worker-001");

        ApiResponse<?> response = MockCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("worker-001", data.get("workerId"));
        assertNotNull(data.get("supportedEvents"));
    }

    @Test
    void shouldExecuteBatchCommandWithContextExport() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "batch");
        request.addProperty("onError", "stop");

        JsonObject context = new JsonObject();
        context.addProperty("text", "hello");
        request.add("context", context);

        JsonObject echoParams = new JsonObject();
        echoParams.addProperty("text", "$ctx.text");
        JsonObject echoExport = new JsonObject();
        echoExport.addProperty("echoText", "$result.text");
        JsonObject echoStep = new JsonObject();
        echoStep.addProperty("id", "echo");
        echoStep.addProperty("event", "client.echo");
        echoStep.add("params", echoParams);
        echoStep.add("export", echoExport);

        JsonObject infoParams = new JsonObject();
        infoParams.addProperty("workerId", "worker-002");
        JsonObject infoStep = new JsonObject();
        infoStep.addProperty("id", "info");
        infoStep.addProperty("event", "client.info");
        infoStep.add("params", infoParams);

        com.google.gson.JsonArray events = new com.google.gson.JsonArray();
        events.add(echoStep);
        events.add(infoStep);
        request.add("events", events);

        ApiResponse<?> response = MockCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertTrue(data.get("context") instanceof Map);
        assertEquals("hello", ((Map<?, ?>) data.get("context")).get("echoText"));
    }
}
