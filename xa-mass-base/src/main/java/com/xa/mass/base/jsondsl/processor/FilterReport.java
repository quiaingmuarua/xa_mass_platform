package com.xa.mass.base.jsondsl.processor;

import java.util.List;

public class FilterReport<T> {
    private final List<T> passed;
    private final List<FilterFail<T>> failed;

    public FilterReport(List<T> passed, List<FilterFail<T>> failed) {
        this.passed = passed;
        this.failed = failed;
    }
    public List<T> getPassed() { return passed; }
    public List<FilterFail<T>> getFailed() { return failed; }

    public static class FilterFail<T> {
        private final T object;
        private final List<String> failedConditions;
        public FilterFail(T object, List<String> failedConditions) {
            this.object = object;
            this.failedConditions = failedConditions;
        }
        public T getObject() { return object; }
        public List<String> getFailedConditions() { return failedConditions; }
    }
} 