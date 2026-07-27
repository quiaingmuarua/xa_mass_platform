package com.xa.mass.workerdelivery.adapter.standalone;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(WorkerDeliveryAdapterStandaloneConfiguration.class)
public class WorkerDeliveryAdapterApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(
                WorkerDeliveryAdapterApplication.class
        );
        application.setDefaultProperties(Map.of(
                "server.port", "18083",
                "xa.mass.worker-delivery.adapter.websocket.enabled",
                "true",
                "xa.mass.worker-delivery.adapter.websocket."
                        + "gateway-base-url",
                "http://127.0.0.1:18082",
                "management.endpoints.web.exposure.include",
                "health",
                "management.endpoint.health.probes.enabled",
                "true",
                "management.endpoint.health.group.readiness.include",
                "readinessState"
        ));
        application.run(args);
    }
}
