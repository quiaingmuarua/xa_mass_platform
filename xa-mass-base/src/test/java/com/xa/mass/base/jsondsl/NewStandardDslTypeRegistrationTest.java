package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.JsonDslProcessorEngine;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NewStandardDslTypeRegistrationTest {

    @Test
    void processesGenerateDslWithoutManualTypeRegistration() {
        JsonDslDefinition definition = new JsonDslDefinition("test_worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate workers without type registry setup");
        definition.setAuthor("test");

        JsonDslContext context = new JsonDslContext(WorkerFixture.class.getName(), 2);
        context.setScopeName("Worker");
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$JOIN", Arrays.asList("test-worker-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("workerGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb")));
        definition.setFieldDsl(fieldDsl);
        definition.validate();

        List<WorkerFixture> workers = JsonDslProcessorEngine.process(
                definition,
                new ProcessingContext("test"),
                WorkerFixture.class
        );

        assertNotNull(workers);
        assertEquals(2, workers.size());
        for (WorkerFixture worker : workers) {
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

        JsonDslContext context = new JsonDslContext(WorkerFixture.class.getName(), 1);
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

        List<WorkerFixture> workers = JsonDslProcessorEngine.process(
                definition,
                new ProcessingContext("test"),
                WorkerFixture.class
        );

        assertNotNull(workers);
        assertEquals(1, workers.size());
        WorkerFixture worker = workers.get(0);
        assertEquals("complex-worker-001", worker.getWorkerId());
        assertEquals("ONLINE", worker.getStatus());
        assertEquals("us", worker.getWorkerGroupId());
        assertEquals("2.0.1", worker.getAgentVersion());
        assertEquals("100", worker.getOnlineStrategy());
    }

    @Test
    void parsesAndProcessesJsonDslWithoutRegistration() {
        String jsonDsl = """
                {
                  "uniqueId": "json_test_generator",
                  "type": "generate",
                  "priority": 1,
                  "description": "JSON generate example",
                  "version": "1.0",
                  "author": "test",
                  "tags": ["json", "test"],
                  "context": {
                    "model": "%s",
                    "count": 1,
                    "scopeName": "Worker"
                  },
                  "fieldDsl": {
                    "workerId": "json-worker-001",
                    "status": "ONLINE",
                    "workerGroupId": "gb"
                  }
                }
                """.formatted(WorkerFixture.class.getName());

        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

        assertEquals("json_test_generator", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.GENERATE, definition.getType());
        assertEquals(WorkerFixture.class.getName(), definition.getContext().getModel());

        List<WorkerFixture> workers = JsonDslProcessorEngine.process(
                definition,
                new ProcessingContext("test"),
                WorkerFixture.class
        );

        assertNotNull(workers);
        assertEquals(1, workers.size());
        WorkerFixture worker = workers.get(0);
        assertEquals("json-worker-001", worker.getWorkerId());
        assertEquals("ONLINE", worker.getStatus());
        assertEquals("gb", worker.getWorkerGroupId());
    }

    public static class WorkerFixture {
        private String workerId;
        private String status;
        private String workerGroupId;
        private String agentVersion;
        private String onlineStrategy;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getWorkerGroupId() {
            return workerGroupId;
        }

        public void setWorkerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
        }

        public String getAgentVersion() {
            return agentVersion;
        }

        public void setAgentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
        }

        public String getOnlineStrategy() {
            return onlineStrategy;
        }

        public void setOnlineStrategy(String onlineStrategy) {
            this.onlineStrategy = onlineStrategy;
        }
    }
}
