package com.xa.mass.server.delivery.adapter;

import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerDeliveryAdapterConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerDeliveryAdapterFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        ServerWorkerDeliveryAdapterProperties.class
)
public class ServerWorkerDeliveryAdapterConfiguration {

    @Bean
    WorkerRouteVerificationBatcher workerRouteVerificationBatcher(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerBindingService bindings
    ) {
        return new WorkerRouteVerificationBatcher(
                bindings,
                properties.verificationQueueCapacity(),
                properties.verificationTimeout()
        );
    }

    @Bean
    WorkerDeliveryAdapterManager workerDeliveryAdapterManager(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerEndpointDirectory endpointDirectory,
            WorkerRouteVerificationBatcher routeVerifier
    ) {
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        NettyWorkerDeliveryAdapterFactory factory =
                new NettyWorkerDeliveryAdapterFactory(
                        properties.remoteBaseUrl(),
                        properties.remoteRequestTimeout(),
                        routeVerifier
                );
        properties.instances().forEach((adapterId, config) -> {
            requireMatchingEndpoint(adapterId, config, endpointDirectory);
            manager.register(factory.create(adapterId, config));
        });
        return manager;
    }

    private static void requireMatchingEndpoint(
            String adapterId,
            NettyWorkerDeliveryAdapterConfig config,
            WorkerEndpointDirectory endpointDirectory
    ) {
        WorkerTransportType transportType = switch (config.type()) {
            case WEBSOCKET -> WorkerTransportType.WEBSOCKET;
            case SOCKET -> WorkerTransportType.SOCKET;
        };
        if (!endpointDirectory.contains(adapterId, transportType)) {
            throw new IllegalArgumentException(
                    "Adapter "
                            + adapterId
                            + ": a matching worker-binding endpoint must "
                            + "be configured"
            );
        }
    }
}
