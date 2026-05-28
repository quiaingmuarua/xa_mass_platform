package com.xa.mass.client.task;

import java.util.ArrayList;
import java.util.List;

public record TaskItemBatch(String eventCode, List<Object> items) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String eventCode;
        private final List<Object> items = new ArrayList<>();

        private Builder() {
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder item(Object item) {
            this.items.add(item);
            return this;
        }

        public Builder items(List<?> items) {
            this.items.clear();
            if (items != null) {
                this.items.addAll(items);
            }
            return this;
        }

        public TaskItemBatch build() {
            return new TaskItemBatch(eventCode, List.copyOf(items));
        }
    }
}
