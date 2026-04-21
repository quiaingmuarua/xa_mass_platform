package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDslProcessorEngineTest {

    private ProcessingContext context;
    private JsonDslDefinition generateDefinition;

    @BeforeEach
    void setUp() {
        ProcessorRegistry.clear();
        context = new ProcessingContext("test-context");

        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);

        generateDefinition = new JsonDslDefinition("test-generate", JsonDslDefinition.DslType.GENERATE);
        generateDefinition.setContext(dslContext);
    }

    @AfterEach
    void tearDown() {
        ProcessorRegistry.clear();
    }

    @Test
    void shouldUseRegisteredGenerateProcessor() {
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());

        List<Map> result = JsonDslProcessorEngine.process(generateDefinition, context, Map.class);

        assertEquals(1, result.size());
        assertEquals("TestGenerateProcessor processed: test-generate", result.get(0).get("message"));
    }

    @Test
    void shouldParseSnakeCaseJsonAliases() {
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());

        String jsonDsl = """
                {
                  "unique_id": "json-snake-case-dsl",
                  "type": "generate",
                  "description": "Test DSL from snake_case JSON",
                  "context": {
                    "model": "java.util.HashMap",
                    "count": 1,
                    "scope_name": "Root"
                  }
                }
                """;

        List<Map> result = JsonDslProcessorEngine.processFromJson(jsonDsl, context, Map.class);

        assertEquals(1, result.size());
        assertEquals("TestGenerateProcessor processed: json-snake-case-dsl", result.get(0).get("message"));
    }

    @Test
    void shouldProcessFilterFromContextInputList() {
        JsonDslDefinition filterDefinition = new JsonDslDefinition("filter-adult", JsonDslDefinition.DslType.FILTER);
        filterDefinition.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        context.setParameter("input", List.of(
                Map.of("name", "Alice", "age", 20),
                Map.of("name", "Bob", "age", 16)
        ));

        List<Map> result = JsonDslProcessorEngine.process(filterDefinition, context, Map.class);

        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).get("name"));
    }

    @Test
    void shouldProcessTransformFromContextInputObject() {
        JsonDslDefinition transformDefinition = new JsonDslDefinition("transform-user", JsonDslDefinition.DslType.TRANSFORM);
        transformDefinition.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "'Mr. ' + name"),
                "age", Map.of("$EXPR", "age + 1")
        ));

        context.setParameter("input", new LinkedHashMap<>(Map.of("name", "John", "age", 25)));

        List<Map> result = JsonDslProcessorEngine.process(transformDefinition, context, Map.class);

        assertEquals(1, result.size());
        assertEquals("Mr. John", result.get(0).get("name"));
        assertEquals(26, result.get(0).get("age"));
    }

    @Test
    void shouldThrowValidationErrorsFromProcess() {
        JsonDslDefinition validateDefinition = new JsonDslDefinition("validate-user", JsonDslDefinition.DslType.VALIDATE);
        validateDefinition.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        context.setParameter("input", Map.of("name", "Bob", "age", 16));

        JsonDslException exception = assertThrows(JsonDslException.class,
                () -> JsonDslProcessorEngine.process(validateDefinition, context, Map.class));

        assertTrue(exception.getMessage().contains("validation failed"));
        assertTrue(exception.getMessage().contains("age validation failed"));
    }

    @Test
    void shouldProcessFullLifecycleChain() {
        JsonDslProcessorEngine.registerProcessor(new StableGenerateProcessor());

        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-stable", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setContext(new JsonDslContext("java.util.HashMap", 3));

        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-ready", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-ready", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "'user-' + age"),
                "status", "READY"
        ));

        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-ready", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "name != null && name.length() > 0"),
                "status", Map.of("$EXPR", "'READY'.equals(status)")
        ));

        List<Map> result = JsonDslProcessorEngine.processChain(
                List.of(generateDsl, filterDsl, transformDsl, validateDsl),
                context,
                Map.class
        );

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(item -> "READY".equals(item.get("status"))));
        assertTrue(result.stream().allMatch(item -> String.valueOf(item.get("name")).startsWith("user-")));
    }

    @Test
    void shouldAllowEmptyIntermediateResultsInChain() {
        JsonDslProcessorEngine.registerProcessor(new StableGenerateProcessor());

        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-stable", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setContext(new JsonDslContext("java.util.HashMap", 3));

        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-none", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 100")));

        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-none", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setFieldDsl(Map.of("status", "READY"));

        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-none", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setFieldDsl(Map.of("status", Map.of("$EXPR", "'READY'.equals(status)")));

        List<Map> result = JsonDslProcessorEngine.processChain(
                List.of(generateDsl, filterDsl, transformDsl, validateDsl),
                context,
                Map.class
        );

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyListForEmptyChain() {
        List<Map> result = JsonDslProcessorEngine.processChain(List.of(), context, Map.class);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldPreserveDetailedFilterFailuresFromEngineBatchApi() {
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-ready", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));
        filterDsl.setCombineDsl(Map.of("ready-only", Map.of("$EXPR", "'READY'.equals(status)")));

        List<Map> input = List.of(
                new LinkedHashMap<>(Map.of("name", "Alice", "age", 16, "status", "READY")),
                new LinkedHashMap<>(Map.of("name", "Bob", "age", 20, "status", "PENDING"))
        );

        FilterResult<Map> result = JsonDslProcessorEngine.filterBatchWithDetails(input, filterDsl, context, Map.class);

        assertEquals(0, result.getPassedCount());
        assertEquals(2, result.getFailedCount());
        assertTrue(result.getFailed().stream().anyMatch(failure -> "Alice".equals(failure.getData().get("name"))
                && failure.getReasons().stream().anyMatch(reason -> reason.contains("field 'age' failed"))));
        assertTrue(result.getFailed().stream().anyMatch(failure -> "Bob".equals(failure.getData().get("name"))
                && failure.getReasons().stream().anyMatch(reason -> reason.contains("combine rule 'ready-only' failed"))));
    }

    @Test
    void shouldThrowWhenFilterInputIsMissing() {
        JsonDslDefinition filterDefinition = new JsonDslDefinition("filter-adult", JsonDslDefinition.DslType.FILTER);
        filterDefinition.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        JsonDslException exception = assertThrows(JsonDslException.class,
                () -> JsonDslProcessorEngine.process(filterDefinition, context, Map.class));

        assertEquals("input parameter in context cannot be null", exception.getMessage());
    }

    @Test
    void shouldExposeRegisteredAndDefaultProcessors() {
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());

        List<JsonDslProcessor> processors = JsonDslProcessorEngine.getAllProcessors();

        assertFalse(processors.isEmpty());
        assertTrue(processors.stream().anyMatch(processor -> "TestGenerateProcessor".equals(processor.getName())));
        assertTrue(processors.stream().anyMatch(processor -> "DefaultFilterProcessor".equals(processor.getName())));
    }

    @Test
    void shouldRejectInvalidJson() {
        assertThrows(Exception.class, () -> JsonDslProcessorEngine.processFromJson("{ invalid json }", context, Map.class));
    }

    @Test
    void shouldRejectNullArguments() {
        assertThrows(JsonDslException.class, () -> JsonDslProcessorEngine.process(null, context, Map.class));
        assertThrows(JsonDslException.class, () -> JsonDslProcessorEngine.process(generateDefinition, null, Map.class));
        assertThrows(JsonDslException.class, () -> JsonDslProcessorEngine.processChain(null, context, Map.class));
    }

    private static class TestGenerateProcessor implements GenerateProcessor {

        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "TestGenerateProcessor processed: " + definition.getUniqueId());

            @SuppressWarnings("unchecked")
            List<T> typedResult = (List<T>) List.of(result);
            return typedResult;
        }

        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }

        @Override
        public String getName() {
            return "TestGenerateProcessor";
        }

        @Override
        public int getPriority() {
            return 500;
        }
    }

    private static class StableGenerateProcessor implements GenerateProcessor {

        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            List<Map<String, Object>> generated = List.of(
                    new LinkedHashMap<>(Map.of("name", "raw-17", "age", 17, "status", "RAW")),
                    new LinkedHashMap<>(Map.of("name", "raw-18", "age", 18, "status", "RAW")),
                    new LinkedHashMap<>(Map.of("name", "raw-25", "age", 25, "status", "RAW"))
            );

            @SuppressWarnings("unchecked")
            List<T> typedResult = (List<T>) generated;
            return typedResult;
        }

        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }

        @Override
        public String getName() {
            return "StableGenerateProcessor";
        }

        @Override
        public int getPriority() {
            return 500;
        }
    }
}
