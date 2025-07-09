package com.xa.mass.base.jsondsl.processor;

import java.util.List;

/**
 * 过滤结果类，支持单对象和列表过滤
 *
 * @param <T> 数据类型
 */
public class FilterResult<T> {
    private final List<T> passed;
    private final List<FilterFailure<T>> failed;
    private final int total;
    private final double rejectRate;

    /**
     * 构造函数
     *
     * @param passed 通过过滤的对象列表
     * @param failed 过滤失败的对象列表
     * @param total 总对象数量
     */
    public FilterResult(List<T> passed, List<FilterFailure<T>> failed, int total) {
        this.passed = passed != null ? passed : List.of();
        this.failed = failed != null ? failed : List.of();
        this.total = total;
        this.rejectRate = total == 0 ? 0.0 : (double) this.failed.size() / total;
    }

    /**
     * 创建单对象过滤结果
     *
     * @param data 过滤的对象
     * @param passed 是否通过
     * @param failureReasons 失败原因（如果未通过）
     * @return 过滤结果
     */
    public static <T> FilterResult<T> of(T data, boolean passed, List<String> failureReasons) {
        if (passed) {
            return new FilterResult<>(List.of(data), null, 1);
        } else {
            return new FilterResult<>(List.of(), List.of(new FilterFailure<>(data, failureReasons)), 1);
        }
    }

    /**
     * 创建列表过滤结果
     *
     * @param passed 通过的对象列表
     * @param failed 失败的对象列表
     * @return 过滤结果
     */
    public static <T> FilterResult<T> of(List<T> passed, List<FilterFailure<T>> failed) {
        int total = (passed != null ? passed.size() : 0) + (failed != null ? failed.size() : 0);
        return new FilterResult<>(passed, failed, total);
    }

    /**
     * 获取通过过滤的对象列表
     */
    public List<T> getPassed() {
        return passed;
    }

    /**
     * 获取过滤失败的对象列表
     */
    public List<FilterFailure<T>> getFailed() {
        return failed;
    }

    /**
     * 获取总对象数量
     */
    public int getTotal() {
        return total;
    }

    /**
     * 获取拒绝率
     */
    public double getRejectRate() {
        return rejectRate;
    }

    /**
     * 检查是否所有对象都通过过滤
     */
    public boolean isAllPassed() {
        return failed.isEmpty();
    }

    /**
     * 检查是否所有对象都未通过过滤
     */
    public boolean isAllFailed() {
        return passed.isEmpty();
    }

    /**
     * 获取通过的对象数量
     */
    public int getPassedCount() {
        return passed.size();
    }

    /**
     * 获取失败的对象数量
     */
    public int getFailedCount() {
        return failed.size();
    }

    /**
     * 过滤失败信息
     *
     * @param <T> 数据类型
     */
    public static class FilterFailure<T> {
        private final T data;
        private final List<String> reasons;

        public FilterFailure(T data, List<String> reasons) {
            this.data = data;
            this.reasons = reasons != null ? reasons : List.of();
        }

        /**
         * 获取失败的对象
         */
        public T getData() {
            return data;
        }

        /**
         * 获取失败原因列表
         */
        public List<String> getReasons() {
            return reasons;
        }

        /**
         * 获取主要失败原因（第一个）
         */
        public String getMainReason() {
            return reasons.isEmpty() ? "未知原因" : reasons.get(0);
        }

        @Override
        public String toString() {
            return "FilterFailure{data=" + data + ", reasons=" + reasons + "}";
        }
    }
} 