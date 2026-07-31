package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.server.workerassembly.ServerWorkerAssemblyProperties
        .BundleProperties;
import com.xa.mass.server.workerassembly.phonenumber
        .PhoneNumberWorkerBundle;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.websocket
        .WebSocketWorkerDeliveryAdapter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties
        .EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerWorkerAssemblyProperties.class)
public class ServerWorkerAssemblyConfiguration {

    private static final int INVALID_ADAPTER = 14001;
    private static final String WORKER_WEBSOCKET_PATH =
            "/api/v1/worker-delivery/websocket";

    @Bean
    ServerWorkerBundleManager serverWorkerBundleManager(
            ServerWorkerAssemblyProperties properties,
            WorkerDeliveryAdapterManager adapterManager,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime
    ) {
        List<PhoneNumberWorkerBundle> bundles = new ArrayList<>();
        properties.bundles().forEach((bundleId, bundle) -> {
            URI websocketUri = requireWebSocketAdapter(
                    bundleId,
                    bundle,
                    adapterManager
            );
            switch (bundle.type()) {
                case PHONE_NUMBER -> bundles.add(
                        new PhoneNumberWorkerBundle(
                                bundleId,
                                bundle,
                                websocketUri,
                                workerCatalog,
                                workerRuntime
                        )
                );
            }
        });
        return new ServerWorkerBundleManager(bundles);
    }

    @Bean
    ServerWorkerAssemblyLifecycleHost
    serverWorkerAssemblyLifecycleHost(
            WorkerDeliveryAdapterManager adapterManager,
            ServerWorkerBundleManager bundleManager
    ) {
        return new ServerWorkerAssemblyLifecycleHost(
                adapterManager,
                bundleManager
        );
    }

    static URI requireWebSocketAdapter(
            String bundleId,
            BundleProperties bundle,
            WorkerDeliveryAdapterManager adapterManager
    ) {
        WorkerDeliveryAdapter adapter;
        try {
            adapter = adapterManager.requireAdapter(bundle.adapterId());
        } catch (RuntimeException error) {
            throw new WorkerAssemblyException(
                    INVALID_ADAPTER,
                    "serverWorkerAssembly.validateAdapter",
                    "Bundle "
                            + bundleId
                            + " requires configured WEBSOCKET Adapter "
                            + bundle.adapterId(),
                    error
            );
        }
        if (!(adapter instanceof WebSocketWorkerDeliveryAdapter websocket)) {
            throw new WorkerAssemblyException(
                    INVALID_ADAPTER,
                    "serverWorkerAssembly.validateAdapter",
                    "Bundle "
                            + bundleId
                            + " requires configured WEBSOCKET Adapter "
                            + bundle.adapterId()
            );
        }
        return URI.create(
                "ws://127.0.0.1:"
                        + websocket.listenPort()
                        + WORKER_WEBSOCKET_PATH
        );
    }
}
