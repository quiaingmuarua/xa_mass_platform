package com.xa.mass.base.jsondsl.eval;

import com.alibaba.qlexpress4.Express4Runner;
import com.alibaba.qlexpress4.InitOptions;
import com.alibaba.qlexpress4.QLOptions;
import com.alibaba.qlexpress4.aparser.QCompileCache;
import com.alibaba.qlexpress4.security.QLSecurityStrategy;
import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class QLExpressEngine implements ExpressionEngine {

    private final Express4Runner runner;
    private final QLOptions executeOptions;
    private final AtomicInteger compileCount = new AtomicInteger();

    public QLExpressEngine() {
        try {
            this.runner = new Express4Runner(InitOptions.builder()
                    .securityStrategy(QLSecurityStrategy.open())
                    .build());
            this.executeOptions = QLOptions.builder()
                    .cache(true)
                    .build();
            BuiltinFunctions.registerToQLExpress(runner);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize QLExpressEngine", e);
        }
    }

    @Override
    public Object eval(String expr, Map<String, Object> context) throws Exception {
        return runner.execute(expr, toQlContext(context), executeOptions).getResult();
    }

    @Override
    public CompiledExpression compile(String expr) throws Exception {
        compileCount.incrementAndGet();
        QCompileCache compileCache = runner.parseToDefinitionWithCache(expr);
        return new CompiledExpression(expr, compileCache);
    }

    @Override
    public Object eval(CompiledExpression compiledExpression, Map<String, Object> context) throws Exception {
        return runner.execute(compiledExpression.expression(), toQlContext(context), executeOptions).getResult();
    }

    public int getCompileCount() {
        return compileCount.get();
    }

    public void resetCompileCount() {
        compileCount.set(0);
        runner.clearCompileCache();
    }

    private Map<String, Object> toQlContext(Map<String, Object> context) {
        return context == null ? Collections.emptyMap() : context;
    }
}
