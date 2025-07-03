package com.xa.mass.base.jsondsl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@FunctionalInterface
interface BuiltinFunction {
    Object apply(Object param);
}

/**
 * 内置 mock 生成函数注册表，支持 $CHOICE, $RANGE, $UUID, $RANDOM, $JOIN, $CONTEXT, $NOW, $TIME_RANGE。
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
        FUNCTION_MAP.put(BuiltinFunc.CONTEXT, BuiltinFunctions::context);
        FUNCTION_MAP.put(BuiltinFunc.NOW, BuiltinFunctions::now);
        FUNCTION_MAP.put(BuiltinFunc.TIME_RANGE, BuiltinFunctions::timeRange);
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
     * @param param 时间范围参数，格式为 [开始时间, 结束时间, 时间单位, 格式化字符串(可选)]
     *              时间单位支持: DAYS, HOURS, MINUTES, SECONDS
     * @return 随机时间
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
} 