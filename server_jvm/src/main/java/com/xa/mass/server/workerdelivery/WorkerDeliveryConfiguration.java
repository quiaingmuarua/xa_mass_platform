package com.xa.mass.server.workerdelivery;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.directcall.DirectCallService;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.server.workerdelivery.application.WorkerChangeReportIngress;
import com.xa.mass.server.workerdelivery.workerchange.WorkerChangeInbox;
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
    WorkerChangeReportIngress workerChangeReportIngress(
            WorkerChangeInbox inbox,
            WorkerBindingService bindings
    ) {
        return new WorkerChangeReportIngress(
                inbox,
                bindings
        );
    }

    @Bean
    WorkerDeliveryService workerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            WorkerResultRuntime resultRuntime,
            WorkerBindingService bindings,
            DirectCallService directCalls,
            WorkerChangeReportIngress workerChanges
    ) {
        return new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings,
                directCalls,
                workerChanges
        );
    }
}
