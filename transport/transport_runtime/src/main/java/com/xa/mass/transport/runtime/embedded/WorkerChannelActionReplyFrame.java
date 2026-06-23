package com.xa.mass.transport.runtime.embedded;

/**
 * Decoded worker-channel ACTION_REPLY facts for embedded adapter result ingress.
 */
public record WorkerChannelActionReplyFrame(
        String frameId,
        String replyRef,
        String body
) {
    public WorkerChannelActionReplyFrame {
        frameId = requireText(frameId, "frameId");
        replyRef = requireText(replyRef, "replyRef");
        if (body == null) {
            throw new IllegalArgumentException("body is required");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
