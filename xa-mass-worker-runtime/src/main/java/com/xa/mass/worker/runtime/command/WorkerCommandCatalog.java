package com.xa.mass.worker.runtime.command;

import java.util.Locale;
import java.util.Set;

/**
 * Small owner-side command catalog for command lifecycle admission.
 */
public final class WorkerCommandCatalog {

    private static final Set<String> APPROVED_COMMAND_TYPES = Set.of("DRAIN", "PING");

    private WorkerCommandCatalog() {
    }

    public static boolean isApproved(String commandType) {
        String normalized = normalize(commandType);
        return normalized != null && APPROVED_COMMAND_TYPES.contains(normalized);
    }

    public static String normalize(String commandType) {
        if (commandType == null || commandType.isBlank()) {
            return null;
        }
        return commandType.trim().toUpperCase(Locale.ROOT);
    }
}
