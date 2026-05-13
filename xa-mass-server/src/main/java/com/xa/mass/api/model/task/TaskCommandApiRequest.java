package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "TaskCommandApiRequest", description = "Unified task lifecycle, governance, and intake command request.")
public class TaskCommandApiRequest extends AbstractUnknownFieldRequest {

    @Schema(
            description = "Task command",
            allowableValues = {"APPROVE", "REJECT", "BLOCK", "PAUSE", "RESUME", "TERMINATE", "SEAL"},
            example = "SEAL"
    )
    private String command;
    @Schema(description = "Optional human-readable command reason", example = "input complete")
    private String reason;
    @Schema(description = "Reserved command options object. Current commands do not require options.")
    private Map<String, Object> options;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }
}
