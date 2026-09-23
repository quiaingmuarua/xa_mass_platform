package com.xa.mass.kernel.pacer.result;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.List;

@FunctionalInterface
interface ResultBatchConsumer {

    List<DeliveryReport> consume(int limit);
}
