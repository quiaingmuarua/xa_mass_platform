package com.xa.mass.server.control;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ControlCallConfiguration {

    @Bean(destroyMethod = "close")
    ControlCallRegistry controlCallRegistry(
            ControlCallProperties properties
    ) {
        return new ControlCallRegistry(properties);
    }

    @Bean
    ControlCallService controlCallService(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScores,
            WorkerBindingService workerBindings,
            WorkerEndpointDirectory endpoints,
            ControlCallRegistry registry,
            ControlCallProperties properties
    ) {
        return new ControlCallService(
                workerCatalog,
                workerScores,
                workerBindings,
                endpoints,
                registry,
                properties
        );
    }
}
