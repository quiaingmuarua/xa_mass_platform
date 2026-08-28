package com.xa.mass.kernel.pacer.dispatch;

import java.util.List;

@FunctionalInterface
interface DispatchBatchPolicy {
    void handle(List<DueTaskObservation> batch);
}
