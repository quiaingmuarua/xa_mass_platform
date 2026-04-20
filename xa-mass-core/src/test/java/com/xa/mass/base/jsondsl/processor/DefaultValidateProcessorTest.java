package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultValidateProcessorTest {

    private final DefaultValidateProcessor processor = new DefaultValidateProcessor();

    @Test
    void shouldReturnNoErrorsForValidInput() {
        JsonDslDefinition definition = new JsonDslDefinition("validate-user", JsonDslDefinition.DslType.VALIDATE);
        definition.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "name != null && name.length() > 0"),
                "age", Map.of("$EXPR", "age >= 18")
        ));
        definition.setCombineDsl(Map.of(
                "adult-ready", Map.of("$EXPR", "age >= 18 && 'READY'.equals(status)")
        ));

        TestBean input = new TestBean("Alice", 22, "READY");

        List<String> errors = processor.validate(input, definition, new ProcessingContext("test"));

        assertTrue(errors.isEmpty());
    }

    @Test
    void shouldCollectFieldAndCombineErrorsForInvalidInput() {
        JsonDslDefinition definition = new JsonDslDefinition("validate-user", JsonDslDefinition.DslType.VALIDATE);
        definition.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "name != null && name.length() > 0"),
                "age", Map.of("$EXPR", "age >= 18")
        ));
        definition.setCombineDsl(Map.of(
                "adult-ready", Map.of("$EXPR", "age >= 18 && 'READY'.equals(status)")
        ));

        TestBean input = new TestBean("", 16, "PENDING");

        List<String> errors = processor.validate(input, definition, new ProcessingContext("test"));

        assertEquals(3, errors.size());
        assertTrue(errors.stream().anyMatch(error -> error.contains("name validation failed")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("age validation failed")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("combine rule 'adult-ready' failed")));
    }

    @Test
    void shouldReportStrictModeErrorsForMissingVariables() {
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setStrict(true);

        JsonDslDefinition definition = new JsonDslDefinition("strict-validate", JsonDslDefinition.DslType.VALIDATE);
        definition.setContext(dslContext);
        definition.setFieldDsl(Map.of("name", "&missing"));

        TestBean input = new TestBean("Alice", 22, "READY");

        List<String> errors = processor.validate(input, definition, new ProcessingContext("test"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("name validation error: Unresolved variable: &missing"));
    }

    static class TestBean {
        private String name;
        private Integer age;
        private String status;

        TestBean(String name, Integer age, String status) {
            this.name = name;
            this.age = age;
            this.status = status;
        }

        public String getName() {
            return name;
        }

        public Integer getAge() {
            return age;
        }

        public String getStatus() {
            return status;
        }
    }
}
