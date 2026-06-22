package com.xa.mass.transport.routing;

/**
 * Logical routing destination for a queue/process-boundary envelope.
 */
public record RoutingTarget(String ownerKind,
                            String ownerRef) {

    public RoutingTarget {
        ownerKind = RoutingOwnerKinds.requireKnownOwnerKind(ownerKind);
        ownerRef = requireText(ownerRef, "ownerRef");
    }

    public static RoutingTarget adapter(String adapterMailboxKey) {
        return new RoutingTarget(RoutingOwnerKinds.ADAPTER, adapterMailboxKey);
    }

    public static RoutingTarget engine(String resultCorrelationRef) {
        return new RoutingTarget(RoutingOwnerKinds.ENGINE, resultCorrelationRef);
    }

    public static RoutingTarget resultIngress(String resultCorrelationRef) {
        return new RoutingTarget(RoutingOwnerKinds.RESULT_INGRESS, resultCorrelationRef);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
