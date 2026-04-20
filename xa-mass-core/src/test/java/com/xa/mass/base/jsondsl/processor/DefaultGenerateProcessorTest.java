package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGenerateProcessorTest {

    private final DefaultGenerateProcessor processor = new DefaultGenerateProcessor();

    @Test
    void shouldExposeContextParametersRuntimeVariablesAndDefaultIndex() {
        JsonDslContext dslContext = new JsonDslContext("java.util.LinkedHashMap", 2);
        dslContext.setParameters(Map.of("greeting", "hello"));

        JsonDslDefinition definition = new JsonDslDefinition("generate-map", JsonDslDefinition.DslType.GENERATE);
        definition.setContext(dslContext);
        Map<String, Object> indexRule = new HashMap<>();
        indexRule.put("$CONTEXT", null);
        definition.setFieldDsl(Map.of(
                "index", indexRule,
                "greeting", Map.of("$CONTEXT", "greeting"),
                "runtimeName", Map.of("$CONTEXT", "runtimeName")
        ));

        ProcessingContext runtimeContext = new ProcessingContext("test");
        runtimeContext.setVariable("runtimeName", "worker-1");

        List<Map> result = processor.generate(definition, runtimeContext, Map.class);

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).get("index"));
        assertEquals(1, result.get(1).get("index"));
        assertEquals("hello", result.get(0).get("greeting"));
        assertEquals("worker-1", result.get(0).get("runtimeName"));
    }

    @Test
    void shouldAllowEmptyFieldDslWhenGeneratingMaps() {
        JsonDslDefinition definition = new JsonDslDefinition("generate-empty-map", JsonDslDefinition.DslType.GENERATE);
        definition.setContext(new JsonDslContext("java.util.LinkedHashMap", 1));

        List<Map> result = processor.generate(definition, new ProcessingContext("test"), Map.class);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isEmpty());
    }
}
