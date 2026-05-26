package com.xa.mass.api.internal;

import com.xa.mass.api.auth.usage.ApiUsageLedgerRecord;
import com.xa.mass.api.auth.usage.ApiUsageLedgerService;
import com.xa.mass.api.auth.usage.ApiUsageOperation;
import com.xa.mass.api.auth.usage.ApiUsageStatus;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
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

    public ApiUsageController(ApiUsageLedgerService usageLedgerService,
                              AuthProvider authProvider) {
        this.usageLedgerService = usageLedgerService;
        this.authProvider = authProvider;
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

    @GetMapping("/submitters/me/usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCurrentSubmitterUsage(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) ApiUsageOperation operation,
            @RequestParam(required = false) ApiUsageStatus status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        PrincipalContext submitter = SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing submitter credential"));
        }
        String keyId = usageLedgerService.apiKeyId(submitter);
        if (keyId == null) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Current submitter is not an API key principal"));
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
                "principalId", submitter.getPrincipalId(),
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
}
