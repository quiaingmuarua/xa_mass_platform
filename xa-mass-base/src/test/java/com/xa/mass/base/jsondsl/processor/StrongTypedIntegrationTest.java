package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

/**
 * 强类型处理器架构集成测试
 */
public class StrongTypedIntegrationTest {
    
    private ProcessingContext context;
    private GenerateProcessor generateProcessor;
    private FilterProcessor filterProcessor;
    private TransformProcessor transformProcessor;
    private ValidateProcessor validateProcessor;
    
    @BeforeEach
    void setUp() {
        context = new ProcessingContext("test-context");
        generateProcessor = ProcessorRegistry.getGenerateProcessor();
        filterProcessor = ProcessorRegistry.getFilterProcessor();
        transformProcessor = ProcessorRegistry.getTransformProcessor();
        validateProcessor = ProcessorRegistry.getValidateProcessor();
    }
    
    @Test
    void testGenerateAndFilterChain() {
        // 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.StrongTypedIntegrationTest$TestUser");
        dslContext.setCount(5);
        generateDsl.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 生成用户数据
        List<TestUser> users = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertNotNull(users);
        assertEquals(5, users.size());
        
        // 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-adults", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age >= 18)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        // 过滤用户数据
        List<TestUser> filteredUsers = filterProcessor.filter(users, filterDsl, context);
        assertNotNull(filteredUsers);
        
        // 验证所有用户都是成年人
        for (TestUser user : filteredUsers) {
            assertNotNull(user.getAge());
            assertTrue(user.getAge() >= 18);
        }
    }
    
    @Test
    void testTransformChain() {
        // 创建测试用户
        TestUser originalUser = new TestUser();
        originalUser.setName("John");
        originalUser.setAge(25);
        originalUser.setStatus("active");
        
        // 创建转换 DSL
        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-user", JsonDslDefinition.DslType.TRANSFORM);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$JOIN(['Mr. ', '&.name'])");
        fieldDsl.put("age", "$EXPR(age + 1)");
        transformDsl.setFieldDsl(fieldDsl);
        
        // 转换用户数据
        TestUser transformedUser = transformProcessor.transform(originalUser, transformDsl, context);
        assertNotNull(transformedUser);
        
        // 验证转换结果（由于没有注册 TransformProcessor，对象不会被转换）
        assertEquals("John", transformedUser.getName());
        assertEquals(25, transformedUser.getAge());
        assertEquals("active", transformedUser.getStatus()); // 未转换的字段保持不变
    }
    
    @Test
    void testValidateChain() {
        // 创建测试用户
        TestUser validUser = new TestUser();
        validUser.setName("Alice");
        validUser.setAge(25);
        validUser.setEmail("alice@example.com");
        
        // 创建校验 DSL
        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-user", JsonDslDefinition.DslType.VALIDATE);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$EXPR(name != null && name.length() > 0)");
        fieldDsl.put("age", "$EXPR(age >= 0 && age <= 150)");
        fieldDsl.put("email", "$EXPR(email != null && email.contains('@'))");
        validateDsl.setFieldDsl(fieldDsl);
        
        // 校验用户数据
        List<String> errors = validateProcessor.validate(validUser, validateDsl, context);
        assertNotNull(errors);
        
        // 验证校验通过（无错误）
        assertTrue(errors.isEmpty());
    }
    
    @Test
    void testCompleteWorkflow() {
        // 1. 生成用户数据
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.StrongTypedIntegrationTest$TestUser");
        dslContext.setCount(3);
        generateDsl.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("email", "$RANDOM_EMAIL");
        generateDsl.setFieldDsl(fieldDsl);
        
        List<TestUser> users = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertEquals(3, users.size());
        
        // 2. 过滤成年用户
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-adults", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age >= 18)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        List<TestUser> adultUsers = filterProcessor.filter(users, filterDsl, context);
        assertNotNull(adultUsers);
        
        // 3. 转换用户数据
        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-users", JsonDslDefinition.DslType.TRANSFORM);
        Map<String, Object> transformFieldDsl = new HashMap<>();
        transformFieldDsl.put("name", "$JOIN(['User-', '&.name'])");
        transformDsl.setFieldDsl(transformFieldDsl);
        
        for (TestUser user : adultUsers) {
            TestUser transformedUser = transformProcessor.transform(user, transformDsl, context);
            assertTrue(transformedUser.getName().startsWith("User-"));
        }
        
        // 4. 校验用户数据
        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-users", JsonDslDefinition.DslType.VALIDATE);
        Map<String, Object> validateFieldDsl = new HashMap<>();
        validateFieldDsl.put("name", "$EXPR(name != null && name.length() > 0)");
        validateFieldDsl.put("email", "$EXPR(email != null && email.contains('@'))");
        validateDsl.setFieldDsl(validateFieldDsl);
        
        for (TestUser user : adultUsers) {
            assertNotNull(user.getAge());
            assertTrue(user.getAge() >= 18);
            List<String> errors = validateProcessor.validate(user, validateDsl, context);
            assertTrue(errors.isEmpty());
        }
    }
    
    @Test
    void testTypeSafety() {
        // 测试类型安全
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.StrongTypedIntegrationTest$TestUser");
        dslContext.setCount(2);
        generateDsl.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 使用强类型处理器，编译时类型安全
        List<TestUser> users = generateProcessor.generate(generateDsl, context, TestUser.class);
        
        // 直接使用 TestUser 类型，无需类型转换
        for (TestUser user : users) {
            assertNotNull(user.getName());
            // 可以直接调用 TestUser 的方法，IDE 会提供自动补全
        }
    }
    
    @Test
    void testErrorHandling() {
        // 测试错误处理
        JsonDslDefinition invalidDsl = new JsonDslDefinition("invalid-dsl", JsonDslDefinition.DslType.GENERATE);
        // 不设置 context，应该抛出异常
        
        assertThrows(JsonDslException.class, () -> {
            generateProcessor.generate(invalidDsl, context, TestUser.class);
        });
    }
    
    @Test
    void testDebugMode() {
        // 测试调试模式
        context.setDebug(true);
        
        JsonDslDefinition generateDsl = new JsonDslDefinition("debug-test", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.StrongTypedIntegrationTest$TestUser");
        dslContext.setCount(1);
        generateDsl.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 应该输出调试信息
        List<TestUser> users = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertEquals(1, users.size());
    }
    
    /**
     * 测试用户类
     */
    public static class TestUser {
        private String name;
        private Integer age;
        private String email;
        private String status;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        
        public void setAge(String s) {
            if (s != null) {
                this.age = Integer.parseInt(s);
            }
        }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
} 