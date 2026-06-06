package com.xa.mass.api.internal;

import com.xa.mass.api.auth.session.ApiKeyViewerSessionRecord;
import com.xa.mass.api.auth.session.ApiKeyViewerSessionService;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyViewerSessionController {

    private final ApiKeyViewerSessionService sessionService;

    public ApiKeyViewerSessionController(ApiKeyViewerSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/api-key-viewer-sessions")
    public ResponseEntity<ApiResponse<ApiKeyViewerSessionCreateResponse>> create(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String credential = SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader);
        if (credential == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API key credential"));
        }
        ApiKeyViewerSessionService.CreatedApiKeyViewerSession created = sessionService.create(credential);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new ApiKeyViewerSessionCreateResponse(
                ApiKeyViewerSessionView.from(created.record()),
                created.rawSecret()
        )));
    }

    @GetMapping("/api-key-viewer-sessions/me")
    public ResponseEntity<ApiResponse<ApiKeyViewerSessionView>> current(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        ApiKeyViewerSessionRecord current = sessionService.current(
                SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader));
        if (current == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API-key viewer session"));
        }
        return ResponseEntity.ok(ApiResponse.success(ApiKeyViewerSessionView.from(current)));
    }

    @PostMapping("/api-key-viewer-sessions:logout")
    public ResponseEntity<ApiResponse<ApiKeyViewerSessionView>> logout(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        ApiKeyViewerSessionRecord revoked = sessionService.logout(
                SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader));
        if (revoked == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API-key viewer session"));
        }
        return ResponseEntity.ok(ApiResponse.success(ApiKeyViewerSessionView.from(revoked)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    public record ApiKeyViewerSessionCreateResponse(ApiKeyViewerSessionView session,
                                                       String rawSecret) {
    }

    public record ApiKeyViewerSessionView(String sessionId,
                                             String keyId,
                                             String principalId,
                                             String createdForUserId,
                                             String keyPrefix,
                                             List<String> permissions,
                                             List<String> projectScopes,
                                             List<String> eventScopes,
                                             Map<String, String> attributes,
                                             Instant createdAt,
                                             Instant expiresAt,
                                             Instant revokedAt) {

        static ApiKeyViewerSessionView from(ApiKeyViewerSessionRecord record) {
            return new ApiKeyViewerSessionView(
                    record.sessionId(),
                    record.keyId(),
                    record.principalId(),
                    record.createdForUserId(),
                    record.keyPrefix(),
                    record.permissions(),
                    record.projectScopes(),
                    record.eventScopes(),
                    record.attributes(),
                    record.createdAt(),
                    record.expiresAt(),
                    record.revokedAt()
            );
        }
    }
}
