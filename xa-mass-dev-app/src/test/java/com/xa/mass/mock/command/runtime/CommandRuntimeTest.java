package com.xa.mass.mock.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.command.mock.MockClientStateRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandRuntimeTest {

    @BeforeAll
    static void setUpRuntime() {
        MockCommandRuntime.registerService(MockClientStateRegistry.class, new MockClientStateRegistry());
        MockCommandRuntime.registerService(ClientSessionManager.class, new ClientSessionManager());
    }

    @Test
    void shouldExecuteMockStateGetCommand() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "mock.state.get");
        request.addProperty("workerId", "worker-001");

        CommandResponse<?> response = MockCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("worker-001", data.get("workerId"));
        assertNotNull(data.get("state"));
    }

    @Test
    void shouldExecuteToolTimeNowCommand() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "tool.time.now");
        request.addProperty("zoneId", "Asia/Shanghai");

        CommandResponse<?> response = MockCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("Asia/Shanghai", data.get("zoneId"));
        assertEquals(false, data.get("simulated"));
        assertNotNull(data.get("iso8601"));
    }

    @Test
    void shouldPersistMockDelayStateForWorker() {
        JsonObject updateRequest = new JsonObject();
        updateRequest.addProperty("event", "mock.delay.response");
        updateRequest.addProperty("workerId", "worker-001");
        updateRequest.addProperty("millis", 250);

        CommandResponse<?> updateResponse = MockCommandRuntime.dispatch(updateRequest);

        assertTrue(updateResponse.isSuccess());

        JsonObject stateRequest = new JsonObject();
        stateRequest.addProperty("event", "mock.state.get");
        stateRequest.addProperty("workerId", "worker-001");

        CommandResponse<?> stateResponse = MockCommandRuntime.dispatch(stateRequest);

        assertTrue(stateResponse.isSuccess());
        assertTrue(stateResponse.getData() instanceof Map);
        Map<?, ?> stateData = (Map<?, ?>) stateResponse.getData();
        assertTrue(stateData.get("state") instanceof Map);
        assertEquals(250L, ((Map<?, ?>) stateData.get("state")).get("taskResponseDelayMillis"));
    }

    @Test
    void shouldExecuteBatchCommandWithContextExport() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "batch");
        request.addProperty("onError", "stop");

        JsonObject context = new JsonObject();
        context.addProperty("query", "Shanghai");
        context.addProperty("amount", 10);
        request.add("context", context);

        JsonObject geoParams = new JsonObject();
        geoParams.addProperty("query", "$ctx.query");
        JsonObject geoExport = new JsonObject();
        geoExport.addProperty("currency", "$result.currency");
        JsonObject geoStep = new JsonObject();
        geoStep.addProperty("id", "geo");
        geoStep.addProperty("event", "tool.geo.lookup");
        geoStep.add("params", geoParams);
        geoStep.add("export", geoExport);

        JsonObject quoteParams = new JsonObject();
        quoteParams.addProperty("base", "USD");
        quoteParams.addProperty("target", "$ctx.currency");
        quoteParams.addProperty("amount", "$ctx.amount");
        JsonObject quoteStep = new JsonObject();
        quoteStep.addProperty("id", "quote");
        quoteStep.addProperty("event", "tool.currency.quote");
        quoteStep.add("params", quoteParams);

        com.google.gson.JsonArray events = new com.google.gson.JsonArray();
        events.add(geoStep);
        events.add(quoteStep);
        request.add("events", events);

        CommandResponse<?> response = MockCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertTrue(data.get("context") instanceof Map);
        assertEquals("CNY", ((Map<?, ?>) data.get("context")).get("currency"));
    }
}
