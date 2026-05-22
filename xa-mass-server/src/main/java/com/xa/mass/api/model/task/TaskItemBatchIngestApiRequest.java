package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "TaskItemBatchIngestApiRequest", description = "Appends opaque work item payloads to an open task intake window.")
public class TaskItemBatchIngestApiRequest extends AbstractUnknownFieldRequest {

    @Schema(description = "Batch-level event/capability code. Items may alternatively carry their own eventCode.", example = "crawler.fetch-page")
    private String eventCode;
    @Schema(description = "Opaque work item payload list. Payload schema is owned by the event/capability, not by task API.")
    private List<Object> items;

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
}
