package com.xa.mass.server.kernelbinding;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
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
            throw new ServerException(
                    ServerErrorCode.INVALID_KERNEL_RESPONSE,
                    "kernelBinding.decodeResult",
                    "Kernel status is missing or invalid",
                    null
            );
        }
        try {
            return decoder.apply(value);
        } catch (IllegalArgumentException error) {
            throw new ServerException(
                    ServerErrorCode.INVALID_KERNEL_RESPONSE,
                    "kernelBinding.decodeResult",
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
            throw new ServerException(
                    ServerErrorCode.INVALID_KERNEL_RESPONSE,
                    "kernelBinding.decodeResult",
                    "Kernel reason is invalid",
                    null
            );
        }
        return reason;
    }
}
