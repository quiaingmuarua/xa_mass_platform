package com.xa.mass.api.internal;

import com.xa.mass.api.auth.usage.ApiUsageLedgerRecord;
import com.xa.mass.api.auth.usage.ApiUsageLedgerService;
import com.xa.mass.api.auth.usage.ApiUsageOperation;
import com.xa.mass.api.auth.usage.ApiUsageStatus;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.session.ApiKeyViewerSessionService;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiUsageController {

    private static final int DEFAULT_USAGE_LIMIT = 500;
    private static final int MAX_USAGE_LIMIT = 1000;

    private final ApiUsageLedgerService usageLedgerService;
    private final AuthProvider authProvider;
    private final ApiKeyCredentialService apiKeyCredentialService;
    private final ApiKeyViewerSessionService apiKeyViewerSessionService;

    public ApiUsageController(ApiUsageLedgerService usageLedgerService,
                              AuthProvider authProvider) {
        this(usageLedgerService, authProvider, null, null);
    }

    @Autowired
    public ApiUsageController(ApiUsageLedgerService usageLedgerService,
                              AuthProvider authProvider,
                              ApiKeyCredentialService apiKeyCredentialService,
                              ApiKeyViewerSessionService apiKeyViewerSessionService) {
        this.usageLedgerService = usageLedgerService;
        this.authProvider = authProvider;
        this.apiKeyCredentialService = apiKeyCredentialService;
        this.apiKeyViewerSessionService = apiKeyViewerSessionService;
    }

    @GetMapping("/api-keys/{keyId}/usage")
    public ApiResponse<Map<String, Object>> listApiKeyUsage(@PathVariable String keyId,
                                                            @RequestParam(required = false) String project,
                                                            @RequestParam(required = false) ApiUsageOperation operation,
                                                            @RequestParam(required = false) ApiUsageStatus status,
                                                            @RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to,
                                                            @RequestParam(required = false) Integer limit) {
        List<ApiUsageLedgerRecord> items = filterUsage(
                usageLedgerService.listByKeyId(keyId),
                project,
                operation,
                status,
                parseInstant(from),
                parseInstant(to),
                limit
        );
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @GetMapping("/api-keys:current/usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCurrentApiKeyUsage(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) ApiUsageOperation operation,
            @RequestParam(required = false) ApiUsageStatus status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        PrincipalContext apiKeyPrincipal = authenticateApiKeyOrViewerSession(apiKeyHeader, authorizationHeader);
        if (apiKeyPrincipal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API key credential"));
        }
        String keyId = usageLedgerService.apiKeyId(apiKeyPrincipal);
        if (keyId == null) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Current credential is not an API key principal"));
        }
        List<ApiUsageLedgerRecord> items = filterUsage(
                usageLedgerService.listByKeyId(keyId),
                project,
                operation,
                status,
                parseInstant(from),
                parseInstant(to),
                limit
        );
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "keyId", keyId,
                "principalId", apiKeyPrincipal.getPrincipalId(),
                "items", items,
                "total", items.size()
        )));
    }

    private List<ApiUsageLedgerRecord> filterUsage(List<ApiUsageLedgerRecord> records,
                                                   String project,
                                                   ApiUsageOperation operation,
                                                   ApiUsageStatus status,
                                                   Instant from,
                                                   Instant to,
                                                   Integer limit) {
        String normalizedProject = normalize(project);
        int resolvedLimit = resolveLimit(limit);
        return records.stream()
                .filter(record -> normalizedProject == null || normalizedProject.equals(record.project()))
                .filter(record -> operation == null || operation == record.operation())
                .filter(record -> status == null || status == record.status())
                .filter(record -> from == null || !record.createdAt().isBefore(from))
                .filter(record -> to == null || !record.createdAt().isAfter(to))
                .limit(resolvedLimit)
                .toList();
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_USAGE_LIMIT;
        }
        return Math.min(limit, MAX_USAGE_LIMIT);
    }

    private Instant parseInstant(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : Instant.parse(normalized);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PrincipalContext authenticateApiKeyOrViewerSession(String apiKeyHeader, String authorizationHeader) {
        String credential = SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader);
        if (credential == null) {
            return null;
        }
        PrincipalContext principal = authProvider == null ? null : authProvider.authenticate(credential);
        if (apiKeyCredentialService != null) {
            principal = apiKeyCredentialService.validateAuthenticatedPrincipal(principal);
        }
        if (principal != null) {
            return principal;
        }
        return apiKeyViewerSessionService == null ? null : apiKeyViewerSessionService.authenticate(credential);
    }
}
