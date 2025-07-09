package com.xa.mass.base.jsondsl.generate;

import com.google.gson.JsonObject;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DslObjectBuilderTest {

    private DslContext context;

    @BeforeEach
    void setUp() {
        context = new DslContext();
        TypeRegistry.register("TestData", TestData.class.getName());
        TypeRegistry.register("NestedData", NestedData.class.getName());
        TypeRegistry.register("SubNestedData", SubNestedData.class.getName());
    }

    @Test
    void testMockFieldValueWithSimpleValue() {
        Object rule = "test value";
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertEquals("test value", result);
    }

    @Test
    void testMockFieldValueWithContextVariable() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("$CONTEXT", "currentUser");
        context.setVariable("currentUser", "Alice");
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertEquals("Alice", result);
    }

    @Test
    void testMockFieldValueWithUUID() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("$UUID", null);
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        assertFalse(((String) result).isEmpty());
    }

    @Test
    void testMockFieldValueWithList() {
        List<Object> rule = List.of("a", "b", "c");
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        assertEquals("a", list.get(0));
        assertEquals("b", list.get(1));
        assertEquals("c", list.get(2));
    }

    @Test
    void testMockFieldValueWithMap() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("key1", "value1");
        rule.put("key2", "value2");
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertNotNull(result);
        assertTrue(result instanceof Map);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("value1", map.get("key1"));
        assertEquals("value2", map.get("key2"));
    }

    @Test
    void testMockFieldValueWithNestedObject() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("MODEL", "NestedData");
        rule.put("FIELDS", Map.of("value", "Nested Object"));
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertNotNull(result);
        assertTrue(result instanceof NestedData);
        NestedData nestedData = (NestedData) result;
        assertEquals("Nested Object", nestedData.getValue());
    }

    @Test
    void testMockFromDslWithSimpleObject() {
        JsonObject dsl = new JsonObject();
        dsl.addProperty("MODEL", "TestData");
        JsonObject fields = new JsonObject();
        fields.addProperty("name", "John");
        fields.addProperty("age", 25);
        dsl.add("FIELDS", fields);
        TestData result = DslObjectBuilder.mockFromDsl(dsl, context, TestData.class);
        assertNotNull(result);
        assertEquals("John", result.getName());
        assertEquals(25, result.getAge());
    }

    @Test
    void testMockFromDslWithComplexObject() {
        JsonObject dsl = new JsonObject();
        dsl.addProperty("MODEL", "TestData");
        JsonObject fields = new JsonObject();
        fields.addProperty("name", "Complex Test");
        JsonObject nestedFields = new JsonObject();
        nestedFields.addProperty("value", "Nested Value");
        JsonObject nestedDsl = new JsonObject();
        nestedDsl.addProperty("MODEL", "NestedData");
        nestedDsl.add("FIELDS", nestedFields);
        fields.add("nested", nestedDsl);
        dsl.add("FIELDS", fields);
        TestData result = DslObjectBuilder.mockFromDsl(dsl, context, TestData.class);
        assertNotNull(result);
        assertEquals("Complex Test", result.getName());
        assertNotNull(result.getNested());
        assertTrue(result.getNested() instanceof NestedData);
        assertEquals("Nested Value", ((NestedData) result.getNested()).getValue());
    }

    @Test
    void testMockFromDslWithMissingModel() {
        JsonObject dsl = new JsonObject();
        JsonObject fields = new JsonObject();
        fields.addProperty("name", "test");
        dsl.add("FIELDS", fields);
        JsonDslException exception = assertThrows(JsonDslException.class, () -> {
            DslObjectBuilder.mockFromDsl(dsl, context, TestData.class);
        });
        assertTrue(exception.getMessage().contains("DSL 缺少 MODEL 字段"));
    }

    @Test
    void testMockFromDslWithInvalidType() {
        JsonObject dsl = new JsonObject();
        dsl.addProperty("MODEL", "NonExistentClass");
        JsonObject fields = new JsonObject();
        fields.addProperty("name", "test");
        dsl.add("FIELDS", fields);
        JsonDslException exception = assertThrows(JsonDslException.class, () -> {
            DslObjectBuilder.mockFromDsl(dsl, context, TestData.class);
        });
        assertTrue(exception.getMessage().contains("未注册类型") ||
                exception.getMessage().contains("无法实例化模型"));
    }

    @Test
    void testMockFieldValueWithNullRule() {
        Object result = DslObjectBuilder.mockFieldValue(null, context);
        assertNull(result);
    }

    @Test
    void testMockFieldValueWithEmptyMap() {
        Map<String, Object> rule = new HashMap<>();
        Object result = DslObjectBuilder.mockFieldValue(rule, context);
        assertNotNull(result);
        assertTrue(result instanceof Map);
        assertTrue(((Map<?, ?>) result).isEmpty());
    }

    // Test data classes
    public static class TestData {
        private String name;
        private int age;
        private Object nested;
        private List<Object> items;
        private Map<String, Object> properties;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public Object getNested() {
            return nested;
        }

        public void setNested(Object nested) {
            this.nested = nested;
        }

        public List<Object> getItems() {
            return items;
        }

        public void setItems(List<Object> items) {
            this.items = items;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties;
        }
    }

    public static class NestedData {
        private String value;
        private Object subNested;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public Object getSubNested() {
            return subNested;
        }

        public void setSubNested(Object subNested) {
            this.subNested = subNested;
        }
    }

    public static class SubNestedData {
        private String subValue;

        public String getSubValue() {
            return subValue;
        }

        public void setSubValue(String subValue) {
            this.subValue = subValue;
        }
    }
} 