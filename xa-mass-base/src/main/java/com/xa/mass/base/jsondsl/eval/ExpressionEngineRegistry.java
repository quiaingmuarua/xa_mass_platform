package com.xa.mass.base.jsondsl.eval;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ExpressionEngineRegistry {

    private static final Map<String, ExpressionEngine> engines = new ConcurrentHashMap<>();

    static {
        // 默认注册 QLExpress 引擎
        register("ql", new QLExpressEngine());
    }

    public static void register(String lang, ExpressionEngine engine) {
        engines.put(lang.toLowerCase(), engine);
    }

    public static ExpressionEngine get(String lang) {
        return engines.get(lang.toLowerCase());
    }

    public static boolean exists(String lang) {
        return engines.containsKey(lang.toLowerCase());
    }
}