package com.xa.mass.integration.androidworkerproof;

import java.util.List;

final class ProofFailure extends RuntimeException {

    private final String invariant;
    private final List<String> missingIds;
    private final List<String> unexpectedIds;
    private final List<String> inconsistentIds;

    ProofFailure(String invariant, String message) {
        this(invariant, message, List.of(), List.of(), List.of(), null);
    }

    ProofFailure(
            String invariant,
            String message,
            List<String> missingIds,
            List<String> unexpectedIds,
            List<String> inconsistentIds
    ) {
        this(
                invariant,
                message,
                missingIds,
                unexpectedIds,
                inconsistentIds,
                null
        );
    }

    ProofFailure(String invariant, String message, Throwable cause) {
        this(invariant, message, List.of(), List.of(), List.of(), cause);
    }

    private ProofFailure(
            String invariant,
            String message,
            List<String> missingIds,
            List<String> unexpectedIds,
            List<String> inconsistentIds,
            Throwable cause
    ) {
        super(message, cause);
        this.invariant = requireText(invariant, "invariant");
        this.missingIds = List.copyOf(missingIds);
        this.unexpectedIds = List.copyOf(unexpectedIds);
        this.inconsistentIds = List.copyOf(inconsistentIds);
    }

    String invariant() {
        return invariant;
    }

    String safeMessage() {
        return getMessage();
    }

    List<String> missingIds() {
        return missingIds;
    }

    List<String> unexpectedIds() {
        return unexpectedIds;
    }

    List<String> inconsistentIds() {
        return inconsistentIds;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
