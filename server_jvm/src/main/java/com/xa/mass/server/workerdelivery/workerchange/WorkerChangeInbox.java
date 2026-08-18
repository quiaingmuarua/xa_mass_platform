package com.xa.mass.server.workerdelivery.workerchange;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;

/** Server-owned bounded handoff for validated Worker route-change evidence. */
public interface WorkerChangeInbox {

    int MAX_APPEND_BATCH_SIZE = 100;

    int append(List<DeliveryReport> reports);
}
