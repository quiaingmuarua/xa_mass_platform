package com.xa.mass.api.auth.session;

import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.CredentialHashing;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ApiKeyViewerSessionService {

    public static final String ATTR_SESSION_ID = "apiKeyViewerSessionId";
    public static final String ATTR_SOURCE_KEY_ID = "apiKeyViewerSourceKeyId";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RAW_SESSION_PREFIX = "mass_sess_";
    private static final Duration DEFAULT_TTL = Duration.ofHours(8);

    private final ApiKeyViewerSessionStore store;
    private final AuthProvider authProvider;
    private final ApiKeyCredentialService apiKeyCredentialService;

    public ApiKeyViewerSessionService(ApiKeyViewerSessionStore store,
                                         AuthProvider authProvider,
                                         ApiKeyCredentialService apiKeyCredentialService) {
        this.store = Objects.requireNonNull(store, "store");
        this.authProvider = Objects.requireNonNull(authProvider, "authProvider");
        this.apiKeyCredentialService = Objects.requireNonNull(apiKeyCredentialService, "apiKeyCredentialService");
    }

    public CreatedApiKeyViewerSession create(String sourceCredential) {
        String credential = requireNonBlank(sourceCredential, "credential");
        PrincipalContext existingSession = authenticate(credential);
        if (existingSession != null && existingSession.getAttributes().containsKey(ATTR_SESSION_ID)) {
            throw new IllegalArgumentException("API-key viewer sessions cannot create nested sessions");
        }
        PrincipalContext source = apiKeyCredentialService.validateAuthenticatedPrincipal(authProvider.authenticate(credential));
        if (source == null) {
            throw new IllegalArgumentException("Invalid or inactive API-key credential");
        }
        if (source.getAttributes().containsKey(ATTR_SESSION_ID)) {
            throw new IllegalArgumentException("API-key viewer sessions cannot create nested sessions");
        }
        String keyId = apiKeyCredentialService.apiKeyId(source);
        if (keyId == null) {
            throw new IllegalArgumentException("API-key viewer session requires an API-key principal");
        }
        List<String> viewerPermissions = viewerPermissions(source.getPermissions());
        if (viewerPermissions.isEmpty()) {
            throw new IllegalArgumentException("API key does not grant viewer permissions");
        }
        String rawSecret = generateSecret();
        String sessionId = "svs_" + UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, String> attributes = new LinkedHashMap<>(source.getAttributes());
        attributes.put(ATTR_SESSION_ID, sessionId);
        attributes.put(ATTR_SOURCE_KEY_ID, keyId);
        ApiKeyViewerSessionRecord record = store.create(new ApiKeyViewerSessionRecord(
                sessionId,
                keyId,
                source.getPrincipalId(),
                source.getUserId(),
                CredentialHashing.sha256(rawSecret),
                keyPrefix(rawSecret),
                viewerPermissions,
                source.getProjectScopes(),
                source.getEventScopes(),
                Map.copyOf(attributes),
                now,
                now.plus(DEFAULT_TTL),
                null
        ));
        return new CreatedApiKeyViewerSession(record, rawSecret, toPrincipal(record));
    }

    public PrincipalContext authenticate(String credential) {
        String normalized = normalize(credential);
        if (normalized == null) {
            return null;
        }
        ApiKeyViewerSessionRecord record = store.getByCredentialHash(CredentialHashing.sha256(normalized));
        if (record == null || record.revokedAt() != null || !record.expiresAt().isAfter(Instant.now())) {
            return null;
        }
        if (!apiKeyCredentialService.isCredentialActive(record.keyId())) {
            return null;
        }
        return toPrincipal(record);
    }

    public ApiKeyViewerSessionRecord current(String credential) {
        PrincipalContext principal = authenticate(credential);
        if (principal == null) {
            return null;
        }
        return store.get(principal.getAttributes().get(ATTR_SESSION_ID));
    }

    public ApiKeyViewerSessionRecord logout(String credential) {
        ApiKeyViewerSessionRecord current = current(credential);
        return current == null ? null : store.revoke(current.sessionId());
    }

    private PrincipalContext toPrincipal(ApiKeyViewerSessionRecord record) {
        return PrincipalContext.builder()
                .principalId(record.principalId())
                .userId(record.createdForUserId())
                .permissions(record.permissions())
                .projectScopes(record.projectScopes())
                .eventScopes(record.eventScopes())
                .attributes(record.attributes())
                .build();
    }

    private List<String> viewerPermissions(List<String> sourcePermissions) {
        Set<String> source = new LinkedHashSet<>(Objects.requireNonNullElse(sourcePermissions, List.of()));
        Set<String> permissions = new LinkedHashSet<>();
        if (source.contains(ApiPermissionNames.TASK_VIEW)) {
            permissions.add(ApiPermissionNames.TASK_VIEW);
        }
        if (source.contains(ApiPermissionNames.API_USAGE_VIEW)) {
            permissions.add(ApiPermissionNames.API_USAGE_VIEW);
        }
        return List.copyOf(permissions);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return RAW_SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String keyPrefix(String rawSecret) {
        return rawSecret.substring(0, Math.min(18, rawSecret.length())) + "...";
    }

    private String requireNonBlank(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreatedApiKeyViewerSession(ApiKeyViewerSessionRecord record,
                                                String rawSecret,
                                                PrincipalContext principal) {
    }
}
