package com.xa.mass.base.jsondsl;

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
 * 内置 mock 生成函数注册表，支持 $CHOICE, $RANGE, $UUID, $RANDOM, $JOIN, $CONTEXT。
 */
public class BuiltinFunctions {
    private static final Random RANDOM = new Random();
    private static final Map<BuiltinFunc, BuiltinFunction> FUNCTION_MAP = new HashMap<>();
    static {
        FUNCTION_MAP.put(BuiltinFunc.CHOICE, param -> choice((List<?>) param));
        FUNCTION_MAP.put(BuiltinFunc.RANGE, param -> {
            List<?> list = (List<?>) param;
            return range(((Number) list.get(0)).intValue(), ((Number) list.get(1)).intValue());
        });
        FUNCTION_MAP.put(BuiltinFunc.UUID, param -> uuid());
        FUNCTION_MAP.put(BuiltinFunc.RANDOM, param -> random());
        FUNCTION_MAP.put(BuiltinFunc.JOIN, param -> join((List<?>) param));
        FUNCTION_MAP.put(BuiltinFunc.CONTEXT, param -> context(param));
    }

    public static Object eval(String func, Object param) {
        BuiltinFunc f = BuiltinFunc.fromKey(func);
        if (f != null) {
            BuiltinFunction fn = FUNCTION_MAP.get(f);
            if (fn != null) return fn.apply(param);
        }
        throw new JsonDslException("不支持的内置函数: " + func + " 参数: " + param);
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

    /**
     * 从上下文中获取值，支持指定键名或使用默认键
     * @param param 上下文键名（如 "i", "j", "depth" 等）或 null（使用默认键）
     * @return 上下文中的值
     */
    public static Object context(Object param) {
        // 这里需要从上下文中获取值，但当前函数没有上下文参数
        // 实际使用时需要通过 TemplateValueResolver 来处理
        throw new JsonDslException("$CONTEXT 函数需要在上下文中使用，不能直接调用");
    }
} 