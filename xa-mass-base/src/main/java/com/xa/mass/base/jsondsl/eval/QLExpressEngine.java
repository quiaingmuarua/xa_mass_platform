package com.xa.mass.base.jsondsl.eval;

import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;
import com.ql.util.express.Operator;
import com.xa.mass.base.jsondsl.BuiltinFunctions;

import java.util.Map;

public class QLExpressEngine implements ExpressionEngine {

    private final ExpressRunner runner = new ExpressRunner();

    public QLExpressEngine() {
        try {
            // 集中注册所有内置函数
            BuiltinFunctions.registerToQLExpress(runner);
        } catch (Exception e) {
            throw new RuntimeException("QLExpressEngine 初始化失败", e);
        }
    }

    @Override
    public Object eval(String expr, Map<String, Object> context) throws Exception {
        DefaultContext<String, Object> qlContext = new DefaultContext<>();
        if (context != null) {
            qlContext.putAll(context);
        }
        return runner.execute(expr, qlContext, null, true, false);
    }
}