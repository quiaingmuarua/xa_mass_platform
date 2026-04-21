package com.xa.mass.base.jsondsl;

import com.alibaba.qlexpress4.Express4Runner;
import com.alibaba.qlexpress4.InitOptions;
import com.alibaba.qlexpress4.QLOptions;
import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.FilterProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class QLExpressBuiltinTest {
    private static final QLOptions EXECUTE_OPTIONS = QLOptions.builder()
            .cache(true)
            .build();

    private FilterProcessor processor;
    private JsonDslDefinition definition;
    private ProcessingContext context;

    @Test
    public void testGenerateFunction() throws Exception {
        Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        Express4Runner runner = createRunner();
        BuiltinFunctions.registerToQLExpress(runner);

        Object result = runner.execute("range(1,100)", Map.of(), EXECUTE_OPTIONS).getResult();
        assertThat(Integer.class.isAssignableFrom(result.getClass())).isTrue();

        Object result1 = runner.execute("$choice('active', 'inactive', 'pending')", Map.of(), EXECUTE_OPTIONS).getResult();
        assertThat(String.class.isAssignableFrom(result1.getClass())).isTrue();
    }

    @Test
    public void testFilterFunction() throws Exception {
        List<Map<String, Object>> testUsers = Arrays.asList(
                createTestUser("Bob", 35, "inactive"),
                createTestUser("Charlie", 45, "active"),
                createTestUser("David", 55, "active")
        );
        definition = new JsonDslDefinition("test-filter", JsonDslDefinition.DslType.FILTER);
        context = new ProcessingContext("test-context");
        Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        Express4Runner runner = createRunner();
        BuiltinFunctions.registerToQLExpress(runner);

        Map<String, Object> fieldDsl = new HashMap<>();
        Map<String, Object> qlContext = new HashMap<>();
        qlContext.put("users", testUsers);
        qlContext.put("user", testUsers.get(0));
        qlContext.put("age", 40);

        Object age = runner.execute("user.age > 30", qlContext, EXECUTE_OPTIONS).getResult();
        assertThat(Boolean.class.isAssignableFrom(age.getClass())).isTrue();

        Object result = runner.execute("user.name == 'Bob'", qlContext, EXECUTE_OPTIONS).getResult();
        assertThat(result).isEqualTo(true);

        assertThat(fieldDsl).isEmpty();
        assertThat(processor).isNull();
    }

    private Express4Runner createRunner() {
        return new Express4Runner(InitOptions.DEFAULT_OPTIONS);
    }

    private Map<String, Object> createTestUser(String name, int age, String status) {
        Map<String, Object> user = new HashMap<>();
        user.put("name", name);
        user.put("age", age);
        user.put("status", status);
        return user;
    }
}
