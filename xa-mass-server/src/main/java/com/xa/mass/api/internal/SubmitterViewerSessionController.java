package com.xa.mass.api.internal;

import com.xa.mass.api.auth.session.SubmitterViewerSessionRecord;
import com.xa.mass.api.auth.session.SubmitterViewerSessionService;
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
public class SubmitterViewerSessionController {

    private final SubmitterViewerSessionService sessionService;

    public SubmitterViewerSessionController(SubmitterViewerSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/api-key-viewer-sessions")
    public ResponseEntity<ApiResponse<SubmitterViewerSessionCreateResponse>> create(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String credential = SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader);
        if (credential == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API key credential"));
        }
        SubmitterViewerSessionService.CreatedSubmitterViewerSession created = sessionService.create(credential);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new SubmitterViewerSessionCreateResponse(
                SubmitterViewerSessionView.from(created.record()),
                created.rawSecret()
        )));
    }

    @GetMapping("/api-key-viewer-sessions/me")
    public ResponseEntity<ApiResponse<SubmitterViewerSessionView>> current(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        SubmitterViewerSessionRecord current = sessionService.current(
                SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader));
        if (current == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API-key viewer session"));
        }
        return ResponseEntity.ok(ApiResponse.success(SubmitterViewerSessionView.from(current)));
    }

    @PostMapping("/api-key-viewer-sessions:logout")
    public ResponseEntity<ApiResponse<SubmitterViewerSessionView>> logout(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        SubmitterViewerSessionRecord revoked = sessionService.logout(
                SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader));
        if (revoked == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing API-key viewer session"));
        }
        return ResponseEntity.ok(ApiResponse.success(SubmitterViewerSessionView.from(revoked)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    public record SubmitterViewerSessionCreateResponse(SubmitterViewerSessionView session,
                                                       String rawSecret) {
    }

    public record SubmitterViewerSessionView(String sessionId,
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

        static SubmitterViewerSessionView from(SubmitterViewerSessionRecord record) {
            return new SubmitterViewerSessionView(
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
