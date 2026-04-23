package com.xa.mass.sdk;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;

import java.util.List;
import java.util.Map;

public interface DebugOperations {

    List<?> getWorkerMessageHistory(String workerId);

    /**
     * Sends a worker debug/control event through the current transport adapter.
     *
     * <p>The supplied {@link EventRequest} remains the canonical control-plane
     * capability shape. Returned {@code msgType}/{@code subMsgType} values are
     * transport diagnostics only and must not be treated as the capability
     * identifier; that identity remains on {@code request.event}.
     */
    Map<String, Object> sendWorkerEvent(String workerId,
                                        EventRequest request,
                                        EventPrincipal principal);
}
