package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.eval.DslExprExecutor;
import com.xa.mass.base.jsondsl.eval.ExpressionEngineRegistry;
import com.xa.mass.base.jsondsl.eval.QLExpressEngine;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorHotPathRegressionTest {

    private final DefaultFilterProcessor filterProcessor = new DefaultFilterProcessor();
    private final DefaultTransformProcessor transformProcessor = new DefaultTransformProcessor();
    private final DefaultValidateProcessor validateProcessor = new DefaultValidateProcessor();
    private final ProcessingContext context = new ProcessingContext("performance-regression");

    @BeforeEach
    void setUp() {
        ((QLExpressEngine) ExpressionEngineRegistry.get("ql")).resetCompileCount();
    }

    @Test
    void shouldHandleSelfReferentialBeanWithoutJsonRoundTrip() {
        SelfReferentialBean bean = new SelfReferentialBean("Alice", 20, "READY");

        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-self", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-self", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "'user-' + age"),
                "status", "DONE"
        ));

        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-self", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "name != null && name.length() > 0"),
                "status", Map.of("$EXPR", "'DONE'.equals(status)")
        ));

        FilterResult<SelfReferentialBean> filterResult = filterProcessor.filter(bean, filterDsl, context);
        SelfReferentialBean transformed = transformProcessor.transform(bean, transformDsl, context);
        List<String> validationErrors = validateProcessor.validate(transformed, validateDsl, context);

        assertTrue(filterResult.isAllPassed());
        assertEquals("Alice", bean.getName());
        assertEquals("user-20", transformed.getName());
        assertEquals("DONE", transformed.getStatus());
        assertTrue(validationErrors.isEmpty());
    }

    @Test
    void shouldRunBeanPerformanceBaselineAndReuseCompiledExpr() {
        List<SelfReferentialBean> beans = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            beans.add(new SelfReferentialBean("user-" + i, i % 100, i % 2 == 0 ? "READY" : "PENDING"));
        }

        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-performance", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 18")));

        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-performance", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "'ready-' + age"),
                "status", "DONE"
        ));

        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-performance", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "name != null && name.length() > 0"),
                "status", Map.of("$EXPR", "'DONE'.equals(status)")
        ));

        long filterStart = System.currentTimeMillis();
        FilterResult<SelfReferentialBean> filterResult = filterProcessor.filterList(beans, filterDsl, context);
        long filterDuration = System.currentTimeMillis() - filterStart;

        long transformStart = System.currentTimeMillis();
        List<SelfReferentialBean> transformed = filterResult.getPassed().stream()
                .map(bean -> transformProcessor.transform(bean, transformDsl, context))
                .toList();
        long transformDuration = System.currentTimeMillis() - transformStart;

        long validateStart = System.currentTimeMillis();
        List<List<String>> validationResults = transformed.stream()
                .map(bean -> validateProcessor.validate(bean, validateDsl, context))
                .toList();
        long validateDuration = System.currentTimeMillis() - validateStart;

        System.out.println("Bean FILTER 1000 items: " + filterDuration + "ms");
        System.out.println("Bean TRANSFORM 1000 items: " + transformDuration + "ms");
        System.out.println("Bean VALIDATE 1000 items: " + validateDuration + "ms");

        assertFalse(filterResult.getPassed().isEmpty());
        assertEquals(filterResult.getPassedCount(), transformed.size());
        assertTrue(validationResults.stream().allMatch(List::isEmpty));
        assertTrue(((QLExpressEngine) ExpressionEngineRegistry.get("ql")).getCompileCount() <= 4);
        assertNotNull(beans.get(0).getSelf());
    }

    static class SelfReferentialBean {
        private Object self;
        private String name;
        private Integer age;
        private String status;

        SelfReferentialBean(String name, Integer age, String status) {
            this.name = name;
            this.age = age;
            this.status = status;
            this.self = this;
        }

        public SelfReferentialBean() {
            this.self = this;
        }

        public Object getSelf() {
            return self;
        }

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
