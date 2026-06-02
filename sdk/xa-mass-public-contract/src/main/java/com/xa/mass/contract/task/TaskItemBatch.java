package com.xa.mass.contract.task;

import com.xa.mass.contract.UnknownFieldRequest;

import java.util.ArrayList;
import java.util.List;

public class TaskItemBatch extends UnknownFieldRequest {
    private String eventCode;
    private List<Object> items;

    public TaskItemBatch() {
    }

    public TaskItemBatch(String eventCode, List<Object> items) {
        this.eventCode = eventCode;
        this.items = items == null ? null : List.copyOf(items);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public List<Object> getItems() {
        return items;
    }

    public void setItems(List<Object> items) {
        this.items = items;
    }

    public String eventCode() {
        return eventCode;
    }

    public List<Object> items() {
        return items;
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
