package com.xa.mass.worker.runtime;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable result of one successful Worker preparation.
 */
public final class PreparedWorker {

    private final String workerId;
    private final URI endpointUri;

    public PreparedWorker(String workerId, URI endpointUri) {
        this.workerId = requireNonBlank(workerId, "workerId");
        this.endpointUri = requireEndpointUri(endpointUri);
    }

    public String workerId() {
        return workerId;
    }

    public URI endpointUri() {
        return endpointUri;
    }

    private static URI requireEndpointUri(URI value) {
        Objects.requireNonNull(value, "endpointUri");
        if (!value.isAbsolute() || value.getHost() == null) {
            throw new IllegalArgumentException(
                    "endpointUri must be an absolute network URI"
            );
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
