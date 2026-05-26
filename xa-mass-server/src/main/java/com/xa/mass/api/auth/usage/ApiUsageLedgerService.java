package com.xa.mass.api.auth.usage;

import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ApiUsageLedgerService {

    public static final String CONTEXT_USAGE_OPERATION = "apiUsageOperation";
    public static final String CONTEXT_USAGE_REQUEST_ID = "apiUsageRequestId";
    public static final String CONTEXT_USAGE_MESSAGE_ID = "apiUsageMessageId";

    private final ApiUsageLedgerStore store;

    public ApiUsageLedgerService(ApiUsageLedgerStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public ApiUsageLedgerRecord recordAccepted(PrincipalContext principal,
                                               ApiUsageOperation operation,
                                               String project,
                                               String eventCode,
                                               String taskId,
                                               String messageId,
                                               String requestId,
                                               long units) {
        String keyId = apiKeyId(principal);
        if (keyId == null) {
            return null;
        }
        long acceptedUnits = Math.max(0, units);
        return store.append(new ApiUsageLedgerRecord(
                usageId(keyId, operation, ApiUsageStatus.ACCEPTED, taskId, messageId, requestId),
                keyId,
                principal.getPrincipalId(),
                principal.getUserId(),
                normalize(project),
                normalize(eventCode),
                Objects.requireNonNull(operation, "operation"),
                normalize(taskId),
                normalize(messageId),
                normalize(requestId),
                acceptedUnits,
                ApiUsageStatus.ACCEPTED,
                null,
                null,
                Instant.now()
        ));
    }

    public ApiUsageLedgerRecord recordRejected(PrincipalContext principal,
                                               ApiUsageOperation operation,
                                               String project,
                                               String eventCode,
                                               String taskId,
                                               String messageId,
                                               String requestId) {
        String keyId = apiKeyId(principal);
        if (keyId == null) {
            return null;
        }
        return store.append(new ApiUsageLedgerRecord(
                usageId(keyId, operation, ApiUsageStatus.REJECTED, taskId, messageId, requestId),
                keyId,
                principal.getPrincipalId(),
                principal.getUserId(),
                normalize(project),
                normalize(eventCode),
                Objects.requireNonNull(operation, "operation"),
                normalize(taskId),
                normalize(messageId),
                normalize(requestId),
                0,
                ApiUsageStatus.REJECTED,
                null,
                null,
                Instant.now()
        ));
    }

    public ApiUsageLedgerRecord recordFailedAfterAccept(PrincipalContext principal,
                                                        ApiUsageOperation operation,
                                                        String project,
                                                        String eventCode,
                                                        String taskId,
                                                        String messageId,
                                                        String requestId,
                                                        String failureReason,
                                                        Integer failureStatus) {
        String keyId = apiKeyId(principal);
        if (keyId == null) {
            return null;
        }
        return store.append(new ApiUsageLedgerRecord(
                usageId(keyId, operation, ApiUsageStatus.FAILED_AFTER_ACCEPT, taskId, messageId, requestId),
                keyId,
                principal.getPrincipalId(),
                principal.getUserId(),
                normalize(project),
                normalize(eventCode),
                Objects.requireNonNull(operation, "operation"),
                normalize(taskId),
                normalize(messageId),
                normalize(requestId),
                0,
                ApiUsageStatus.FAILED_AFTER_ACCEPT,
                normalize(failureReason),
                failureStatus,
                Instant.now()
        ));
    }

    public List<ApiUsageLedgerRecord> listByKeyId(String keyId) {
        return store.listByKeyId(keyId);
    }

    public List<ApiUsageLedgerRecord> listByPrincipalId(String principalId) {
        return store.listByPrincipalId(principalId);
    }

    public String apiKeyId(PrincipalContext principal) {
        if (principal == null || principal.getAttributes() == null) {
            return null;
        }
        String keyId = principal.getAttributes().get(ApiKeyCredentialService.ATTR_KEY_ID);
        return keyId == null || keyId.isBlank() ? null : keyId.trim();
    }

    public ApiUsageOperation operationFromContext(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object value = context.get(CONTEXT_USAGE_OPERATION);
        if (value instanceof ApiUsageOperation operation) {
            return operation;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return ApiUsageOperation.valueOf(stringValue.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public String stringFromContext(Map<String, Object> context, String key) {
        if (context == null || key == null) {
            return null;
        }
        Object value = context.get(key);
        return value == null ? null : normalize(String.valueOf(value));
    }

    private String usageId(String keyId,
                           ApiUsageOperation operation,
                           ApiUsageStatus status,
                           String taskId,
                           String messageId,
                           String requestId) {
        String normalizedRequestId = normalize(requestId);
        if (normalizedRequestId != null) {
            return keyId + ":" + operation + ":" + status + ":" + normalizedRequestId;
        }
        return keyId + ":" + operation + ":" + status + ":" + normalize(taskId) + ":" + normalize(messageId) + ":" + UUID.randomUUID();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
