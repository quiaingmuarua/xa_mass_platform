package com.xa.mass.kernel.serviceability;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;
import java.util.Map;

public interface WorkerServiceabilityRuntime {

    Map<String, ProbeRequestOfferStatus> offerProbeRequests(
            String adapterId,
            List<String> workerIds
    );

    List<String> consumeProbeRequests(String adapterId, int limit);

    /**
     * Admits the complete batch with respect to queue capacity.
     *
     * @return {@code reports.size()} when admitted, otherwise {@code 0}
     */
    int appendAdapterEvidenceResults(List<DeliveryReport> reports);

    List<DeliveryReport> consumeAdapterEvidenceResults(int limit);

    enum ProbeRequestOfferStatus {
        OFFERED,
        ALREADY_REQUESTED,
        CAPACITY
    }
}
