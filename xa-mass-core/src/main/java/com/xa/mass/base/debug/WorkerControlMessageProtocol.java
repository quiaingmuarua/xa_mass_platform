package com.xa.mass.base.debug;

/**
 * Shared payload-field constants for worker control messages and acknowledgements.
 *
 * <p>Routing identity belongs to the global event code carried by the
 * {@code CONTROL/event} envelope. These fields describe the payload/body shape
 * used by mock workers and debug tooling.
 */
public final class WorkerControlMessageProtocol {
    /**
     * @deprecated Control-plane routing truth is {@code CONTROL/event}. This
     * legacy sub-message type remains only for historical records.
     */
    @Deprecated(forRemoval = false)
    public static final String LEGACY_SUB_MSG_TYPE = "manual-chat";
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
    public static final String ECHO_PAYLOAD_FIELD = "echoPayload";
    public static final String ECHO_SUB_MSG_TYPE_FIELD = "echoSubMsgType";
    public static final String SOURCE_FIELD = "source";

    private WorkerControlMessageProtocol() {
    }
}
