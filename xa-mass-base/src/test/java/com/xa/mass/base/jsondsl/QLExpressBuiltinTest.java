package com.xa.mass.base.jsondsl;

import com.ql.util.express.ExpressRunner;
import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.setAllowComparingPrivateFields;

public class QLExpressBuiltinTest {

    @Test
    public void testChoiceFunction() throws Exception {
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
} 