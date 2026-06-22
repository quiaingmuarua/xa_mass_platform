package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Adapter-side sink for retryable delivery failure evidence.
 */
@FunctionalInterface
public interface DeliveryFailureEvidenceSink {

    void accept(List<DispatchOutcome> outcomes);
}
