package com.xa.mass.api.auth.apikey;

import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.iam.UserRecord;
import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserStatus;
import com.xa.mass.sdk.auth.CredentialHashing;
import com.xa.mass.sdk.auth.CredentialAuthProjectionWriter;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
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
public class ApiKeyCredentialService {

    public static final String ATTR_KEY_ID = "apiKeyId";
    public static final String ATTR_CREDENTIAL_OWNER = "apiKeyCreatedForUserId";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RAW_KEY_PREFIX = "mass_sk_";

    private final ApiKeyApplicationStore applicationStore;
    private final ApiKeyCredentialStore credentialStore;
    private final UserRolePermissionStore userStore;
    private final CredentialAuthProjectionWriter credentialProjectionWriter;

    public ApiKeyCredentialService(ApiKeyApplicationStore applicationStore,
                                   ApiKeyCredentialStore credentialStore,
                                   UserRolePermissionStore userStore,
                                   CredentialAuthProjectionWriter credentialProjectionWriter) {
        this.applicationStore = Objects.requireNonNull(applicationStore, "applicationStore");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.userStore = Objects.requireNonNull(userStore, "userStore");
        this.credentialProjectionWriter = Objects.requireNonNull(
                credentialProjectionWriter,
                "credentialProjectionWriter"
        );
    }

    public ApiKeyApplicationRecord createApplication(CreateApplicationCommand command) {
        CreateApplicationCommand normalized = normalize(command);
        UserRecord applicant = requireActiveUser(normalized.applicantUserId());
        String requestedUserId = normalized.requestedUserId() == null
                ? applicant.userId()
                : normalized.requestedUserId();
        if (!applicant.userId().equals(requestedUserId)) {
            throw new IllegalArgumentException("requestedUserId must match applicantUserId");
        }
        validatePermissions(normalized.requestedPermissions());
        return applicationStore.create(new ApiKeyApplicationRecord(
                "aka_" + UUID.randomUUID(),
                applicant.userId(),
                normalized.applicantName() == null ? applicant.displayName() : normalized.applicantName(),
                normalized.requestedPrincipalId(),
                requestedUserId,
                normalized.requestedProjectScopes(),
                normalized.requestedEventScopes(),
                normalized.requestedPermissions(),
                normalized.purpose(),
                ApiKeyApplicationStatus.PENDING,
                null,
                null,
                Instant.now(),
                null,
                normalized.attributes()
        ));
    }

    public List<ApiKeyApplicationRecord> listApplications() {
        return applicationStore.list();
    }

    public ApiKeyApplicationRecord getApplication(String applicationId) {
        return applicationStore.get(applicationId);
    }

    public CreatedApiKey approveApplication(String applicationId,
                                            String reviewedBy,
                                            String reviewReason,
                                            Instant expiresAt) {
        ApiKeyApplicationRecord application = applicationStore.get(applicationId);
        if (application == null) {
            return null;
        }
        if (application.status() != ApiKeyApplicationStatus.PENDING) {
            throw new IllegalArgumentException("API key application is not pending: " + applicationId);
        }
        String principalId = application.requestedPrincipalId() == null || application.requestedPrincipalId().isBlank()
                ? "api-key-" + application.applicationId()
                : application.requestedPrincipalId();
        CreatedApiKey created = createOperatorKey(new CreateApiKeyCommand(
                principalId,
                application.requestedUserId(),
                application.requestedProjectScopes(),
                application.requestedEventScopes(),
                application.requestedPermissions(),
                reviewedBy,
                expiresAt,
                application.attributes(),
                application.applicationId()
        ));
        applicationStore.markApproved(applicationId, reviewedBy, reviewReason);
        return created;
    }

    public ApiKeyApplicationRecord rejectApplication(String applicationId, String reviewedBy, String reviewReason) {
        return applicationStore.markRejected(applicationId, reviewedBy, reviewReason);
    }

    public CreatedApiKey createOperatorKey(CreateApiKeyCommand command) {
        CreateApiKeyCommand normalized = normalize(command);
        UserRecord owner = requireActiveUser(normalized.createdForUserId());
        if (credentialStore.getByPrincipalId(normalized.principalId()) != null) {
            throw new IllegalArgumentException("principal already has an API key credential: " + normalized.principalId());
        }
        if (credentialProjectionWriter.hasProjectedCredential(normalized.principalId())) {
            throw new IllegalArgumentException("principal already exists in submitter auth projection: " + normalized.principalId());
        }
        String rawSecret = generateSecret();
        String keyId = "ak_" + UUID.randomUUID();
        String keyPrefix = keyPrefix(rawSecret);
        Instant now = Instant.now();
        Map<String, String> attributes = new LinkedHashMap<>(normalized.attributes());
        attributes.put(ATTR_KEY_ID, keyId);
        attributes.put(ATTR_CREDENTIAL_OWNER, owner.userId());
        ApiKeyCredentialRecord record = new ApiKeyCredentialRecord(
                keyId,
                normalized.principalId(),
                owner.userId(),
                keyPrefix,
                CredentialHashing.sha256(rawSecret),
                normalized.projectScopes(),
                normalized.eventScopes(),
                normalized.permissions(),
                ApiKeyCredentialStatus.ACTIVE,
                normalized.applicationId(),
                normalized.createdBy(),
                now,
                normalized.expiresAt(),
                null,
                null,
                null,
                Map.copyOf(attributes)
        );
        credentialStore.create(record);
        try {
            projectActiveCredential(record, rawSecret);
        } catch (RuntimeException e) {
            credentialStore.revoke(record.keyId(), "system", "auth projection failed: " + e.getMessage());
            throw e;
        }
        return new CreatedApiKey(record, rawSecret);
    }

    public List<ApiKeyCredentialRecord> list() {
        return credentialStore.list();
    }

    public ApiKeyCredentialRecord get(String keyId) {
        return credentialStore.get(keyId);
    }

    public ApiKeyCredentialRecord revoke(String keyId, String revokedBy, String reason) {
        ApiKeyCredentialRecord revoked = credentialStore.revoke(keyId, revokedBy, reason);
        if (revoked != null) {
            projectDisabledCredential(revoked);
        }
        return revoked;
    }

    public List<ApiKeyCredentialRecord> disableCredentialsForUser(String userId, String disabledBy, String reason) {
        List<ApiKeyCredentialRecord> disabled = credentialStore.disableByUserId(userId, disabledBy, reason);
        for (ApiKeyCredentialRecord record : disabled) {
            projectDisabledCredential(record);
        }
        return disabled;
    }

    public PrincipalContext validateAuthenticatedPrincipal(PrincipalContext principal) {
        if (principal == null) {
            return null;
        }
        String keyId = apiKeyId(principal);
        if (keyId == null) {
            return principal;
        }
        ApiKeyCredentialRecord record = credentialStore.get(keyId);
        if (record == null) {
            return null;
        }
        ApiKeyCredentialRecord current = expireIfNeeded(record);
        return current.status() == ApiKeyCredentialStatus.ACTIVE ? principal : null;
    }

    public boolean isCredentialActive(String keyId) {
        ApiKeyCredentialRecord record = credentialStore.get(keyId);
        if (record == null) {
            return false;
        }
        return expireIfNeeded(record).status() == ApiKeyCredentialStatus.ACTIVE;
    }

    public String apiKeyId(PrincipalContext principal) {
        if (principal == null || principal.getAttributes() == null) {
            return null;
        }
        String keyId = principal.getAttributes().get(ATTR_KEY_ID);
        return keyId == null || keyId.isBlank() ? null : keyId.trim();
    }

    private ApiKeyCredentialRecord expireIfNeeded(ApiKeyCredentialRecord record) {
        if (record.status() != ApiKeyCredentialStatus.ACTIVE
                || record.expiresAt() == null
                || record.expiresAt().isAfter(Instant.now())) {
            return record;
        }
        ApiKeyCredentialRecord expired = credentialStore.expire(record.keyId());
        if (expired != null && expired.status() == ApiKeyCredentialStatus.EXPIRED) {
            projectDisabledCredential(expired);
            return expired;
        }
        return record;
    }

    private void projectActiveCredential(ApiKeyCredentialRecord record, String rawSecret) {
        credentialProjectionWriter.projectCredential(SubmitterRegistration.builder()
                .principalId(record.principalId())
                .principalType(PrincipalType.SERVICE)
                .credential(rawSecret)
                .keyPrefix(record.keyPrefix())
                .userId(record.createdForUserId())
                .permissions(record.permissions())
                .projectScopes(record.projectScopes())
                .eventScopes(record.eventScopes())
                .enabled(true)
                .attributes(record.attributes())
                .build());
    }

    private void projectDisabledCredential(ApiKeyCredentialRecord record) {
        credentialProjectionWriter.projectCredential(SubmitterRegistration.builder()
                .principalId(record.principalId())
                .principalType(PrincipalType.SERVICE)
                .credential("revoked-" + record.keyId() + "-" + UUID.randomUUID())
                .keyPrefix(record.keyPrefix())
                .userId(record.createdForUserId())
                .permissions(record.permissions())
                .projectScopes(record.projectScopes())
                .eventScopes(record.eventScopes())
                .enabled(false)
                .attributes(record.attributes())
                .build());
    }

    private CreateApiKeyCommand normalize(CreateApiKeyCommand command) {
        CreateApiKeyCommand source = Objects.requireNonNull(command, "command");
        String principalId = requireNonBlank(source.principalId(), "principalId");
        String createdForUserId = requireNonBlank(source.createdForUserId(), "createdForUserId");
        List<String> permissions = normalizeList(source.permissions());
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions must not be empty");
        }
        validatePermissions(permissions);
        Instant expiresAt = source.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        return new CreateApiKeyCommand(
                principalId,
                createdForUserId,
                normalizeList(source.projectScopes()),
                normalizeList(source.eventScopes()),
                permissions,
                source.createdBy(),
                expiresAt,
                source.attributes() == null ? Map.of() : Map.copyOf(source.attributes()),
                normalizeOptional(source.applicationId())
        );
    }

    private UserRecord requireActiveUser(String userId) {
        UserRecord user = userStore.getUser(userId);
        if (user == null) {
            throw new IllegalArgumentException("createdForUserId does not exist: " + userId);
        }
        if (user.status() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("createdForUserId is not active: " + userId);
        }
        return user;
    }

    private void validatePermissions(List<String> permissions) {
        Set<String> known = new LinkedHashSet<>(ApiPermissionNames.ALL);
        for (String permission : permissions) {
            if (!known.contains(permission)) {
                throw new IllegalArgumentException("unknown permission: " + permission);
            }
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return RAW_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String keyPrefix(String rawSecret) {
        return rawSecret.substring(0, Math.min(16, rawSecret.length())) + "...";
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private CreateApplicationCommand normalize(CreateApplicationCommand command) {
        CreateApplicationCommand source = Objects.requireNonNull(command, "command");
        List<String> permissions = normalizeList(source.requestedPermissions());
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("requestedPermissions must not be empty");
        }
        String purpose = requireNonBlank(source.purpose(), "purpose");
        return new CreateApplicationCommand(
                requireNonBlank(source.applicantUserId(), "applicantUserId"),
                normalizeOptional(source.applicantName()),
                normalizeOptional(source.requestedPrincipalId()),
                normalizeOptional(source.requestedUserId()),
                normalizeList(source.requestedProjectScopes()),
                normalizeList(source.requestedEventScopes()),
                permissions,
                purpose,
                source.attributes() == null ? Map.of() : Map.copyOf(source.attributes())
        );
    }

    public record CreateApplicationCommand(String applicantUserId,
                                           String applicantName,
                                           String requestedPrincipalId,
                                           String requestedUserId,
                                           List<String> requestedProjectScopes,
                                           List<String> requestedEventScopes,
                                           List<String> requestedPermissions,
                                           String purpose,
                                           Map<String, String> attributes) {
    }

    public record CreateApiKeyCommand(String principalId,
                                      String createdForUserId,
                                      List<String> projectScopes,
                                      List<String> eventScopes,
                                      List<String> permissions,
                                      String createdBy,
                                      Instant expiresAt,
                                      Map<String, String> attributes,
                                      String applicationId) {
    }

    public record CreatedApiKey(ApiKeyCredentialRecord record, String rawSecret) {
    }
}
