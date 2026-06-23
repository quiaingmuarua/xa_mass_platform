package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;

import java.util.Map;
import java.util.UUID;

public final class AdapterResultIngressEntries {
    private AdapterResultIngressEntries() {
    }

    public static ResultIngressEntry from(String resultCorrelationRef,
                                          String payload,
                                          Map<String, String> diagnostics) {
        String correlation = requireText(resultCorrelationRef, "resultCorrelationRef");
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        long now = System.currentTimeMillis();
        return new ResultIngressEntry(
                correlation,
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        correlation,
                        payload,
                        0L,
                        now
                ),
                new ResultIngressDiagnostics(diagnostics)
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
