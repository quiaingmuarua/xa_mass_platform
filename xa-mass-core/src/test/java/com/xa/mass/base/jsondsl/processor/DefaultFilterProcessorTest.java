package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFilterProcessorTest {

    private final DefaultFilterProcessor processor = new DefaultFilterProcessor();

    @Test
    void shouldFilterSingleBeanAndReturnFailureReasons() {
        JsonDslDefinition definition = new JsonDslDefinition("filter-user", JsonDslDefinition.DslType.FILTER);
        definition.setFieldDsl(Map.of(
                "age", Map.of("$EXPR", "age >= 18"),
                "status", Map.of("$EXPR", "'READY'.equals(status)")
        ));

        TestBean passed = new TestBean("Alice", 20, "READY");
        TestBean failed = new TestBean("Bob", 16, "PENDING");

        FilterResult<TestBean> passedResult = processor.filter(passed, definition, new ProcessingContext("test"));
        FilterResult<TestBean> failedResult = processor.filter(failed, definition, new ProcessingContext("test"));

        assertEquals(1, passedResult.getPassedCount());
        assertEquals(0, passedResult.getFailedCount());
        assertEquals(0, failedResult.getPassedCount());
        assertEquals(1, failedResult.getFailedCount());
        assertEquals(2, failedResult.getFailed().get(0).getReasons().size());
        assertTrue(failedResult.getFailed().get(0).getReasons().stream().anyMatch(reason -> reason.contains("field 'age' failed")));
        assertTrue(failedResult.getFailed().get(0).getReasons().stream().anyMatch(reason -> reason.contains("field 'status' failed")));
    }

    @Test
    void shouldSupportCombineRulesAndContextVariables() {
        JsonDslDefinition definition = new JsonDslDefinition("filter-context", JsonDslDefinition.DslType.FILTER);
        definition.setFieldDsl(Map.of(
                "status", Map.of("$EXPR", "expectedStatus.equals(status)")
        ));
        definition.setCombineDsl(Map.of(
                "adult-ready", Map.of("$EXPR", "age >= minAge && 'READY'.equals(status)")
        ));

        ProcessingContext context = new ProcessingContext("test");
        context.setVariable("expectedStatus", "READY");
        context.setParameter("minAge", 18);

        TestBean passed = new TestBean("Alice", 20, "READY");
        TestBean failed = new TestBean("Bob", 16, "READY");

        FilterResult<TestBean> passedResult = processor.filter(passed, definition, context);
        FilterResult<TestBean> failedResult = processor.filter(failed, definition, context);

        assertTrue(passedResult.isAllPassed());
        assertTrue(failedResult.isAllFailed());
        assertEquals(1, failedResult.getFailed().get(0).getReasons().size());
        assertTrue(failedResult.getFailed().get(0).getReasons().get(0).contains("combine rule 'adult-ready' failed"));
    }

    @Test
    void shouldReportStrictModeMissingVariablesAsFailureReasons() {
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setStrict(true);

        JsonDslDefinition definition = new JsonDslDefinition("strict-filter", JsonDslDefinition.DslType.FILTER);
        definition.setContext(dslContext);
        definition.setFieldDsl(Map.of("status", "&missing"));

        FilterResult<TestBean> result = processor.filter(new TestBean("Alice", 20, "READY"), definition, new ProcessingContext("test"));

        assertTrue(result.isAllFailed());
        assertEquals(1, result.getFailed().get(0).getReasons().size());
        assertTrue(result.getFailed().get(0).getReasons().get(0).contains("field 'status' error: Unresolved variable: &missing"));
    }

    @Test
    void shouldPreservePerItemReasonsWhenFilteringBatch() {
        JsonDslDefinition definition = new JsonDslDefinition("batch-filter", JsonDslDefinition.DslType.FILTER);
        definition.setFieldDsl(Map.of(
                "age", Map.of("$EXPR", "age >= 18")
        ));
        definition.setCombineDsl(Map.of(
                "ready-only", Map.of("$EXPR", "'READY'.equals(status)")
        ));

        List<TestBean> users = List.of(
                new TestBean("Alice", 20, "READY"),
                new TestBean("Bob", 16, "READY"),
                new TestBean("Charlie", 30, "PENDING")
        );

        FilterResult<TestBean> result = processor.filterList(users, definition, new ProcessingContext("test"));

        assertEquals(1, result.getPassedCount());
        assertEquals(2, result.getFailedCount());
        assertTrue(result.getFailed().stream().anyMatch(failure -> failure.getData().getName().equals("Bob")
                && failure.getReasons().stream().anyMatch(reason -> reason.contains("field 'age' failed"))));
        assertTrue(result.getFailed().stream().anyMatch(failure -> failure.getData().getName().equals("Charlie")
                && failure.getReasons().stream().anyMatch(reason -> reason.contains("combine rule 'ready-only' failed"))));
    }

    @Test
    void shouldRejectNullInputs() {
        JsonDslDefinition definition = new JsonDslDefinition("filter-user", JsonDslDefinition.DslType.FILTER);
        definition.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        assertThrows(JsonDslException.class, () -> processor.filter(null, definition, new ProcessingContext("test")));
        assertThrows(JsonDslException.class, () -> processor.filterList(null, definition, new ProcessingContext("test")));
    }

    static class TestBean {
        private final String name;
        private final Integer age;
        private final String status;

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
