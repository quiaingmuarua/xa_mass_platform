package com.xa.mass.sdk.event;

import java.util.Objects;

/**
 * Minimal event caller identity.
 */
public final class EventPrincipal {

    private final String clientId;
    private final String userId;

    private EventPrincipal(Builder builder) {
        this.clientId = trimToNull(builder.clientId);
        this.userId = trimToNull(builder.userId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EventPrincipal of(String clientId, String userId) {
        return builder().clientId(clientId).userId(userId).build();
    }

    public String getClientId() {
        return clientId;
    }

    public String getUserId() {
        return userId;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventPrincipal that)) return false;
        return Objects.equals(clientId, that.clientId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId, userId);
    }

    public static final class Builder {
        private String clientId;
        private String userId;

        private Builder() {
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public EventPrincipal build() {
            return new EventPrincipal(this);
        }
    }
}
