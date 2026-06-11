package com.xa.mass.transport.runtime;

/**
 * Default Redis namespaces for transport-owned runtime state.
 */
public final class RedisTransportNamespaces {

    public static final String DELIVERY = "xa:mass:transport:delivery:v1";
    public static final String ROUTE_OWNER = "xa:mass:transport:route-owner:v1";
    public static final String NODES = "xa:mass:transport:nodes:v1";
    public static final String DISPATCH_NODE = "xa:mass:transport:dispatch-node:v1";
    public static final String DISPATCH_HANDOFF = "xa:mass:transport:dispatch-handoff:v1";
    public static final String RESULT_INBOX = "xa:mass:transport:result-inbox:v1";
    public static final String DISPATCH_FAILURE = "xa:mass:transport:dispatch-failure:v1";

    private RedisTransportNamespaces() {
    }
}
