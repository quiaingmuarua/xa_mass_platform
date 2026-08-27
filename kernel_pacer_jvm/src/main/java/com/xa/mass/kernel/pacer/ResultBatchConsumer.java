package com.xa.mass.kernel.pacer;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.List;

@FunctionalInterface
interface ResultBatchConsumer {

    List<DeliveryReport> consume(int limit);
}
