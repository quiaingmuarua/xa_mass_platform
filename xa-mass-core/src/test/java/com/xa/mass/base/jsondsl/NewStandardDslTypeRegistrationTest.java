package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.JsonDslProcessorEngine;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewStandardDslTypeRegistrationTest {

    @Test
    void processesGenerateDslWithoutManualTypeRegistration() {
        JsonDslDefinition definition = new JsonDslDefinition("test_worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate workers without type registry setup");
        definition.setAuthor("test");

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Worker", 2);
        context.setScopeName("Worker");
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$JOIN", Arrays.asList("test-worker-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("workerGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb")));
        definition.setFieldDsl(fieldDsl);
        definition.validate();

        List<Worker> workers = JsonDslProcessorEngine.process(definition, new ProcessingContext("test"), Worker.class);

        assertNotNull(workers);
        assertEquals(2, workers.size());
        for (Worker worker : workers) {
            assertNotNull(worker.getWorkerId());
            assertTrue(worker.getWorkerId().startsWith("test-worker-"));
            assertNotNull(worker.getStatus());
            assertNotNull(worker.getWorkerGroupId());
            assertTrue(Arrays.asList("us", "gb").contains(worker.getWorkerGroupId()));
        }
    }

    @Test
    void processesGenerateDslWithConcreteFields() {
        JsonDslDefinition definition = new JsonDslDefinition("complex_worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate one concrete worker");
        definition.setAuthor("test");

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Worker", 1);
        context.setScopeName("Worker");
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", "complex-worker-001");
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("workerGroupId", "us");
        fieldDsl.put("agentVersion", "2.0.1");
        fieldDsl.put("onlineStrategy", "100");
        definition.setFieldDsl(fieldDsl);
        definition.validate();

        List<Worker> workers = JsonDslProcessorEngine.process(definition, new ProcessingContext("test"), Worker.class);

        assertNotNull(workers);
        assertEquals(1, workers.size());
        Worker worker = workers.get(0);
        assertEquals("complex-worker-001", worker.getWorkerId());
        assertEquals("ONLINE", worker.getStatus().name());
        assertEquals("us", worker.getWorkerGroupId());
        assertEquals("2.0.1", worker.getAgentVersion());
        assertEquals("100", worker.getOnlineStrategy());
    }

    @Test
    void parsesAndProcessesJsonDslWithoutRegistration() {
        String jsonDsl = """
                {
                  "unique_id": "json_test_generator",
                  "type": "generate",
                  "priority": 1,
                  "desc": "JSON generate example",
                  "version": "1.0",
                  "author": "test",
                  "tags": ["json", "test"],
                  "context": {
                    "MODEL": "com.xa.mass.base.model.Worker",
                    "COUNT": 1,
                    "scope_name": "Worker",
                    "debug": true
                  },
                  "fieldDsl": {
                    "workerId": "json-worker-001",
                    "status": "ONLINE",
                    "workerGroupId": "gb"
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

        assertEquals("json_test_generator", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.GENERATE, definition.getType());
        assertEquals("com.xa.mass.base.model.Worker", definition.getContext().getModel());

        List<Worker> workers = JsonDslProcessorEngine.process(definition, new ProcessingContext("test"), Worker.class);

        assertNotNull(workers);
        assertEquals(1, workers.size());
        Worker worker = workers.get(0);
        assertEquals("json-worker-001", worker.getWorkerId());
        assertEquals("ONLINE", worker.getStatus().name());
        assertEquals("gb", worker.getWorkerGroupId());
    }
}
