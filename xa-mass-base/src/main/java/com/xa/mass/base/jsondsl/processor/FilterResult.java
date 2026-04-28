package com.xa.mass.base.jsondsl.processor;

import java.util.List;

/**
 * Filter result for either a single item or a batch.
 *
 * @param <T> input item type
 */
public class FilterResult<T> {
    private final List<T> passed;
    private final List<FilterFailure<T>> failed;
    private final int total;
    private final double rejectRate;

    public FilterResult(List<T> passed, List<FilterFailure<T>> failed, int total) {
        this.passed = passed != null ? passed : List.of();
        this.failed = failed != null ? failed : List.of();
        this.total = total;
        this.rejectRate = total == 0 ? 0.0 : (double) this.failed.size() / total;
    }

    public static <T> FilterResult<T> of(T data, boolean passed, List<String> failureReasons) {
        if (passed) {
            return new FilterResult<>(List.of(data), null, 1);
        }
        return new FilterResult<>(List.of(), List.of(new FilterFailure<>(data, failureReasons)), 1);
    }

    public static <T> FilterResult<T> of(List<T> passed, List<FilterFailure<T>> failed) {
        int total = (passed != null ? passed.size() : 0) + (failed != null ? failed.size() : 0);
        return new FilterResult<>(passed, failed, total);
    }

    public List<T> getPassed() {
        return passed;
    }

    public List<FilterFailure<T>> getFailed() {
        return failed;
    }

    public int getTotal() {
        return total;
    }

    public double getRejectRate() {
        return rejectRate;
    }

    public boolean isAllPassed() {
        return failed.isEmpty();
    }

    public boolean isAllFailed() {
        return passed.isEmpty();
    }

    public int getPassedCount() {
        return passed.size();
    }

    public int getFailedCount() {
        return failed.size();
    }

    public static class FilterFailure<T> {
        private final T data;
        private final List<String> reasons;

        public FilterFailure(T data, List<String> reasons) {
            this.data = data;
            this.reasons = reasons != null ? reasons : List.of();
        }

        public T getData() {
            return data;
        }

        public List<String> getReasons() {
            return reasons;
        }

        public String getMainReason() {
            return reasons.isEmpty() ? "unknown reason" : reasons.get(0);
        }

        @Override
        public String toString() {
            return "FilterFailure{data=" + data + ", reasons=" + reasons + "}";
        }
    }
}
