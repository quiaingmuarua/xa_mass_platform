package com.xa.mass.api.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ServerApiFailureLogger {

    public static final String LOGGER_NAME = ServerApiFailureLogger.class.getName();

    private static final Logger log = LoggerFactory.getLogger(ServerApiFailureLogger.class);
    private static final int MAX_SAFE_MESSAGE_LENGTH = 240;

    public void logFailure(ServerApiFailureEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", ServerApiFailureAttributes.EVENT);
        fields.put("failureClass", event.failureClass());
        fields.put("httpMethod", event.httpMethod());
        fields.put("httpPath", event.httpPath());
        fields.put("status", Integer.toString(event.status()));
        fields.put("responseCode", Integer.toString(event.status()));
        fields.put("safeMessage", sanitizeSafeMessage(event.safeMessage(), event.failureClass()));
        fields.put("traceId", event.traceId());
        fields.put("durationMs", Long.toString(event.durationMs()));
        fields.put("principalId", event.principalId());
        fields.put("principalType", event.principalType());
        fields.put("routeAuthorizationClass", event.routeAuthorizationClass());
        fields.put("requiredPermission", event.requiredPermission());
        fields.put("originSurface", event.originSurface());
        fields.put("requestSource", event.requestSource());

        Map<String, String> previous = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            previous.put(key, MDC.get(key));
            if (value == null || value.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
        try {
            log.info(ServerApiFailureAttributes.EVENT);
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }

    public String sanitizeSafeMessage(String message, String fallbackFailureClass) {
        String fallback = fallbackMessage(fallbackFailureClass);
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String normalized = message.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase();
        if (lower.contains("authorization")
                || lower.contains("x-mass-api-key")
                || lower.contains("cookie")
                || lower.contains("csrf")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("token")) {
            return fallback;
        }
        if (normalized.length() > MAX_SAFE_MESSAGE_LENGTH) {
            return normalized.substring(0, MAX_SAFE_MESSAGE_LENGTH);
        }
        return normalized;
    }

    private String fallbackMessage(String failureClass) {
        return switch (failureClass == null ? "" : failureClass) {
            case ServerApiFailureAttributes.AUTHENTICATION -> "authentication failed";
            case ServerApiFailureAttributes.AUTHORIZATION -> "authorization failed";
            case ServerApiFailureAttributes.METHOD_NOT_ALLOWED -> "method not allowed";
            case ServerApiFailureAttributes.CONFLICT -> "conflict";
            case ServerApiFailureAttributes.NOT_FOUND -> "not found";
            case ServerApiFailureAttributes.PAYLOAD_TOO_LARGE -> "payload too large";
            case ServerApiFailureAttributes.RATE_LIMIT -> "rate limited";
            case ServerApiFailureAttributes.UNHANDLED -> "unhandled server error";
            default -> "bad request";
        };
    }

    public record ServerApiFailureEvent(String failureClass,
                                        String httpMethod,
                                        String httpPath,
                                        int status,
                                        String safeMessage,
                                        String traceId,
                                        long durationMs,
                                        String principalId,
                                        String principalType,
                                        String routeAuthorizationClass,
                                        String requiredPermission,
                                        String originSurface,
                                        String requestSource) {
    }
}
