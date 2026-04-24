package com.xa.mass.base.debug;

/**
 * Shared payload-field constants for worker control messages and acknowledgements.
 *
 * <p>Routing identity belongs to the global event code carried by the
 * root-level event-first control frame. These fields describe the payload/body shape
 * used by mock workers and debug tooling.
 */
public final class WorkerControlMessageProtocol {
    public static final String MESSAGE_KIND_FIELD = "messageKind";
    public static final String MESSAGE_KIND_REQUEST = "worker_control";
    public static final String MESSAGE_KIND_ACK = "worker_control_ack";
    public static final String TEXT_FIELD = "text";
    public static final String WORKER_ID_FIELD = "workerId";
    public static final String SENT_AT_FIELD = "sentAt";
    public static final String RECEIVED_AT_FIELD = "receivedAt";
    public static final String EXPECT_REPLY_FIELD = "expectReply";
    public static final String REPLY_TO_MESSAGE_ID_FIELD = "replyToMessageId";
    public static final String ACK_STATUS_FIELD = "ackStatus";
    public static final String ACK_STATUS_RECEIVED = "RECEIVED";
    public static final String EVENT_HANDLED_FIELD = "eventHandled";
    public static final String EVENT_RESULT_FIELD = "eventResult";
    public static final String ECHO_PAYLOAD_FIELD = "echoPayload";
    public static final String SOURCE_FIELD = "source";

    private WorkerControlMessageProtocol() {
    }
}
