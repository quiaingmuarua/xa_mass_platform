package com.xa.mass.base.mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@FunctionalInterface
interface BuiltinFunction {
    Object apply(Object param);
}

/**
 * 内置 mock 生成函数注册表，支持 $CHOICE, $RANGE, $UUID, $RANDOM, $JOIN。
 */
public class BuiltinFunctions {
    private static final Random RANDOM = new Random();
    private static final Map<String, BuiltinFunction> FUNCTION_MAP = new HashMap<>();
    static {
        FUNCTION_MAP.put("$CHOICE", param -> choice((List<?>) param));
        FUNCTION_MAP.put("$RANGE", param -> {
            List<?> list = (List<?>) param;
            return range(((Number) list.get(0)).intValue(), ((Number) list.get(1)).intValue());
        });
        FUNCTION_MAP.put("$UUID", param -> uuid());
        FUNCTION_MAP.put("$RANDOM", param -> random());
        FUNCTION_MAP.put("$JOIN", param -> join((List<?>) param));
    }

    public static Object eval(String func, Object param) {
        BuiltinFunction f = FUNCTION_MAP.get(func);
        if (f != null) return f.apply(param);
        throw new MockTemplateException("不支持的内置函数: " + func + " 参数: " + param);
    }

    public static Object choice(List<?> options) {
        if (options == null || options.isEmpty()) return null;
        return options.get(RANDOM.nextInt(options.size()));
    }

    public static int range(int min, int max) {
        return min + RANDOM.nextInt(max - min + 1);
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static int random() {
        return RANDOM.nextInt();
    }

    public static String join(List<?> parts) {
        return parts.stream().map(String::valueOf).collect(Collectors.joining());
    }
} 