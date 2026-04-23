package com.xa.mass.base.debug;

/**
 * Shared transport-compatibility constants for worker control events.
 *
 * <p>This protocol exists only as the current gateway/debug adapter envelope
 * for SDK event-first worker control. The capability identity remains on the
 * global SDK event code carried in {@link #EVENT_FIELD}; this protocol must
 * not become the long-term business routing model.
 */
public final class WorkerControlEventProtocol {

    public static final String SUB_MSG_TYPE = "event";
    public static final String EVENT_FIELD = "event";
    public static final String REQUEST_ID_FIELD = "requestId";
    public static final String HEADERS_FIELD = "headers";
    public static final String PAYLOAD_FIELD = "payload";
    public static final String PRINCIPAL_FIELD = "principal";
    public static final String CLIENT_ID_FIELD = "clientId";
    public static final String USER_ID_FIELD = "userId";

    private WorkerControlEventProtocol() {
    }
}
