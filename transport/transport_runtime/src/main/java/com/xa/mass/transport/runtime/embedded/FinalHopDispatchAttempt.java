package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.runtime.delivery.DispatchMessage;

@FunctionalInterface
public interface FinalHopDispatchAttempt {
    boolean send(DispatchMessage message);
}
