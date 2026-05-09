package com.xa.mass.sdk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SDK mainline item-ingest contract.
 *
 * <p>The task shell already defines the payload contract. This request carries
 * only the append batch plus an optional retry seed.
 */
public final class MassTaskItemBatchAppendRequest {

    private final String eventCode;
    private final List<Object> items;
    private final Integer defaultMsgMaxRetryCount;

    private MassTaskItemBatchAppendRequest(Builder builder) {
        this.eventCode = normalizeString(builder.eventCode);
        this.items = unmodifiableItems(builder.items);
        this.defaultMsgMaxRetryCount = builder.defaultMsgMaxRetryCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventCode() {
        return eventCode;
    }

    public List<Object> getItems() {
        return items;
    }

    public Integer getDefaultMsgMaxRetryCount() {
        return defaultMsgMaxRetryCount;
    }

    public static final class Builder {
        private String eventCode;
        private List<Object> items = Collections.emptyList();
        private Integer defaultMsgMaxRetryCount;

        private Builder() {
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder items(List<Object> items) {
            this.items = items != null ? items : Collections.emptyList();
            return this;
        }

        public Builder defaultMsgMaxRetryCount(Integer defaultMsgMaxRetryCount) {
            this.defaultMsgMaxRetryCount = defaultMsgMaxRetryCount;
            return this;
        }

        public MassTaskItemBatchAppendRequest build() {
            return new MassTaskItemBatchAppendRequest(this);
        }
    }

    private static List<Object> unmodifiableItems(List<Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static String normalizeString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
