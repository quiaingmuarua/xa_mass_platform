package com.xa.mass.server.delivery.directcall;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DirectCallConfiguration {

    @Bean(destroyMethod = "close")
    DirectCallRegistry directCallRegistry(
            DirectCallProperties properties
    ) {
        return new DirectCallRegistry(properties);
    }

    @Bean
    DirectCallService directCallService(
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerBindingService workerBindings,
            WorkerEndpointDirectory endpoints,
            DirectCallRegistry registry,
            DirectCallProperties properties
    ) {
        return new DirectCallService(
                workerCatalog,
                workerCommands,
                workerBindings,
                endpoints,
                registry,
                properties
        );
    }
}
