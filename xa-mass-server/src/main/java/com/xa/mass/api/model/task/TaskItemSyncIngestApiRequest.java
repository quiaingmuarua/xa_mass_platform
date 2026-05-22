package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
        name = "TaskItemSyncIngestApiRequest",
        description = "Appends exactly one opaque work item to an active task and blocks until that item reaches stable finality or the caller timeout elapses."
)
public class TaskItemSyncIngestApiRequest extends AbstractUnknownFieldRequest {

    @Schema(
            description = "Batch-level event/capability code. The item may alternatively carry its own eventCode.",
            example = "crawler.fetch-page"
    )
    private String eventCode;

    @Schema(
            description = "Exactly one opaque work item payload. Payload schema is owned by the event/capability, not by task API."
    )
    private Object item;

    @Schema(
            description = "Optional synchronous wait timeout in milliseconds. Timing out only ends the HTTP wait; the task item keeps running.",
            example = "5000"
    )
    private Long timeoutMs;

    @Schema(
            description = "Optional caller correlation key reserved for future idempotency or audit enrichment.",
            example = "req-001"
    )
    private String clientRequestId;

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public Object getItem() {
        return item;
    }

    public void setItem(Object item) {
        this.item = item;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }
}
