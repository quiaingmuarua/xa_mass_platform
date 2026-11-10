package com.xa.mass.worker.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** An optional machine credential for one Runtime's HTTPS/WSS gateway. */
public final class WorkerGatewayCredential {
    private final String keyId;
    private final String authorizationHeader;

    private WorkerGatewayCredential(String keyId, String secret) {
        if (keyId == null || keyId.isEmpty() || keyId.length() > 128
                || keyId.chars().anyMatch(value -> value < 33 || value > 126 || value == ':')) {
            throw new IllegalArgumentException("Gateway keyId must contain 1..128 visible ASCII characters without colon");
        }
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length > 4096
                || secret.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Gateway secret must be non-blank, at most 4096 UTF-8 bytes and contain no control characters");
        }
        this.keyId = keyId;
        this.authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(
                (keyId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    public static WorkerGatewayCredential of(String keyId, String secret) {
        return new WorkerGatewayCredential(keyId, secret);
    }

    public String keyId() { return keyId; }

    /** Sensitive request material; never include this value in logs or Properties. */
    public String authorizationHeader() { return authorizationHeader; }

    /** May be called by a Host before creating inventory or networking resources. */
    public void validateRuntimeUri(URI runtimeApiBaseUrl) {
        requireSecureUri(runtimeApiBaseUrl, "https", "Gateway Runtime URI must be HTTPS without userinfo, query or fragment");
    }

    public void validateEndpointUri(URI runtimeApiBaseUrl, URI endpointUri) {
        validateRuntimeUri(runtimeApiBaseUrl);
        requireSecureUri(endpointUri, "wss", "Gateway Endpoint URI must be WSS without userinfo, query or fragment");
        if (!runtimeApiBaseUrl.getHost().equalsIgnoreCase(endpointUri.getHost())
                || effectivePort(runtimeApiBaseUrl) != effectivePort(endpointUri)) {
            throw new IllegalArgumentException("Gateway Endpoint must have the Runtime host and effective port");
        }
    }

    private static void requireSecureUri(URI value, String scheme, String message) {
        if (value == null || !scheme.equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getRawUserInfo() != null
                || value.getRawQuery() != null || value.getRawFragment() != null
                || value.getPort() == 0 || value.getPort() > 65535 || value.getPort() < -1) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int effectivePort(URI value) { return value.getPort() == -1 ? 443 : value.getPort(); }

    @Override
    public String toString() { return "WorkerGatewayCredential[keyId=" + keyId + ", secret=<redacted>]"; }
}
