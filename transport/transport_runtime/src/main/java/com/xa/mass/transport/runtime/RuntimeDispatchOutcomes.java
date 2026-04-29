package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.List;

/**
 * Shared runtime helpers for adapter-neutral dispatch outcome generation.
 */
public final class RuntimeDispatchOutcomes {

    private RuntimeDispatchOutcomes() {
    }

    public static List<DispatchOutcome> adapterUnavailable(String adapterId,
                                                           List<TransportDispatchEnvelope> envelopes,
                                                           String reason) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        return envelopes.stream()
                .map(envelope -> missingRoute(envelope)
                        ? DispatchOutcome.invalid(adapterId, envelope, "routeKey must not be blank")
                        : DispatchOutcome.adapterUnavailable(adapterId, envelope, reason))
                .toList();
    }

    public static boolean missingRoute(TransportDispatchEnvelope envelope) {
        return envelope == null || envelope.getRouteKey() == null || envelope.getRouteKey().isBlank();
    }
}
