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
     * capability shape. The returned result should remain event-first, expose
     * the capability identity as {@code eventCode}, and must not expose
     * transport-only wire fields as if they were the control capability identity.
     */
    Map<String, Object> sendWorkerEvent(String workerId,
                                        EventRequest request,
                                        EventPrincipal principal);
}
