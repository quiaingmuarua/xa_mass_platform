package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;

import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * worker-facing transport channels.
 */
public final class TransportBinding {

    private final String adapterId;
    private final String adapterMailboxKey;
    private final String transportHint;
    private final String protocol;
    private final AdapterCommandExecutor commandExecutor;
    private final DeliveryPullChannel deliveryPullChannel;
    private final PullSessionEvidenceDriver pullSessionEvidenceDriver;

    private TransportBinding(Builder builder) {
        this.adapterId = requireText(builder.adapterId, "adapterId");
        this.adapterMailboxKey = requireText(builder.adapterMailboxKey, "adapterMailboxKey");
        this.transportHint = requireText(builder.transportHint, "transportHint");
        this.protocol = builder.protocol == null || builder.protocol.isBlank()
                ? this.adapterId
                : builder.protocol.trim();
        this.commandExecutor = Objects.requireNonNull(builder.commandExecutor, "commandExecutor");
        this.deliveryPullChannel = builder.deliveryPullChannel;
        this.pullSessionEvidenceDriver = builder.pullSessionEvidenceDriver;
        if (this.deliveryPullChannel != null && this.pullSessionEvidenceDriver == null) {
            throw new IllegalArgumentException("pullSessionEvidenceDriver must be set when deliveryPullChannel is set");
        }
    }

    public static Builder builder(String adapterId,
                                  String transportHint,
                                  AdapterCommandExecutor commandExecutor) {
        return new Builder(adapterId, transportHint, commandExecutor);
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getAdapterMailboxKey() {
        return adapterMailboxKey;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public String getProtocol() {
        return protocol;
    }

    public AdapterCommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    public DeliveryPullChannel getDeliveryPullChannel() {
        return deliveryPullChannel;
    }

    public PullSessionEvidenceDriver getPullSessionEvidenceDriver() {
        return pullSessionEvidenceDriver;
    }

    public static final class Builder {
        private final String adapterId;
        private final String transportHint;
        private final AdapterCommandExecutor commandExecutor;
        private String adapterMailboxKey;
        private String protocol;
        private DeliveryPullChannel deliveryPullChannel;
        private PullSessionEvidenceDriver pullSessionEvidenceDriver;

        private Builder(String adapterId,
                        String transportHint,
                        AdapterCommandExecutor commandExecutor) {
            this.adapterId = adapterId;
            this.transportHint = transportHint;
            this.commandExecutor = commandExecutor;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder adapterMailboxKey(String adapterMailboxKey) {
            this.adapterMailboxKey = adapterMailboxKey;
            return this;
        }

        public Builder deliveryPullChannel(DeliveryPullChannel deliveryPullChannel) {
            this.deliveryPullChannel = deliveryPullChannel;
            return this;
        }

        public Builder pullSessionEvidenceDriver(PullSessionEvidenceDriver pullSessionEvidenceDriver) {
            this.pullSessionEvidenceDriver = pullSessionEvidenceDriver;
            return this;
        }

        public TransportBinding build() {
            return new TransportBinding(this);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
