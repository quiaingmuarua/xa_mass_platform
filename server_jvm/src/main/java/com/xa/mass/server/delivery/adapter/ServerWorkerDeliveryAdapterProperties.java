package com.xa.mass.server.delivery.adapter;

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
        @DefaultValue HttpClientProperties httpClient,
        @DefaultValue Map<String, Map<String, Object>> instances
) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ServerWorkerDeliveryAdapterProperties {
        if (httpClient == null) {
            throw new IllegalArgumentException(
                    "Adapter HTTP client config must be present"
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

    public record HttpClientProperties(
            @DefaultValue("http://127.0.0.1:18082") URI baseUrl,
            @DefaultValue("5s") Duration requestTimeout
    ) {

        public HttpClientProperties {
            if (baseUrl == null
                    || !baseUrl.isAbsolute()
                    || baseUrl.getHost() == null
                    || baseUrl.getRawQuery() != null
                    || baseUrl.getRawFragment() != null
                    || !isHttp(baseUrl)) {
                throw new IllegalArgumentException(
                    "http-client.base-url must be an absolute HTTP(S) URI "
                            + "without query or fragment"
                );
            }
            if (requestTimeout == null
                    || requestTimeout.isZero()
                    || requestTimeout.isNegative()) {
                throw new IllegalArgumentException(
                    "http-client.request-timeout must be positive"
                );
            }
        }

        private static boolean isHttp(URI value) {
            return "http".equalsIgnoreCase(value.getScheme())
                    || "https".equalsIgnoreCase(value.getScheme());
        }
    }
}
