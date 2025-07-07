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
    // 统一注册表，key 小写
    private static final Map<String, BuiltinFunction> FUNCTION_MAP = new HashMap<>();

    static {
        registerFunction("$choice", param -> choice((List<?>) param));
        registerFunction("$range", param -> {
            List<?> list = (List<?>) param;
            return range(((Number) list.get(0)).intValue(), ((Number) list.get(1)).intValue());
        });
        registerFunction("$uuid", param -> uuid());
        registerFunction("$random", param -> random());
        registerFunction("$join", param -> join((List<?>) param));
        registerFunction("$context", BuiltinFunctions::context);
        registerFunction("$now", BuiltinFunctions::now);
        registerFunction("$time_range", BuiltinFunctions::timeRange);
    }

    public static void registerFunction(String op, BuiltinFunction func) {
        if (op == null) return;
        String key = op.toLowerCase();
        FUNCTION_MAP.put(key, func);
        // 自动注册无 $ 前缀的别名
        if (key.startsWith("$")) {
            FUNCTION_MAP.put(key.substring(1), func);
        }
    }

    private static int toInt(Object obj) {
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Double) return ((Double) obj).intValue();
        if (obj instanceof Float) return ((Float) obj).intValue();
        if (obj instanceof Long) return ((Long) obj).intValue();
        if (obj instanceof String) return Integer.parseInt((String) obj);
        throw new JsonDslException("$RANDOM_INT 参数类型不支持: " + obj);
    }

    public static Object eval(String func, Object param) {
        if (func == null) throw new JsonDslException("函数名不能为空");
        String key = func.toLowerCase();
        if ("$random_int".equals(key) || "randomint".equals(key)) {
            if (!(param instanceof List<?> list) || list.size() < 2) {
                throw new JsonDslException("$RANDOM_INT 需要2个参数: [min, max]");
            }
            int min = toInt(list.get(0));
            int max = toInt(list.get(1));
            return range(min, max);
        }
        BuiltinFunction fn = FUNCTION_MAP.get(key);
        if (fn != null) return fn.apply(param);
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
            throw new JsonDslException("时间范围解析失败", e);
        }
    }

    /**
     * 解析时间参数，支持多种格式
     * @param timeParam 时间参数
     * @return LocalDateTime 对象
     */
    private static LocalDateTime parseDateTime(Object timeParam) {
        if (timeParam instanceof LocalDateTime) {
            return (LocalDateTime) timeParam;
        }

        if (timeParam instanceof String timeStr) {
            // 尝试多种时间格式
            String[] formats = {
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd HH:mm",
                    "yyyy-MM-dd",
                    "HH:mm:ss",
                    "HH:mm"
            };

            for (String format : formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                    if (format.length() == timeStr.length()) {
                        return LocalDateTime.parse(timeStr, formatter);
                    }
                } catch (Exception ignored) {
                    // 继续尝试下一个格式
                }
            }

            // 如果是相对时间，如 "now", "now-1d", "now+2h"
            if (timeStr.startsWith("now")) {
                return parseRelativeTime(timeStr);
            }
        }

        throw new JsonDslException("无法解析时间参数: " + timeParam);
    }

    /**
     * 解析相对时间，如 "now-1d", "now+2h"
     * @param timeStr 相对时间字符串
     * @return LocalDateTime 对象
     */
    private static LocalDateTime parseRelativeTime(String timeStr) {
        LocalDateTime now = LocalDateTime.now();

        if (timeStr.equals("now")) {
            return now;
        }

        if (timeStr.startsWith("now")) {
            String offset = timeStr.substring(3);
            if (offset.startsWith("+") || offset.startsWith("-")) {
                char sign = offset.charAt(0);
                String value = offset.substring(1);

                // 解析数值和单位
                for (int i = 0; i < value.length(); i++) {
                    if (!Character.isDigit(value.charAt(i))) {
                        String numStr = value.substring(0, i);
                        String unit = value.substring(i).toLowerCase();

                        if (numStr.isEmpty()) {
                            throw new JsonDslException("无效的相对时间格式: " + timeStr);
                        }

                        int num = Integer.parseInt(numStr);
                        if (sign == '-') {
                            num = -num;
                        }

                        switch (unit) {
                            case "d":
                            case "day":
                            case "days":
                                return now.plusDays(num);
                            case "h":
                            case "hour":
                            case "hours":
                                return now.plusHours(num);
                            case "m":
                            case "min":
                            case "minute":
                            case "minutes":
                                return now.plusMinutes(num);
                            case "s":
                            case "sec":
                            case "second":
                            case "seconds":
                                return now.plusSeconds(num);
                            default:
                                throw new JsonDslException("不支持的时间单位: " + unit);
                        }
                    }
                }
            }
        }

        throw new JsonDslException("无法解析相对时间: " + timeStr);
    }

    /**
     * 批量注册所有常用内置函数到 QLExpress
     */
    public static void registerToQLExpress(ExpressRunner runner) {
        try {
            // 注册 parseInt(String) 函数
            runner.addFunctionOfClassMethod("parseInt", Integer.class.getName(), "parseInt", new String[] { "String" }, null);
            for (Map.Entry<String, BuiltinFunction> entry : FUNCTION_MAP.entrySet()) {
                String func = entry.getKey();
                BuiltinFunction impl = entry.getValue();
                runner.addFunction(func, new com.ql.util.express.Operator() {
                    @Override
                    public Object executeInner(Object[] list) throws Exception {
                        // 兼容单参数和多参数
                        if (list == null || list.length == 0) return impl.apply(null);
                        if (list.length == 1) return impl.apply(list[0]);
                        return impl.apply(java.util.Arrays.asList(list));
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException("注册 BuiltinFunctions 到 QLExpress 失败", e);
        }
    }
} 