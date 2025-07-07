package com.xa.mass.base.jsondsl.builtin;

import com.ql.util.express.ExpressRunner;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@FunctionalInterface
interface BuiltinFunction {
    Object apply(Object param);
}

/**
 * 内置函数集合，提供常用的 mock 数据生成功能。
 *
 * 新标准提供更丰富的表达式引擎支持和内置函数扩展机制。
 *
 * <p><b>常用 DSL 配置示例：</b></p>
 * <pre>
 * // 生成指定时间范围内的随机 LocalDateTime
 * "updateTime": {"$TIME_RANGE": ["now-2h", "now", "MINUTES"]}
 *
 * // 生成指定时间范围内的随机时间字符串（指定格式）
 * "updateTime": {"$TIME_RANGE": ["2024-07-01 00:00:00", "2024-07-02 00:00:00", "MINUTES", "yyyy-MM-dd HH:mm:ss"]}
 *
 * // 结合 now±偏移，返回格式化字符串
 * "updateTime": {"$TIME_RANGE": ["now-1d", "now", "HOURS", "yyyy-MM-dd HH:mm"]}
 * </pre>
 *
 * <b>参数说明：</b>
 * <ul>
 *   <li>第1个参数：开始时间（支持 yyyy-MM-dd HH:mm:ss、now、now-1d、now+2h 等）</li>
 *   <li>第2个参数：结束时间（同上）</li>
 *   <li>第3个参数：时间单位（DAYS, HOURS, MINUTES, SECONDS，当前实现主要以秒为粒度，单位参数可忽略）</li>
 *   <li>第4个参数（可选）：格式化字符串（如 "yyyy-MM-dd HH:mm:ss"），不填则返回 LocalDateTime 对象</li>
 * </ul>
 */
public class BuiltinFunctions {
    private static final Random RANDOM = new Random();
    // 移除 FUNCTION_MAP、本地注册和 eval 逻辑，所有注册和查找统一走 OperatorRegistry

    private static int toInt(Object obj) {
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Double) return ((Double) obj).intValue();
        if (obj instanceof Float) return ((Float) obj).intValue();
        if (obj instanceof Long) return ((Long) obj).intValue();
        if (obj instanceof String) return Integer.parseInt((String) obj);
        throw new JsonDslException("$RANDOM_INT 参数类型不支持: " + obj);
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

    /**
     * 获取当前时间
     * @param param 格式化字符串（可选），如 "yyyy-MM-dd HH:mm:ss"
     * @return 当前时间的字符串表示或 LocalDateTime 对象
     */
    public static Object now(Object param) {
        LocalDateTime now = LocalDateTime.now();
        if (param instanceof String format) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                return now.format(formatter);
            } catch (Exception e) {
                throw new JsonDslException("时间格式化失败: " + format, e);
            }
        }
        return now;
    }

    /**
     * 在时间范围内随机生成时间
     * <p>
     * DSL 示例：
     * <pre>
     *   // 生成 LocalDateTime
     *   "updateTime": {"$TIME_RANGE": ["now-2h", "now", "MINUTES"]}
     *   // 生成格式化字符串
     *   "updateTime": {"$TIME_RANGE": ["2024-07-01 00:00:00", "2024-07-02 00:00:00", "MINUTES", "yyyy-MM-dd HH:mm:ss"]}
     * </pre>
     * @param param 时间范围参数，格式为 [开始时间, 结束时间, 时间单位, 格式化字符串(可选)]
     *              时间单位支持: DAYS, HOURS, MINUTES, SECONDS（当前实现主要以秒为粒度，单位参数可忽略）
     * @return 随机时间（LocalDateTime 或格式化字符串）
     */
    public static Object timeRange(Object param) {
        if (!(param instanceof List<?> list) || list.size() < 3) {
            throw new JsonDslException("$TIME_RANGE 需要至少3个参数: [开始时间, 结束时间, 时间单位, 格式化字符串(可选)]");
        }

        try {
            LocalDateTime startTime = parseDateTime(list.get(0));
            LocalDateTime endTime = parseDateTime(list.get(1));
            String unitStr = list.get(2).toString().toUpperCase();

            if (startTime.isAfter(endTime)) {
                throw new JsonDslException("开始时间不能晚于结束时间");
            }

            // 计算时间差
            long totalSeconds = ChronoUnit.SECONDS.between(startTime, endTime);
            if (totalSeconds <= 0) {
                return startTime;
            }

            // 生成随机秒数
            long randomSeconds = RANDOM.nextInt((int) totalSeconds);
            LocalDateTime randomTime = startTime.plusSeconds(randomSeconds);

            // 格式化输出
            if (list.size() > 3 && list.get(3) instanceof String format) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                    return randomTime.format(formatter);
                } catch (Exception e) {
                    throw new JsonDslException("时间格式化失败: " + format, e);
                }
            }
            return randomTime;
        } catch (Exception e) {
            throw new JsonDslException("$TIME_RANGE 解析失败", e);
        }
    }

    private static LocalDateTime parseDateTime(Object timeParam) {
        if (timeParam instanceof LocalDateTime ldt) return ldt;
        if (timeParam instanceof String str) {
            if (str.startsWith("now")) {
                return parseRelativeTime(str);
            }
            try {
                return LocalDateTime.parse(str, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                throw new JsonDslException("时间格式不支持: " + str, e);
            }
        }
        throw new JsonDslException("不支持的时间参数: " + timeParam);
    }

    private static LocalDateTime parseRelativeTime(String timeStr) {
        LocalDateTime now = LocalDateTime.now();
        if ("now".equals(timeStr)) return now;
        try {
            int sign = timeStr.contains("-") ? -1 : 1;
            String[] parts = timeStr.split("[+-]");
            if (parts.length != 2) throw new JsonDslException("相对时间格式错误: " + timeStr);
            String base = parts[0];
            String offset = parts[1];
            int num = Integer.parseInt(offset.replaceAll("[a-zA-Z]+", ""));
            String unit = offset.replaceAll("[0-9]+", "").toUpperCase();
            switch (unit) {
                case "D":
                case "DAY":
                case "DAYS":
                    return now.plusDays(sign * num);
                case "H":
                case "HOUR":
                case "HOURS":
                    return now.plusHours(sign * num);
                case "M":
                case "MIN":
                case "MINUTE":
                case "MINUTES":
                    return now.plusMinutes(sign * num);
                default:
                    throw new JsonDslException("不支持的时间单位: " + unit);
            }
        } catch (Exception e) {
            throw new JsonDslException("相对时间格式错误: " + timeStr, e);
        }
    }

    public static void registerToQLExpress(ExpressRunner runner) {
        // 兼容旧接口，推荐直接用 OperatorRegistry
        OperatorRegistry.registerAllToQLExpress(runner);
    }

    static {
        // 所有函数注册到 OperatorRegistry
        OperatorRegistry.registerFunction("$range", (args, ctx) -> {
            if (args.length == 2 && args[0] instanceof Number && args[1] instanceof Number) {
                int min = ((Number) args[0]).intValue();
                int max = ((Number) args[1]).intValue();
                if (min > max) return min;
                return min + new Random().nextInt(max - min + 1);
            }
            return null;
        });
        OperatorRegistry.registerFunction("$choice", (args, ctx) -> {
            // 支持 $CHOICE: ["ONLINE", "OFFLINE"] 或 $CHOICE: "ONLINE"
            if (args.length == 1) {
                Object arg = args[0];
                if (arg instanceof Collection<?> col) {
                    List<?> list = new ArrayList<>(col);
                    if (list.isEmpty()) return null;
                    Random r = new Random();
                    return list.get(r.nextInt(list.size()));
                } else {
                    return arg; // 直接返回单个值
                }
            }
            // 多参数时，等价于 $CHOICE: [a, b, c]
            if (args.length > 1) {
                Random r = new Random();
                return args[r.nextInt(args.length)];
            }
            return null;
        });
        OperatorRegistry.registerFunction("$join", (args, ctx) -> {
            // 拼接所有参数为字符串
            return Arrays.stream(args).map(String::valueOf).collect(Collectors.joining());
        });
        // 你可以继续注册更多函数
    }
} 