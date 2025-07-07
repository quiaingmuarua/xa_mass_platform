package com.xa.mass.base.jsondsl.processor;

import java.util.List;

public class FilterResult<T> {
    private final List<T> passed;
    private final List<FilterReport.FilterFail<T>> failed;
    private final int total;
    private final double rejectRate;

    public FilterResult(List<T> passed, List<FilterReport.FilterFail<T>> failed, int total) {
        this.passed = passed;
        this.failed = failed;
        this.total = total;
        this.rejectRate = total == 0 ? 0.0 : (double) (failed == null ? 0 : failed.size()) / total;
    }
    public List<T> getPassed() { return passed; }
    public List<FilterReport.FilterFail<T>> getFailed() { return failed; }
    public int getTotal() { return total; }
    public double getRejectRate() { return rejectRate; }
} 