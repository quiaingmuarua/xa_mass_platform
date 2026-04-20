package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultTransformProcessorTest {

    private final DefaultTransformProcessor processor = new DefaultTransformProcessor();

    @Test
    void shouldTransformBeanWithoutMutatingOriginalInput() {
        TestBean original = new TestBean();
        original.setName("John");
        original.setAge(25);
        original.setStatus("active");

        JsonDslDefinition definition = new JsonDslDefinition("transform-bean", JsonDslDefinition.DslType.TRANSFORM);
        definition.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "'Mr. ' + name"),
                "age", Map.of("$EXPR", "age + 1")
        ));

        TestBean transformed = processor.transform(original, definition, new ProcessingContext("test"));

        assertNotSame(original, transformed);
        assertEquals("John", original.getName());
        assertEquals(25, original.getAge());
        assertEquals("Mr. John", transformed.getName());
        assertEquals(26, transformed.getAge());
        assertEquals("active", transformed.getStatus());
    }

    @Test
    void shouldTransformMapAndExposeContextVariables() {
        JsonDslDefinition definition = new JsonDslDefinition("transform-map", JsonDslDefinition.DslType.TRANSFORM);
        definition.setFieldDsl(Map.of(
                "status", Map.of("$CONTEXT", "targetStatus"),
                "name", Map.of("$EXPR", "name + '-' + suffix")
        ));

        ProcessingContext context = new ProcessingContext("test");
        context.setVariable("targetStatus", "READY");
        context.setParameter("suffix", "worker");

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "task");
        original.put("status", "PENDING");

        Map<String, Object> transformed = processor.transform(original, definition, context);

        assertEquals(Map.of("name", "task", "status", "PENDING"), original);
        assertEquals("task-worker", transformed.get("name"));
        assertEquals("READY", transformed.get("status"));
    }

    @Test
    void shouldFailFastWhenStrictModeHasUnresolvedVariable() {
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setStrict(true);

        JsonDslDefinition definition = new JsonDslDefinition("strict-transform", JsonDslDefinition.DslType.TRANSFORM);
        definition.setContext(dslContext);
        definition.setFieldDsl(Map.of("name", "&missing"));

        TestBean original = new TestBean();
        original.setName("John");

        JsonDslException exception = assertThrows(JsonDslException.class,
                () -> processor.transform(original, definition, new ProcessingContext("test")));

        assertEquals("Unresolved variable: &missing", exception.getMessage());
    }

    static class TestBean {
        private String name;
        private Integer age;
        private String status;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
