package com.xa.mass.transport.runtime;

/**
 * Default Redis namespaces for transport-owned runtime state.
 */
public final class RedisTransportNamespaces {

    public static final String DISPATCH = "xa:mass:transport:dispatch:v1";
    public static final String ENDPOINT_LEASE = "xa:mass:transport:endpoint-lease:v1";
    public static final String RESULT_INGRESS = "xa:mass:transport:result-ingress:v1";

    private RedisTransportNamespaces() {
    }
}
