package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskItemBatchIngestApiRequest extends AbstractUnknownFieldRequest {

    private List<Object> items;
    private int defaultMsgMaxRetryCount = 3;

    public List<Object> getItems() {
        return items;
    }

    public void setItems(List<Object> items) {
        this.items = items;
    }

    public int getDefaultMsgMaxRetryCount() {
        return defaultMsgMaxRetryCount;
    }

    public void setDefaultMsgMaxRetryCount(int defaultMsgMaxRetryCount) {
        this.defaultMsgMaxRetryCount = defaultMsgMaxRetryCount;
    }
}
