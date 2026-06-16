package com.xa.mass.transport.runtime;

/**
 * Default Redis namespaces for transport-owned runtime state.
 */
public final class RedisTransportNamespaces {

    public static final String DELIVERY = "xa:mass:transport:delivery:v1";
    public static final String DELIVERY_COMMAND = "xa:mass:transport:delivery-command:v1";
    public static final String DELIVERY_FAILURE = "xa:mass:transport:delivery-failure:v1";
    public static final String ENDPOINT_LEASE = "xa:mass:transport:endpoint-lease:v1";
    public static final String NODES = "xa:mass:transport:nodes:v1";
    public static final String RESULT_INBOX = "xa:mass:transport:result-inbox:v1";

    private RedisTransportNamespaces() {
    }
}
