package com.xa.mass.server.kernelbinding;

import java.util.Map;
import java.util.function.Function;

final class KernelHttpResultDecoder {

    private KernelHttpResultDecoder() {
    }

    static <T> T status(
            Map<String, Object> payload,
            Function<String, T> decoder
    ) {
        Object rawStatus = payload.get("status");
        if (!(rawStatus instanceof String value)) {
            throw PythonKernelBindingException.invalidResponse(
                    "Kernel status is missing or invalid"
            );
        }
        try {
            return decoder.apply(value);
        } catch (IllegalArgumentException error) {
            throw PythonKernelBindingException.invalidResponse(
                    "Kernel status is unknown",
                    error
            );
        }
    }

    static String reason(Map<String, Object> payload) {
        Object rawReason = payload.get("reason");
        if (rawReason == null) {
            return null;
        }
        if (!(rawReason instanceof String reason)) {
            throw PythonKernelBindingException.invalidResponse(
                    "Kernel reason is invalid"
            );
        }
        return reason;
    }
}
