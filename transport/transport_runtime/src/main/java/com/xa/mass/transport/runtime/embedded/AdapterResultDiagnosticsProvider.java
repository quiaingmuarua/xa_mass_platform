package com.xa.mass.transport.runtime.embedded;

import java.util.Map;

/**
 * Supplies adapter-local diagnostics for an already decoded result frame.
 */
@FunctionalInterface
public interface AdapterResultDiagnosticsProvider<T> {

    Map<String, String> diagnostics(T resultFrame, AdapterResultFrame result);
}
