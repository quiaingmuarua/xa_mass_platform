package com.xa.mass.workerpack.tool.geo;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.AdapterNodeSpec;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerResult;
import com.xa.mass.client.worker.session.PollingWorkerSession;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GeoLookupWorkerPack {
    public static final String WORKER_GROUP_ID = "worker-pack.tools.geo";
    public static final String DEFAULT_ADAPTER_TYPE = "polling";

    private GeoLookupWorkerPack() {
    }

    public static Builder builder(MassPlatform platform) {
        return new Builder(platform);
    }

    public static WorkerGroupSpec groupSpec(List<String> projectCodes) {
        return groupSpec(projectCodes, GeoLookupTool.defaultProvider());
    }

    public static WorkerGroupSpec groupSpec(List<String> projectCodes, GeoLookupProvider provider) {
        GeoLookupProvider resolvedProvider = Objects.requireNonNull(provider, "provider is required");
        return WorkerGroupSpec.builder()
                .groupId(WORKER_GROUP_ID)
                .bindEvent(GeoLookupTool.EVENT_CODE, projectCodes == null ? List.of() : List.copyOf(projectCodes))
                .defaultAttribute("capability", GeoLookupTool.EVENT_CODE)
                .defaultAttribute("provider", resolvedProvider.providerId())
                .defaultAttribute("simulated", "true")
                .defaultMaxConcurrentWork(4)
                .build();
    }

    public static WorkerEventHandler handler() {
        return handler(GeoLookupTool.defaultProvider());
    }

    public static WorkerEventHandler handler(GeoLookupProvider provider) {
        GeoLookupProvider resolvedProvider = Objects.requireNonNull(provider, "provider is required");
        return dispatch -> {
            String query = dispatch.input().getString("query")
                    .or(() -> dispatch.input().getString("city"))
                    .orElse("");
            try {
                return WorkerResult.success("geo lookup resolved", GeoLookupTool.lookup(query, resolvedProvider));
            } catch (IllegalArgumentException e) {
                return WorkerResult.failure("INVALID_GEO_QUERY", e.getMessage());
            } catch (GeoLookupProviderException e) {
                return WorkerResult.failure(e.errorCode(), e.getMessage(), Map.of(
                        "query", query,
                        "provider", resolvedProvider.providerId()
                ));
            }
        };
    }

    public static final class Builder {
        private final MassPlatform platform;
        private String workerId;
        private String adapterNodeId;
        private List<String> projectCodes = List.of();
        private GeoLookupProvider provider = GeoLookupTool.defaultProvider();
        private Map<String, String> attributes = defaultAttributes();
        private int maxMessages = 4;
        private Duration pollTimeout = Duration.ofMillis(250);
        private Duration pollInterval = Duration.ofMillis(50);
        private Duration heartbeatInterval = Duration.ofSeconds(5);

        private Builder(MassPlatform platform) {
            this.platform = Objects.requireNonNull(platform, "platform is required");
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes == null ? List.of() : List.copyOf(projectCodes);
            return this;
        }

        public Builder provider(GeoLookupProvider provider) {
            this.provider = Objects.requireNonNull(provider, "provider is required");
            this.attributes.put("provider", provider.providerId());
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? defaultAttributes() : new LinkedHashMap<>(attributes);
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public Builder pollTimeout(Duration pollTimeout) {
            this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout is required");
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval is required");
            return this;
        }

        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval is required");
            return this;
        }

        public PollingWorkerSession startPolling() {
            String resolvedWorkerId = requireText(workerId, "workerId");
            String resolvedAdapterNodeId = requireText(adapterNodeId, "adapterNodeId");
            platform.workers().declareGroup(groupSpec(projectCodes, provider));
            platform.workers().registerAdapterNode(AdapterNodeSpec.builder()
                    .adapterNodeId(resolvedAdapterNodeId)
                    .adapterType(DEFAULT_ADAPTER_TYPE)
                    .endpointId(resolvedAdapterNodeId)
                    .attributes(attributes)
                    .build());
            platform.workers().bindNodeGroup(resolvedAdapterNodeId, WORKER_GROUP_ID);
            return platform.workerSessions().polling()
                    .workerId(resolvedWorkerId)
                    .workerGroupId(WORKER_GROUP_ID)
                    .attributes(attributes)
                    .eventHandler(GeoLookupTool.EVENT_CODE, handler(provider))
                    .maxMessages(maxMessages)
                    .pollTimeout(pollTimeout)
                    .pollInterval(pollInterval)
                    .heartbeatInterval(heartbeatInterval)
                    .start();
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(fieldName + " is required");
            }
            return value.trim();
        }

        private static Map<String, String> defaultAttributes() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("capability", GeoLookupTool.EVENT_CODE);
            values.put("provider", GeoLookupTool.PROVIDER);
            values.put("simulated", "true");
            values.put("routingTags", "global");
            return values;
        }
    }
}
