package com.xa.mass.base.jsondsl.example;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.processor.*;

import java.util.*;

/**
 * 强类型处理器架构使用示例
 * <p>
 * 展示如何使用新的强类型处理器接口进行 DSL 处理
 * </p>
 */
public class ProcessorExample {
    
    public static void main(String[] args) {
        // 示例1：强类型生成处理器
        strongTypedGenerateExample();
        
        // 示例2：强类型过滤处理器
        strongTypedFilterExample();
        
        // 示例3：强类型转换处理器
        strongTypedTransformExample();
        
        // 示例4：强类型校验处理器
        strongTypedValidateExample();
        
        // 示例5：自定义强类型处理器
        customStrongTypedProcessorExample();
        
        // 示例6：调试模式
        debugModeExample();
    }
    
    /**
     * 示例1：强类型生成处理器
     */
    public static void strongTypedGenerateExample() {
        System.out.println("=== 示例1：强类型生成处理器 ===");
        
        // 创建强类型处理器
        GenerateProcessor generateProcessor = ProcessorRegistry.getGenerateProcessor();
        
        // 创建生成类型的 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("user-generator", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setDescription("生成用户数据");
        generateDsl.setPriority(1);
        
        JsonDslContext context = new JsonDslContext();
        context.setModel("com.xa.mass.base.jsondsl.example.ProcessorExample$TestUser");
        context.setCount(3);
        generateDsl.setContext(context);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        fieldDsl.put("email", "$RANDOM_EMAIL");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 创建处理上下文
        ProcessingContext processingContext = new ProcessingContext("generate-example");
        
        // 生成数据
        try {
            List<TestUser> users = generateProcessor.generate(generateDsl, processingContext, TestUser.class);
            System.out.println("生成用户数量: " + users.size());
            for (TestUser user : users) {
                System.out.println("用户: " + user.getName() + ", 年龄: " + user.getAge() + ", 邮箱: " + user.getEmail());
            }
        } catch (Exception e) {
            System.out.println("生成失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例2：强类型过滤处理器
     */
    public static void strongTypedFilterExample() {
        System.out.println("\n=== 示例2：强类型过滤处理器 ===");
        
        // 创建强类型处理器
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        
        // 创建测试数据
        List<TestUser> testUsers = Arrays.asList(
            createTestUser("Alice", 25, "alice@example.com"),
            createTestUser("Bob", 35, "bob@example.com"),
            createTestUser("Charlie", 45, "charlie@example.com"),
            createTestUser("David", 55, "david@example.com")
        );
        
        // 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        filterDsl.setDescription("过滤年龄大于30的用户");
        filterDsl.setPriority(2);
        
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age > 30)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("filter-example");
        
        // 过滤数据
        try {
            FilterResult<TestUser> filteredResult = filterProcessor.filter(testUsers, filterDsl, context);
            List<TestUser> filteredUsers = filteredResult.getPassed();
            System.out.println("原始用户数量: " + testUsers.size());
            System.out.println("过滤后用户数量: " + filteredUsers.size());
            for (TestUser user : filteredUsers) {
                System.out.println("过滤后用户: " + user.getName() + ", 年龄: " + user.getAge());
            }
        } catch (Exception e) {
            System.out.println("过滤失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例3：强类型转换处理器
     */
    public static void strongTypedTransformExample() {
        System.out.println("\n=== 示例3：强类型转换处理器 ===");
        
        // 创建强类型处理器
        TransformProcessor transformProcessor = ProcessorRegistry.getTransformProcessor();
        
        // 创建测试用户
        TestUser originalUser = createTestUser("John", 30, "john@example.com");
        originalUser.setStatus("active");
        
        // 创建转换 DSL
        JsonDslDefinition transformDsl = new JsonDslDefinition("user-transform", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setDescription("转换用户数据");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$JOIN(['Mr. ', '&.name'])");
        fieldDsl.put("age", "$EXPR(age + 1)");
        fieldDsl.put("status", "$EXPR(status == 'active' ? 'ACTIVE' : 'INACTIVE')");
        transformDsl.setFieldDsl(fieldDsl);
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("transform-example");
        
        // 转换数据
        try {
            TestUser transformedUser = transformProcessor.transform(originalUser, transformDsl, context);
            System.out.println("原始用户: " + originalUser.getName() + ", 年龄: " + originalUser.getAge() + ", 状态: " + originalUser.getStatus());
            System.out.println("转换后用户: " + transformedUser.getName() + ", 年龄: " + transformedUser.getAge() + ", 状态: " + transformedUser.getStatus());
        } catch (Exception e) {
            System.out.println("转换失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例4：强类型校验处理器
     */
    public static void strongTypedValidateExample() {
        System.out.println("\n=== 示例4：强类型校验处理器 ===");
        
        // 创建强类型处理器
        ValidateProcessor validateProcessor = ProcessorRegistry.getValidateProcessor();
        
        // 创建测试用户
        TestUser validUser = createTestUser("Alice", 25, "alice@example.com");
        validUser.setStatus("active");
        
        // 创建校验 DSL
        JsonDslDefinition validateDsl = new JsonDslDefinition("user-validate", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setDescription("校验用户数据");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$EXPR(name != null && name.length() > 0)");
        fieldDsl.put("age", "$EXPR(age >= 0 && age <= 150)");
        fieldDsl.put("email", "$EXPR(email != null && email.contains('@'))");
        fieldDsl.put("status", "$EXPR(status != null && (status == 'active' || status == 'inactive'))");
        validateDsl.setFieldDsl(fieldDsl);
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("validate-example");
        
        // 校验数据
        try {
            List<String> errors = validateProcessor.validate(validUser, validateDsl, context);
            if (errors.isEmpty()) {
                System.out.println("用户数据校验通过");
            } else {
                System.out.println("用户数据校验失败，错误信息:");
                for (String error : errors) {
                    System.out.println("  - " + error);
                }
            }
        } catch (Exception e) {
            System.out.println("校验失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例5：自定义强类型处理器
     */
    public static void customStrongTypedProcessorExample() {
        System.out.println("\n=== 示例5：自定义强类型处理器 ===");
        
        // 创建自定义强类型处理器
        CustomGenerateProcessor customProcessor = new CustomGenerateProcessor();
        
        // 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("custom-generator", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setDescription("使用自定义处理器生成数据");
        
        JsonDslContext context = new JsonDslContext();
        context.setModel("com.xa.mass.base.jsondsl.example.ProcessorExample$TestUser");
        context.setCount(2);
        generateDsl.setContext(context);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$CUSTOM_NAME");
        fieldDsl.put("age", "$CUSTOM_AGE");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 创建处理上下文
        ProcessingContext processingContext = new ProcessingContext("custom-example");
        
        // 生成数据
        try {
            List<TestUser> users = customProcessor.generate(generateDsl, processingContext, TestUser.class);
            System.out.println("自定义处理器生成用户数量: " + users.size());
            for (TestUser user : users) {
                System.out.println("自定义用户: " + user.getName() + ", 年龄: " + user.getAge());
            }
        } catch (Exception e) {
            System.out.println("自定义处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例6：调试模式
     */
    public static void debugModeExample() {
        System.out.println("\n=== 示例6：调试模式 ===");
        
        // 创建调试上下文
        ProcessingContext debugContext = new ProcessingContext("debug-example");
        debugContext.setDebug(true);
        
        // 创建强类型处理器
        GenerateProcessor generateProcessor = ProcessorRegistry.getGenerateProcessor();
        
        // 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("debug-generator", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setDescription("调试模式生成数据");
        
        JsonDslContext context = new JsonDslContext();
        context.setModel("com.xa.mass.base.jsondsl.example.ProcessorExample$TestUser");
        context.setCount(1);
        generateDsl.setContext(context);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 生成数据（会输出调试信息）
        try {
            List<TestUser> users = generateProcessor.generate(generateDsl, debugContext, TestUser.class);
            System.out.println("调试模式生成用户数量: " + users.size());
        } catch (Exception e) {
            System.out.println("调试处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建测试用户
     */
    private static TestUser createTestUser(String name, int age, String email) {
        TestUser user = new TestUser();
        user.setName(name);
        user.setAge(age);
        user.setEmail(email);
        return user;
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
    
    /**
     * 自定义强类型生成处理器示例
     */
    public static class CustomGenerateProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            if (TestUser.class.equals(targetType)) {
                System.out.println("[CustomGenerateProcessor] 处理自定义 DSL: " + definition.getUniqueId());
                
                // 自定义生成逻辑
                List<TestUser> users = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    TestUser user = new TestUser();
                    user.setName("CustomUser" + (i + 1));
                    user.setAge(25 + i * 5);
                    user.setEmail("custom" + (i + 1) + "@example.com");
                    user.setStatus("custom");
                    users.add(user);
                }
                
                @SuppressWarnings("unchecked")
                List<T> typedResult = (List<T>) users;
                return typedResult;
            }
            throw new IllegalArgumentException("Unsupported target type: " + targetType);
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }
        
        @Override
        public String getName() {
            return "CustomGenerateProcessor";
        }
        
        @Override
        public int getPriority() {
            return 500; // 高优先级
        }
    }
} 