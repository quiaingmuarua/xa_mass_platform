package com.xa.mass.sdk;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;

import java.util.List;
import java.util.Map;

public interface DebugOperations {

    List<?> getWorkerMessageHistory(String workerId);

    Map<String, Object> sendWorkerEvent(String workerId,
                                        EventRequest request,
                                        EventPrincipal principal);

    /**
     * @deprecated Compatibility entry for legacy debug/control callers that still
     * expose transport-oriented msgType/subMsgType fields.
     */
    @Deprecated(forRemoval = false)
    Map<String, Object> sendWorkerMessage(String workerId,
                                          String project,
                                          String msgType,
                                          String subMsgType,
                                          Object payload);
}
