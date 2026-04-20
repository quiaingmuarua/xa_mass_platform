package com.xa.mass.base.debug;

/**
 * Shared protocol constants for manual worker debug chat.
 */
public final class ManualDebugChatProtocol {
    public static final String SUB_MSG_TYPE = "manual-chat";
    public static final String MESSAGE_KIND_FIELD = "messageKind";
    public static final String MESSAGE_KIND_REQUEST = "debug_chat";
    public static final String MESSAGE_KIND_ACK = "debug_chat_ack";
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

    private ManualDebugChatProtocol() {
    }
}
