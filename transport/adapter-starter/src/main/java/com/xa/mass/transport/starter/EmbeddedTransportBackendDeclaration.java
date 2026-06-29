package com.xa.mass.transport.starter;

import java.util.Objects;

/**
 * Backend declaration for embedded transport runtime primitives.
 */
public final class EmbeddedTransportBackendDeclaration {

    public static final String DEFAULT_REDIS_DISPATCH_NAMESPACE = "xa:mass:transport:dispatch:v1";
    public static final String DEFAULT_REDIS_ENDPOINT_LEASE_NAMESPACE = "xa:mass:transport:endpoint-lease:v1";
    public static final String DEFAULT_REDIS_RESULT_INGRESS_NAMESPACE = "xa:mass:transport:result-ingress:v1";
    public static final String DEFAULT_REDIS_POLLING_DELIVERY_NAMESPACE = "xa:mass:transport:polling-delivery:v1";
    public static final int DEFAULT_MAX_DISPATCH_ITEMS_PER_QUEUE = 100_000;
    public static final int DEFAULT_MAX_RESULT_INGRESS_ITEMS = 100_000;

    private final String dispatchRedisUri;
    private final String dispatchNamespace;
    private final int maxDispatchItemsPerQueue;
    private final String resultIngressRedisUri;
    private final String resultIngressNamespace;
    private final int maxResultIngressItems;
    private final String endpointLeaseRedisUri;
    private final String endpointLeaseNamespace;
    private final long endpointLeaseMillis;
    private final String pollingDeliveryRedisUri;
    private final String pollingDeliveryNamespace;
    private final int maxPollingPendingDeliveryItems;
    private final int maxPollingPendingDeliveryItemsPerWorker;

    private EmbeddedTransportBackendDeclaration(Builder builder) {
        this.dispatchRedisUri = normalizeOptional(builder.dispatchRedisUri);
        this.dispatchNamespace = requireNamespace(builder.dispatchNamespace, "dispatchNamespace");
        this.maxDispatchItemsPerQueue = requirePositive(
                builder.maxDispatchItemsPerQueue,
                "maxDispatchItemsPerQueue"
        );
        this.resultIngressRedisUri = normalizeOptional(builder.resultIngressRedisUri);
        this.resultIngressNamespace = requireNamespace(builder.resultIngressNamespace, "resultIngressNamespace");
        this.maxResultIngressItems = requirePositive(builder.maxResultIngressItems, "maxResultIngressItems");
        this.endpointLeaseRedisUri = normalizeOptional(builder.endpointLeaseRedisUri);
        this.endpointLeaseNamespace = requireNamespace(builder.endpointLeaseNamespace, "endpointLeaseNamespace");
        if (builder.endpointLeaseMillis <= 0L) {
            throw new IllegalArgumentException("endpointLeaseMillis must be greater than 0");
        }
        this.endpointLeaseMillis = builder.endpointLeaseMillis;
        this.pollingDeliveryRedisUri = normalizeOptional(builder.pollingDeliveryRedisUri);
        this.pollingDeliveryNamespace = requireNamespace(builder.pollingDeliveryNamespace, "pollingDeliveryNamespace");
        this.maxPollingPendingDeliveryItems = requirePositive(
                builder.maxPollingPendingDeliveryItems,
                "maxPollingPendingDeliveryItems"
        );
        this.maxPollingPendingDeliveryItemsPerWorker = requirePositive(
                builder.maxPollingPendingDeliveryItemsPerWorker,
                "maxPollingPendingDeliveryItemsPerWorker"
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EmbeddedTransportBackendDeclaration memory(int maxPollingPendingDeliveryItems,
                                                            int maxPollingPendingDeliveryItemsPerWorker,
                                                            long endpointLeaseMillis) {
        return builder()
                .maxPollingPendingDeliveryItems(maxPollingPendingDeliveryItems)
                .maxPollingPendingDeliveryItemsPerWorker(maxPollingPendingDeliveryItemsPerWorker)
                .endpointLeaseMillis(endpointLeaseMillis)
                .build();
    }

    public String dispatchRedisUri() {
        return dispatchRedisUri;
    }

    public String dispatchNamespace() {
        return dispatchNamespace;
    }

    public int maxDispatchItemsPerQueue() {
        return maxDispatchItemsPerQueue;
    }

    public String resultIngressRedisUri() {
        return resultIngressRedisUri;
    }

    public String resultIngressNamespace() {
        return resultIngressNamespace;
    }

    public int maxResultIngressItems() {
        return maxResultIngressItems;
    }

    public String endpointLeaseRedisUri() {
        return endpointLeaseRedisUri;
    }

    public String endpointLeaseNamespace() {
        return endpointLeaseNamespace;
    }

    public long endpointLeaseMillis() {
        return endpointLeaseMillis;
    }

    public String pollingDeliveryRedisUri() {
        return pollingDeliveryRedisUri;
    }

    public String pollingDeliveryNamespace() {
        return pollingDeliveryNamespace;
    }

    public int maxPollingPendingDeliveryItems() {
        return maxPollingPendingDeliveryItems;
    }

    public int maxPollingPendingDeliveryItemsPerWorker() {
        return maxPollingPendingDeliveryItemsPerWorker;
    }

    public boolean hasDispatchRedis() {
        return dispatchRedisUri != null;
    }

    public boolean hasResultIngressRedis() {
        return resultIngressRedisUri != null;
    }

    public boolean hasEndpointLeaseRedis() {
        return endpointLeaseRedisUri != null;
    }

    public boolean hasPollingDeliveryRedis() {
        return pollingDeliveryRedisUri != null;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String dispatchRedisUri;
        private String dispatchNamespace = DEFAULT_REDIS_DISPATCH_NAMESPACE;
        private int maxDispatchItemsPerQueue = DEFAULT_MAX_DISPATCH_ITEMS_PER_QUEUE;
        private String resultIngressRedisUri;
        private String resultIngressNamespace = DEFAULT_REDIS_RESULT_INGRESS_NAMESPACE;
        private int maxResultIngressItems = DEFAULT_MAX_RESULT_INGRESS_ITEMS;
        private String endpointLeaseRedisUri;
        private String endpointLeaseNamespace = DEFAULT_REDIS_ENDPOINT_LEASE_NAMESPACE;
        private long endpointLeaseMillis = 30_000L;
        private String pollingDeliveryRedisUri;
        private String pollingDeliveryNamespace = DEFAULT_REDIS_POLLING_DELIVERY_NAMESPACE;
        private int maxPollingPendingDeliveryItems = 100_000;
        private int maxPollingPendingDeliveryItemsPerWorker = 10_000;

        private Builder() {
        }

        private Builder(EmbeddedTransportBackendDeclaration source) {
            Objects.requireNonNull(source, "source");
            this.dispatchRedisUri = source.dispatchRedisUri;
            this.dispatchNamespace = source.dispatchNamespace;
            this.maxDispatchItemsPerQueue = source.maxDispatchItemsPerQueue;
            this.resultIngressRedisUri = source.resultIngressRedisUri;
            this.resultIngressNamespace = source.resultIngressNamespace;
            this.maxResultIngressItems = source.maxResultIngressItems;
            this.endpointLeaseRedisUri = source.endpointLeaseRedisUri;
            this.endpointLeaseNamespace = source.endpointLeaseNamespace;
            this.endpointLeaseMillis = source.endpointLeaseMillis;
            this.pollingDeliveryRedisUri = source.pollingDeliveryRedisUri;
            this.pollingDeliveryNamespace = source.pollingDeliveryNamespace;
            this.maxPollingPendingDeliveryItems = source.maxPollingPendingDeliveryItems;
            this.maxPollingPendingDeliveryItemsPerWorker = source.maxPollingPendingDeliveryItemsPerWorker;
        }

        public Builder dispatchRedis(String redisUri, String namespace) {
            this.dispatchRedisUri = requireRedisUri(redisUri);
            this.dispatchNamespace = requireNamespace(namespace, "dispatchNamespace");
            return this;
        }

        public Builder resultIngressRedis(String redisUri, String namespace) {
            this.resultIngressRedisUri = requireRedisUri(redisUri);
            this.resultIngressNamespace = requireNamespace(namespace, "resultIngressNamespace");
            return this;
        }

        public Builder endpointLeaseRedis(String redisUri, String namespace) {
            this.endpointLeaseRedisUri = requireRedisUri(redisUri);
            this.endpointLeaseNamespace = requireNamespace(namespace, "endpointLeaseNamespace");
            return this;
        }

        public Builder pollingDeliveryRedis(String redisUri, String namespace) {
            this.pollingDeliveryRedisUri = requireRedisUri(redisUri);
            this.pollingDeliveryNamespace = requireNamespace(namespace, "pollingDeliveryNamespace");
            return this;
        }

        public Builder maxPollingPendingDeliveryItems(int maxPollingPendingDeliveryItems) {
            this.maxPollingPendingDeliveryItems = maxPollingPendingDeliveryItems;
            return this;
        }

        public Builder maxPollingPendingDeliveryItemsPerWorker(int maxPollingPendingDeliveryItemsPerWorker) {
            this.maxPollingPendingDeliveryItemsPerWorker = maxPollingPendingDeliveryItemsPerWorker;
            return this;
        }

        public Builder endpointLeaseMillis(long endpointLeaseMillis) {
            this.endpointLeaseMillis = endpointLeaseMillis;
            return this;
        }

        public EmbeddedTransportBackendDeclaration build() {
            return new EmbeddedTransportBackendDeclaration(this);
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String requireRedisUri(String redisUri) {
        if (redisUri == null || redisUri.isBlank()) {
            throw new IllegalArgumentException("redisUri must not be blank");
        }
        return redisUri.trim();
    }

    private static String requireNamespace(String namespace, String fieldName) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return namespace.trim();
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }
}
