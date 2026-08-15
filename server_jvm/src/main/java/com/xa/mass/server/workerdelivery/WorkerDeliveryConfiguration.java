package com.xa.mass.server.workerdelivery;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.control.ControlCallService;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
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
            WorkerResultRuntime resultRuntime,
            WorkerBindingService bindings,
            ControlCallService controlCalls
    ) {
        return new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings,
                controlCalls
        );
    }
}
