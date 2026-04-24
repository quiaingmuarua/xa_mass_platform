package com.xa.mass.base.debug;

/**
 * Shared transport-compatibility constants for worker control events.
 *
 * <p>This protocol exists only as the current gateway/debug adapter envelope
 * for SDK event-first worker control. The capability identity remains on the
 * global SDK event code carried in {@link #EVENT_CODE_FIELD}. This protocol
 * must not become the long-term business routing model.
 */
public final class WorkerControlEventProtocol {

    public static final String EVENT_CODE_FIELD = "eventCode";
    public static final String MESSAGE_ID_FIELD = "messageId";
    public static final String RESPONSE_FIELD = "response";
    public static final String WORKER_ID_FIELD = "workerId";
    public static final String PROJECT_FIELD = "project";
    public static final String REQUEST_ID_FIELD = "requestId";
    public static final String HEADERS_FIELD = "headers";
    public static final String PAYLOAD_FIELD = "payload";
    public static final String PRINCIPAL_FIELD = "principal";
    public static final String CLIENT_ID_FIELD = "clientId";
    public static final String USER_ID_FIELD = "userId";
    public static final String SUCCESS_FIELD = "success";
    public static final String CODE_FIELD = "code";
    public static final String MESSAGE_FIELD = "message";
    public static final String DATA_FIELD = "data";

    private WorkerControlEventProtocol() {
    }
}
