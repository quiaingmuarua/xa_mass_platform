package com.xa.mass.contract.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Public worker-channel wire frame for multiplexed worker protocols.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerChannelFrame(
        String frameId,
        String kind,
        String body
) {
    public static final String ACTION = "ACTION";
    public static final String ACTION_REPLY = "ACTION_REPLY";
    public static final String EVIDENCE_REPORT = "EVIDENCE_REPORT";
    public static final String HEARTBEAT = "HEARTBEAT";

    @JsonCreator
    public WorkerChannelFrame(@JsonProperty("frameId") String frameId,
                              @JsonProperty("kind") String kind,
                              @JsonProperty("body") String body) {
        this.frameId = requireText(frameId, "frameId");
        this.kind = requireText(kind, "kind");
        this.body = requireBody(body);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static String requireBody(String value) {
        if (value == null) {
            throw new IllegalArgumentException("body is required");
        }
        return value;
    }
}
