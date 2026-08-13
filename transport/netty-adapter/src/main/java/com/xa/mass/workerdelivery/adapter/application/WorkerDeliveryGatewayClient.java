package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Thread-safe composition boundary for the three remote Worker Delivery
 * owners used by one or more Adapter instances.
 *
 * <p>Callers project this composition root to the one owner port they need;
 * no process or connection mechanism receives the broad client.
 */
public interface WorkerDeliveryGatewayClient {

    CommandSource commandSource();

    ResultIngress resultIngress();

    RouteVerifier routeVerifier();

    interface CommandSource {

        Map<String, DeliveryCommand> consume(
                String endpointManagerId,
                int limit
        );
    }

    interface ResultIngress {

        void ingress(
                String endpointManagerId,
                List<String> encodedDeliveryReports
        );
    }

    interface RouteVerifier {

        CompletionStage<Void> verify(
                String endpointManagerId,
                String workerId
        );
    }
}
