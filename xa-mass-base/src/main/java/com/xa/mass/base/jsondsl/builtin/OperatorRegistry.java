package com.xa.mass.base.jsondsl.builtin;

import com.ql.util.express.ExpressRunner;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 统一的 DSL 操作符/函数注册表。
 * 支持条件判断、mock 生成、表达式等所有 $ 开头函数。
 * 注册时自动注册 $xxx、xxx 两个 key（小写），并可自动同步到 QLExpress。
 */
public class OperatorRegistry {
    private static final Map<String, BiFunction<Object[], DslContext, Object>> FUNCTION_MAP = new HashMap<>();
    private static ExpressRunner qlExpressRunner = null;

    private static final Set<String> QLEXPRESS_BUILTIN_OPS = Set.of(
            "in", "eq", "ne", "gt", "lt", "gte", "lte", "equal"
    );

    static {
        try {
            Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 注册函数，自动注册 $xxx 和 xxx 两个 key（小写），并可同步到 QLExpress。
     */
    public static void registerFunction(String op, BiFunction<Object[], DslContext, Object> func, boolean registerToQLExpress) {
        if (op == null || func == null) return;
        String key = op.toLowerCase();
        FUNCTION_MAP.put(key, func);
        if (key.startsWith("$")) {
            FUNCTION_MAP.put(key.substring(1), func);
        }
        if (registerToQLExpress && qlExpressRunner != null) {
            // $xxx 只有无$前缀不是 QLExpress 内置操作符才注册无$前缀，否则只注册 $xxx
            {
                if (key.startsWith("$")) {
                    registerToQLExpressInternal(key.substring(1), func);
                } else {
                    registerToQLExpressInternal(key, func);
                }

            }
        }
    }

    /**
     * 注册函数（默认自动注册到 QLExpress，如果已设置 runner）
     */
    public static void registerFunction(String op, BiFunction<Object[], DslContext, Object> func) {
        registerFunction(op, func, true);
    }

    /**
     * 获取函数
     */
    public static BiFunction<Object[], DslContext, Object> getFunction(String op) {
        if (op == null) return null;
        return FUNCTION_MAP.get(op.toLowerCase());
    }

    /**
     * 设置 QLExpress Runner，后续注册的函数会自动同步
     */
    public static void setQLExpressRunner(ExpressRunner runner) {
        qlExpressRunner = runner;
        // 已注册的函数也同步注册
        for (Map.Entry<String, BiFunction<Object[], DslContext, Object>> entry : FUNCTION_MAP.entrySet()) {
            registerToQLExpressInternal(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 内部注册到 QLExpress
     */
    private static void registerToQLExpressInternal(String func, BiFunction<Object[], DslContext, Object> impl) {
        try {
            if (QLEXPRESS_BUILTIN_OPS.contains(func.substring(1)) || QLEXPRESS_BUILTIN_OPS.contains(func)) {
                return;
            }
            qlExpressRunner.addFunction(func, new com.ql.util.express.Operator() {
                @Override
                public Object executeInner(Object[] list) throws Exception {
                    return impl.apply(list, null);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("注册 " + func + " 到 QLExpress 失败", e);
        }
    }

    /**
     * 获取所有已注册函数名
     */
    public static Set<String> getAllFunctionNames() {
        return FUNCTION_MAP.keySet();
    }

    /**
     * 批量注册所有已注册函数到 QLExpress
     */
    public static void registerAllToQLExpress(ExpressRunner runner) {
        if (runner == null) return;
        setQLExpressRunner(runner);
    }
} 