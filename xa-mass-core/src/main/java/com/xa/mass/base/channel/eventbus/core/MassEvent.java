package com.xa.mass.base.channel.eventbus.core;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public interface MassEvent extends Serializable {
    String getEventId();

    Instant getTimestamp();

    String getDescription();

    default String getEventType() {
        return null;
    }            // 业务事件类型标识

    default MassPlatformEventType getPlatformEventType() {
        return null;
    } // 平台事件类型，业务事件可不实现

    default Map<String, Object> getMetadata() {
        return Collections.emptyMap();
    }

    default String getTraceId() {
        return null;
    }

    default String getRequestId() {
        return null;
    }

    abstract class BaseMassEvent implements MassEvent {
        private final String eventId;
        private final Instant timestamp;
        private final String description;
        private final String eventType;
        private final MassPlatformEventType platformEventType;
        private final Map<String, Object> metadata;
        private final String traceId;
        private final String requestId;

        protected BaseMassEvent(
                String eventType,
                MassPlatformEventType platformEventType,
                String description,
                Map<String, Object> metadata,
                String traceId,
                String requestId
        ) {
            this.eventId = UUID.randomUUID().toString();
            this.timestamp = Instant.now();
            this.eventType = eventType;
            this.platformEventType = platformEventType;
            this.description = description;
            this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
            this.traceId = traceId;
            this.requestId = requestId;
        }

        @Override
        public String getEventId() {
            return eventId;
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventType() {
            return eventType;
        }

        @Override
        public MassPlatformEventType getPlatformEventType() {
            return platformEventType;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }

        @Override
        public String getTraceId() {
            return traceId;
        }

        @Override
        public String getRequestId() {
            return requestId;
        }

        @Override
        public String toString() {
            return String.format(
                    "MassEvent{eventId='%s', type='%s', platformType='%s', desc='%s', timestamp=%s, traceId=%s, requestId=%s}",
                    eventId, eventType, platformEventType, description, timestamp, traceId, requestId);
        }
    }
}
