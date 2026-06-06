package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialRecord;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.session.ApiKeyViewerSessionService;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController {

    private final ApiKeyCredentialService credentialService;
    private final AuthProvider authProvider;
    private final ApiKeyViewerSessionService viewerSessionService;

    public ApiKeyController(ApiKeyCredentialService credentialService) {
        this(credentialService, null);
    }

    public ApiKeyController(ApiKeyCredentialService credentialService, AuthProvider authProvider) {
        this(credentialService, authProvider, null);
    }

    @Autowired
    public ApiKeyController(ApiKeyCredentialService credentialService,
                            AuthProvider authProvider,
                            ApiKeyViewerSessionService viewerSessionService) {
        this.credentialService = credentialService;
        this.authProvider = authProvider;
        this.viewerSessionService = viewerSessionService;
    }

    @PostMapping("/api-keys")
    public ApiResponse<ApiKeyCreateResponse> create(@RequestBody ApiKeyCreateRequest request,
                                                    HttpServletRequest servletRequest) {
        PrincipalContext operator = authenticatedPrincipal(servletRequest);
        ApiKeyCredentialService.CreatedApiKey created = credentialService.createOperatorKey(
                new ApiKeyCredentialService.CreateApiKeyCommand(
                        request.principalId(),
                        request.createdForUserId(),
                        request.projectScopes(),
                        request.eventScopes(),
                        request.permissions(),
                        operator == null ? null : operator.getPrincipalId(),
                        request.expiresAt(),
                        request.attributes(),
                        null,
                        null
                )
        );
        return ApiResponse.success(new ApiKeyCreateResponse(toView(created.record()), created.rawSecret()));
    }

    @GetMapping("/api-keys")
    public ApiResponse<List<ApiKeyCredentialView>> list() {
        return ApiResponse.success(credentialService.list().stream()
                .map(this::toView)
                .toList());
    }

    @GetMapping("/api-keys:current")
    public ResponseEntity<ApiResponse<Map<String, Object>>> current(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        PrincipalContext principal = authenticateApiKeyPrincipal(apiKeyHeader, authorizationHeader);
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API key credential"));
        }
        String keyId = credentialService.apiKeyId(principal);
        if (keyId == null) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Current credential is not an API key principal"));
        }
        ApiKeyCredentialRecord record = credentialService.get(keyId);
        if (record == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "API key credential is no longer active"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("credential", toView(record));
        data.put("principalId", principal.getPrincipalId());
        data.put("principalType", principal.getPrincipalType().name());
        data.put("userId", principal.getUserId());
        data.put("projectScope", principal.getProjectScope());
        data.put("permissions", principal.getPermissions());
        data.put("projectScopes", principal.getProjectScopes());
        data.put("eventScopes", principal.getEventScopes());
        data.put("attributes", principal.getAttributes());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/api-keys/{keyId}")
    public ResponseEntity<ApiResponse<ApiKeyCredentialView>> get(@PathVariable String keyId) {
        ApiKeyCredentialRecord record = credentialService.get(keyId);
        if (record == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "API key not found: " + keyId));
        }
        return ResponseEntity.ok(ApiResponse.success(toView(record)));
    }

    @PostMapping("/api-keys/{keyId}:revoke")
    public ResponseEntity<ApiResponse<ApiKeyCredentialView>> revoke(@PathVariable String keyId,
                                                                    @RequestBody(required = false) ApiKeyRevokeRequest request,
                                                                    HttpServletRequest servletRequest) {
        PrincipalContext operator = authenticatedPrincipal(servletRequest);
        ApiKeyCredentialRecord revoked = credentialService.revoke(
                keyId,
                operator == null ? null : operator.getPrincipalId(),
                request == null ? null : request.reason()
        );
        if (revoked == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "API key not found: " + keyId));
        }
        return ResponseEntity.ok(ApiResponse.success(toView(revoked)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    private PrincipalContext authenticatedPrincipal(HttpServletRequest request) {
        Object principal = request.getAttribute(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR);
        return principal instanceof PrincipalContext context ? context : null;
    }

    private PrincipalContext authenticateApiKeyPrincipal(String apiKeyHeader, String authorizationHeader) {
        String credential = SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader);
        if (credential == null) {
            return null;
        }
        PrincipalContext principal = authProvider == null ? null : authProvider.authenticate(credential);
        principal = credentialService.validateAuthenticatedPrincipal(principal);
        if (principal != null) {
            return principal;
        }
        return viewerSessionService == null ? null : viewerSessionService.authenticate(credential);
    }

    private ApiKeyCredentialView toView(ApiKeyCredentialRecord record) {
        return ApiKeyCredentialView.from(record);
    }

    public record ApiKeyCreateRequest(String principalId,
                                      String createdForUserId,
                                      List<String> projectScopes,
                                      List<String> eventScopes,
                                      List<String> permissions,
                                      Instant expiresAt,
                                      Map<String, String> attributes) {
    }

    public record ApiKeyRevokeRequest(String reason) {
    }

    public record ApiKeyCreateResponse(ApiKeyCredentialView credential, String rawSecret) {
    }

    public record ApiKeyCredentialView(String keyId,
                                       String principalId,
                                       String createdForUserId,
                                       String keyPrefix,
                                       List<String> projectScopes,
                                       List<String> eventScopes,
                                       List<String> permissions,
                                       String status,
                                       String applicationId,
                                       String createdBy,
                                       Instant createdAt,
                                       Instant expiresAt,
                                       Instant revokedAt,
                                       String revokedBy,
                                       String revokeReason,
                                       Map<String, String> attributes) {

        public static ApiKeyCredentialView from(ApiKeyCredentialRecord record) {
            return new ApiKeyCredentialView(
                    record.keyId(),
                    record.principalId(),
                    record.createdForUserId(),
                    record.keyPrefix(),
                    record.projectScopes(),
                    record.eventScopes(),
                    record.permissions(),
                    record.status().name(),
                    record.applicationId(),
                    record.createdBy(),
                    record.createdAt(),
                    record.expiresAt(),
                    record.revokedAt(),
                    record.revokedBy(),
                    record.revokeReason(),
                    record.attributes()
            );
        }
    }
}
