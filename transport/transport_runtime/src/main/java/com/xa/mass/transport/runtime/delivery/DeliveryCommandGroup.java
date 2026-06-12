package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.List;

/**
 * Producer-side command group sharing one adapter identity.
 */
public record DeliveryCommandGroup(String adapterId, List<DeliveryCommand> commands) {

    public DeliveryCommandGroup {
        adapterId = requireAdapterId(adapterId);
        commands = commands == null ? List.of() : List.copyOf(commands);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        if (commands.stream().anyMatch(command -> command == null)) {
            throw new IllegalArgumentException("commands must not contain null");
        }
    }

    private static String requireAdapterId(String value) {
        String normalized = TransportDeliveryAddressing.normalizeAdapterId(value);
        if (normalized == null) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return normalized;
    }
}
