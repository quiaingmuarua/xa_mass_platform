package com.xa.mass.starter;

import java.util.Map;

/**
 * Internal typed view for transport/event runtime executor diagnostics.
 */
record RuntimeExecutorsDiagnosticsView(RuntimeExecutorDiagnosticsView transport,
                                       RuntimeExecutorDiagnosticsView event) {

    Map<String, Object> toMap() {
        return Map.of(
                "transport", transport != null ? transport.toMap() : Map.of(),
                "event", event != null ? event.toMap() : Map.of()
        );
    }
}
