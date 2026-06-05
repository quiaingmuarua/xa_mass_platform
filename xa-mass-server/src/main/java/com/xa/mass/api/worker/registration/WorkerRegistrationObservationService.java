package com.xa.mass.api.worker.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class WorkerRegistrationObservationService {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistrationObservationService.class);
    private static final int MAX_PAYLOAD_JSON_LENGTH = 8192;

    private final WorkerRegistrationObservationStore store;
    private final ObjectMapper objectMapper;

    public WorkerRegistrationObservationService(WorkerRegistrationObservationStore store) {
        this(store, new ObjectMapper().findAndRegisterModules());
    }

    public WorkerRegistrationObservationService(WorkerRegistrationObservationStore store, ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public void observeSuccessfulRegistration(String resourceType,
                                              String resourceId,
                                              String action,
                                              PrincipalContext principal,
                                              Map<String, Object> payload) {
        try {
            String payloadJson = payloadJson(payload);
            store.append(new WorkerRegistrationObservationRecord(
                    observationId(resourceType, resourceId),
                    normalize(resourceType),
                    resourceId,
                    normalize(action),
                    principal == null ? null : principal.getPrincipalId(),
                    principal == null || principal.getPrincipalType() == null
                            ? null
                            : principal.getPrincipalType().name(),
                    sha256(payloadJson),
                    payloadJson,
                    Instant.now()
            ));
        } catch (RuntimeException e) {
            log.warn("Failed to record worker registration observation for {} {}", resourceType, resourceId, e);
        }
    }

    private String payloadJson(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            return json.length() <= MAX_PAYLOAD_JSON_LENGTH
                    ? json
                    : json.substring(0, MAX_PAYLOAD_JSON_LENGTH);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode worker registration observation payload", e);
        }
    }

    private String observationId(String resourceType, String resourceId) {
        return normalize(resourceType) + ":" + resourceId + ":" + UUID.randomUUID();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash worker registration observation payload", e);
        }
    }
}
