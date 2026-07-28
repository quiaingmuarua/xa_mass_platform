package com.xa.mass.server.workerdelivery.adapter;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ConfigurationProperties(
        prefix = "xa.mass.worker-delivery.adapter",
        ignoreUnknownFields = false
)
public record ServerWorkerDeliveryAdapterProperties(
        @DefaultValue GatewayProperties gateway,
        @DefaultValue Map<String, Map<String, Object>> instances
) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ServerWorkerDeliveryAdapterProperties {
        if (gateway == null) {
            throw new IllegalArgumentException(
                    "Adapter gateway config must be present"
            );
        }
        if (instances == null) {
            instances = Map.of();
        } else {
            LinkedHashMap<String, Map<String, Object>> copy =
                    new LinkedHashMap<>();
            instances.forEach((adapterId, config) ->
                    copy.put(
                            adapterId,
                            config == null
                                    ? Map.of()
                                    : Collections.unmodifiableMap(
                                            new LinkedHashMap<>(config)
                                    )
                    )
            );
            instances = Collections.unmodifiableMap(copy);
        }
    }

    public Map<String, JsonNode> instanceConfigs() {
        LinkedHashMap<String, JsonNode> configs = new LinkedHashMap<>();
        instances.forEach((adapterId, config) ->
                configs.put(adapterId, JSON.valueToTree(config))
        );
        return Collections.unmodifiableMap(configs);
    }

    public record GatewayProperties(
            @DefaultValue("http://127.0.0.1:18082") URI baseUrl,
            @DefaultValue("5s") Duration requestTimeout
    ) {

        public GatewayProperties {
            if (baseUrl == null
                    || !baseUrl.isAbsolute()
                    || baseUrl.getHost() == null
                    || !isHttp(baseUrl)) {
                throw new IllegalArgumentException(
                        "gateway.base-url must be an absolute HTTP(S) URI"
                );
            }
            if (requestTimeout == null
                    || requestTimeout.isZero()
                    || requestTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "gateway.request-timeout must be positive"
                );
            }
        }

        private static boolean isHttp(URI value) {
            return "http".equalsIgnoreCase(value.getScheme())
                    || "https".equalsIgnoreCase(value.getScheme());
        }
    }
}
