package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import java.util.List;
import java.util.Objects;

/** Submits one encoded Report batch without owning its consumption lane. */
public final class DeliveryReportProcess
        implements AdapterBatchProcessor<String> {

    private final WorkerDeliveryRemoteApi remoteApi;
    private final String adapterId;

    public DeliveryReportProcess(
            WorkerDeliveryRemoteApi remoteApi,
            String adapterId
    ) {
        this.remoteApi = Objects.requireNonNull(remoteApi, "remoteApi");
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        this.adapterId = adapterId;
    }

    @Override
    public BatchProcessResult process(List<String> batch) {
        List<String> encodedReports = List.copyOf(batch);
        remoteApi.appendReports(adapterId, encodedReports);
        return BatchProcessResult.completed();
    }
}
