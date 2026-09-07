package com.xa.mass.server.delivery;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import com.xa.mass.server.delivery.application.WorkerDeliveryService;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerDeliveryConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkerDeliveryCodec.class)
    WorkerDeliveryCodec workerDeliveryCodec() {
        return new WorkerDeliveryCodec();
    }

    @Bean
    WorkerDeliveryService workerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            TaskResultRuntime taskResults,
            WorkerResourceCatalog workerCatalog,
            DirectCallService directCalls,
            WorkerServiceabilityRuntime serviceability,
            WorkerMatchingCatalog matchingCatalog
    ) {
        return new WorkerDeliveryService(
                commandRuntime,
                taskResults,
                workerCatalog,
                directCalls,
                serviceability,
                matchingCatalog
        );
    }
}
