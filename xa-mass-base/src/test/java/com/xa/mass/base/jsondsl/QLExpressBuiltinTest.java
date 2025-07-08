package com.xa.mass.base.jsondsl;

import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;
import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.FilterProcessor;
import com.xa.mass.base.jsondsl.processor.FilterProcessorTest;
import com.xa.mass.base.jsondsl.processor.FilterResult;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.setAllowComparingPrivateFields;

public class QLExpressBuiltinTest {
    private FilterProcessor processor;
    private JsonDslDefinition definition;
    private ProcessingContext context;
    @Test
    public void testGenerateFunction() throws Exception {
        // 强制触发 BuiltinFunctions 的 static 块，确保所有内置函数注册
        Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        ExpressRunner runner = new ExpressRunner();
        BuiltinFunctions.registerToQLExpress(runner);

        Object result = runner.execute("range(1,100)", null, null, true, false);
        assertThat(Integer.class.isAssignableFrom(result.getClass())).isTrue();
        System.out.println("range result: " + result);
        Object result1 = runner.execute("$choice('active', 'inactive', 'pending')", null, null, true, false);
        System.out.println("choice result: "+result1);
        assertThat(String.class.isAssignableFrom(result1.getClass())).isTrue();


    }


    @Test
    public void testFilterFunction() throws  Exception{
        // 设置测试数据
        List<FilterProcessorTest.TestUser> testUsers = Arrays.asList(

                createTestUser("Bob", 35, "inactive"),
                createTestUser("Charlie", 45, "active"),
                createTestUser("David", 55, "active")
        );
        definition = new JsonDslDefinition("test-filter", JsonDslDefinition.DslType.FILTER);
        context = new ProcessingContext("test-context");
        Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        ExpressRunner runner = new ExpressRunner();
        BuiltinFunctions.registerToQLExpress(runner);
        Map<String, Object> fieldDsl = new HashMap<>();
        DefaultContext<String, Object> qlContext = new DefaultContext<>();
        qlContext.put("users", testUsers);
        qlContext.put("user", testUsers.get(0));
        qlContext.put("age", 40);
        Object age =runner.execute("user.age > 30",qlContext,null, true, false);
        System.out.println(age);
        //age is true
        assertThat(Boolean.class.isAssignableFrom(age.getClass())).isTrue();
        Object result =runner.execute("user.name == 'Bob'",qlContext,null, true, false);
        System.out.println(result);


    }


    private FilterProcessorTest.TestUser createTestUser(String name, int age, String status) {
        FilterProcessorTest.TestUser user = new FilterProcessorTest.TestUser();
        user.setName(name);
        user.setAge(age);
        user.setStatus(status);
        return user;
    }

    /**
     * 测试用户类
     */
    public static class TestUser {
        private String name;
        private Integer age;
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

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
} 