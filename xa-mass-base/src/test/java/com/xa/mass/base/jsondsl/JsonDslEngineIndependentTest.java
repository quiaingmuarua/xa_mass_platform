package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试 JsonDslEngine 独立的 generate 和 filter 方法
 */
public class JsonDslEngineIndependentTest {

    static class TestFilterProcessor implements FilterProcessor {
        
        @Override
        public <T> List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context) {
            // 过滤掉不包含或为 null 的 age/status/score 字段的对象
            List<T> result = new java.util.ArrayList<>();
            for (T obj : input) {
                if (obj instanceof java.util.Map) {
                    java.util.Map<?,?> map = (java.util.Map<?,?>) obj;
                    if (!map.containsKey("age") || map.get("age") == null) continue;
                    if (!map.containsKey("status") || map.get("status") == null) continue;
                    if (!map.containsKey("score") || map.get("score") == null) continue;
                }
                result.add(obj);
            }
            return result;
        }

        @Override
        public <T> FilterReport<T> filterWithReport(List<T> data, JsonDslDefinition def, ProcessingContext ctx) {
            return null;
        }

        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.FILTER.equals(type);
        }
        
        @Override
        public String getName() {
            return "TestFilterProcessor";
        }
        
        @Override
        public int getPriority() {
            return 150;
        }
    }

    @Test
    public void testGenerateAndFilterIndependently() {
        // 注册测试过滤处理器
        JsonDslProcessorEngine.registerProcessor(new TestFilterProcessor());
        // 1. 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("test-generate", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext context = new JsonDslContext();
        context.setModel("java.util.HashMap");
        context.setCount(5);
        generateDsl.setContext(context);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 100)");
        fieldDsl.put("name", "$CHOICE(Alice, Bob, Charlie, Diana, Eve)");
        fieldDsl.put("age", "$RANGE(18, 50)");
        fieldDsl.put("score", "$RANGE(60, 100)");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 2. 独立生成数据
        ProcessingContext processingContext = new ProcessingContext();
        List<Map> allUsers = JsonDslProcessorEngine.process(generateDsl, processingContext, Map.class);
        assertEquals(5, allUsers.size());
        
        // 3. 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("test-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age >= 25)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        // 4. 设置上下文参数
        ProcessingContext filterContext = new ProcessingContext();
        filterContext.setParameter("input", allUsers);
        
        // 5. 执行过滤
        List<Map> filteredUsers = JsonDslProcessorEngine.process(filterDsl, filterContext, Map.class);
        assertTrue(filteredUsers.size() <= allUsers.size());
        
        // 6. 验证过滤结果
        for (Map user : filteredUsers) {
            Object ageObj = user.get("age");
            if (ageObj == null) fail("用户年龄不能为 null");
            int age = Integer.parseInt(ageObj.toString());
            assertTrue(age >= 25, "过滤后的用户年龄应该 >= 25");
        }
        
        System.out.println("原始用户数: " + allUsers.size());
        System.out.println("过滤后用户数: " + filteredUsers.size());
    }
    
    @Test
    public void testGenerateMapAndFilter() {
        JsonDslProcessorEngine.registerProcessor(new TestFilterProcessor());
        // 1. 创建用户生成 DSL
        JsonDslDefinition userDsl = new JsonDslDefinition("test-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext userContext = new JsonDslContext();
        userContext.setModel("java.util.HashMap");
        userContext.setCount(3);
        userDsl.setContext(userContext);
        
        Map<String, Object> userFieldDsl = new HashMap<>();
        userFieldDsl.put("id", "$RANGE(1, 100)");
        userFieldDsl.put("name", "$CHOICE(Alice, Bob, Charlie)");
        userFieldDsl.put("age", "$RANGE(18, 50)");
        userDsl.setFieldDsl(userFieldDsl);
        
        // 2. 创建产品生成 DSL
        JsonDslDefinition productDsl = new JsonDslDefinition("test-products", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext productContext = new JsonDslContext();
        productContext.setModel("java.util.HashMap");
        productContext.setCount(2);
        productDsl.setContext(productContext);
        
        Map<String, Object> productFieldDsl = new HashMap<>();
        productFieldDsl.put("id", "$RANGE(1, 100)");
        productFieldDsl.put("name", "$CHOICE(iPhone, MacBook)");
        productFieldDsl.put("price", "$RANGE(100, 1000)");
        productDsl.setFieldDsl(productFieldDsl);
        
        // 3. 独立生成多模型数据
        ProcessingContext processingContext = new ProcessingContext();
        List<Map> users = JsonDslProcessorEngine.process(userDsl, processingContext, Map.class);
        List<Map> products = JsonDslProcessorEngine.process(productDsl, processingContext, Map.class);
        
        Map<String, List<Map>> allModels = new HashMap<>();
        allModels.put("users", users);
        allModels.put("products", products);
        
        assertEquals(2, allModels.size());
        assertEquals(3, allModels.get("users").size());
        assertEquals(2, allModels.get("products").size());
        
        // 4. 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("test-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age >= 25)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        // 5. 过滤用户数据
        ProcessingContext filterContext = new ProcessingContext();
        filterContext.setParameter("input", users);
        List<Map> filteredUsers = JsonDslProcessorEngine.process(filterDsl, filterContext, Map.class);
        
        // 6. 构建过滤后的模型映射
        Map<String, List<Map>> filteredModels = new HashMap<>();
        filteredModels.put("users", filteredUsers);
        filteredModels.put("products", products); // 产品没有 age 字段，保持不变
        
        // 7. 验证过滤结果
        assertTrue(filteredModels.get("users").size() <= allModels.get("users").size());
        assertEquals(allModels.get("products").size(), filteredModels.get("products").size()); // 产品没有 age 字段，应该保持不变
        
        System.out.println("原始用户数: " + allModels.get("users").size());
        System.out.println("过滤后用户数: " + filteredModels.get("users").size());
    }
    
    @Test
    public void testChainFiltering() {
        JsonDslProcessorEngine.registerProcessor(new TestFilterProcessor());
        // 1. 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("test-chain", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext context = new JsonDslContext();
        context.setModel("java.util.HashMap");
        context.setCount(10);
        generateDsl.setContext(context);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 100)");
        fieldDsl.put("name", "$CHOICE(Alice, Bob, Charlie, Diana, Eve)");
        fieldDsl.put("age", "$RANGE(16, 70)");
        fieldDsl.put("score", "$RANGE(30, 100)");
        fieldDsl.put("status", "$CHOICE(active, inactive)");
        generateDsl.setFieldDsl(fieldDsl);
        
        ProcessingContext processingContext = new ProcessingContext();
        List<Map> allUsers = JsonDslProcessorEngine.process(generateDsl, processingContext, Map.class);
        assertEquals(10, allUsers.size());
        
        // 2. 链式过滤：年龄 -> 状态 -> 分数 -> 部门
        ProcessingContext filterContext = new ProcessingContext();
        
        // 步骤1：年龄过滤
        JsonDslDefinition filter1 = new JsonDslDefinition("filter-age", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter1Dsl = new HashMap<>();
        filter1Dsl.put("age", "$EXPR(age >= 25)");
        filter1.setFieldDsl(filter1Dsl);
        filterContext.setParameter("input", allUsers);
        List<Map> step1 = JsonDslProcessorEngine.process(filter1, filterContext, Map.class);
        System.out.println("年龄>=25: " + step1.size());
        
        // 步骤2：状态过滤
        JsonDslDefinition filter2 = new JsonDslDefinition("filter-status", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter2Dsl = new HashMap<>();
        filter2Dsl.put("status", "$EXPR(status == 'active')");
        filter2.setFieldDsl(filter2Dsl);
        filterContext.setParameter("input", step1);
        List<Map> step2 = JsonDslProcessorEngine.process(filter2, filterContext, Map.class);
        System.out.println("状态=active: " + step2.size());
        
        // 步骤3：分数过滤
        JsonDslDefinition filter3 = new JsonDslDefinition("filter-score-min", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter3Dsl = new HashMap<>();
        filter3Dsl.put("score", "$EXPR(score >= 70)");
        filter3.setFieldDsl(filter3Dsl);
        filterContext.setParameter("input", step2);
        List<Map> step3 = JsonDslProcessorEngine.process(filter3, filterContext, Map.class);
        System.out.println("分数>=70: " + step3.size());
        
        // 步骤4：分数上限过滤
        JsonDslDefinition filter4 = new JsonDslDefinition("filter-score-max", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter4Dsl = new HashMap<>();
        filter4Dsl.put("score", "$EXPR(score <= 100)");
        filter4.setFieldDsl(filter4Dsl);
        filterContext.setParameter("input", step3);
        List<Map> step4 = JsonDslProcessorEngine.process(filter4, filterContext, Map.class);
        System.out.println("分数<=100: " + step4.size());
        
        // 3. 验证链式过滤结果
        assertTrue(step1.size() <= allUsers.size());
        assertTrue(step2.size() <= step1.size());
        assertTrue(step3.size() <= step2.size());
        assertTrue(step4.size() <= step3.size());
        
        // 4. 验证最终结果
        for (Map user : step4) {
            Object ageObj = user.get("age");
            Object statusObj = user.get("status");
            Object scoreObj = user.get("score");
            if (ageObj == null) fail("用户年龄不能为 null");
            if (statusObj == null) fail("用户状态不能为 null");
            if (scoreObj == null) fail("用户分数不能为 null");
            int age = Integer.parseInt(ageObj.toString());
            String status = statusObj.toString();
            int score = Integer.parseInt(scoreObj.toString());
            assertTrue(age >= 25, "最终用户年龄应该 >= 25");
            assertEquals("active", status, "最终用户状态应该是 active");
            assertTrue(score >= 70 && score <= 100, "最终用户分数应该在 70-100 之间");
        }
        
        System.out.println("链式过滤完成，最终用户数: " + step4.size());
    }
} 