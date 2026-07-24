package com.xa.mass.server.kernelclient;

import java.util.Objects;
import org.springframework.http.HttpStatusCode;

public record KernelResponse<T>(
        HttpStatusCode statusCode,
        T body
) {
    public KernelResponse {
        Objects.requireNonNull(statusCode, "statusCode");
        Objects.requireNonNull(body, "body");
    }
}
