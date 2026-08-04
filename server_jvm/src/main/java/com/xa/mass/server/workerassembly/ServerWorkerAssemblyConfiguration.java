package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.scenarioworkers.ScenarioWorkerBundle;
import com.xa.mass.scenarioworkers.ScenarioWorkerBundleConfig;
import com.xa.mass.scenarioworkers.ScenarioWorkerBundles;
import com.xa.mass.server.workerassembly.ServerWorkerAssemblyProperties
        .BundleProperties;
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
            WorkerRuntime workerRuntime,
            WorkerPropertyIndexRuntime propertyIndex
    ) {
        List<ScenarioWorkerBundle> bundles = new ArrayList<>();
        properties.bundles().forEach((bundleId, bundle) -> {
            URI websocketUri = requireWebSocketAdapter(
                    bundleId,
                    bundle,
                    adapterManager
            );
            ScenarioWorkerBundleConfig config =
                    scenarioWorkerBundleConfig(
                            bundleId,
                            bundle,
                            websocketUri
                    );
            switch (bundle.type()) {
                case PHONE_NUMBER -> bundles.add(
                        ScenarioWorkerBundles.phoneNumber(
                                config,
                                workerCatalog,
                                workerRuntime,
                                propertyIndex
                        )
                );
                case STRING_UTILS -> bundles.add(
                        ScenarioWorkerBundles.stringUtils(
                                config,
                                workerCatalog,
                                workerRuntime,
                                propertyIndex
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

    private static ScenarioWorkerBundleConfig
    scenarioWorkerBundleConfig(
            String bundleId,
            BundleProperties bundle,
            URI websocketUri
    ) {
        return new ScenarioWorkerBundleConfig(
                bundleId,
                bundle.adapterId(),
                websocketUri,
                bundle.workerGroupId(),
                bundle.workerIdPrefix(),
                bundle.workerCount(),
                bundle.requestTimeout(),
                bundle.reconnectInterval(),
                bundle.connectTimeout()
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
