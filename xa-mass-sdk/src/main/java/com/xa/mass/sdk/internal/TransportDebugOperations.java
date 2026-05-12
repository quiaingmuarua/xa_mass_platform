package com.xa.mass.sdk.internal;

import com.xa.mass.sdk.RuntimeDiagnosticsOperations;

import java.util.Map;

/**
 * Internal/operator-only transport diagnostics and raw side-channel access.
 *
 * <p>This surface is intentionally outside the stable SDK mainline.
 */
public interface TransportDebugOperations extends RuntimeDiagnosticsOperations {

    Map<String, Object> enqueueRawMessage(Map<String, Object> request);
}
