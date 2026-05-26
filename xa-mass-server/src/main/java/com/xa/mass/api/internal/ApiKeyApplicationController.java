package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.apikey.ApiKeyApplicationRecord;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/api-key-applications")
public class ApiKeyApplicationController {

    private final ApiKeyCredentialService credentialService;

    public ApiKeyApplicationController(ApiKeyCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ApiResponse<ApiKeyApplicationRecord> create(@RequestBody ApiKeyApplicationCreateRequest request,
                                                       HttpServletRequest servletRequest) {
        PrincipalContext applicant = authenticatedPrincipal(servletRequest);
        ApiKeyApplicationRecord created = credentialService.createApplication(
                new ApiKeyCredentialService.CreateApplicationCommand(
                        applicant == null ? null : applicant.getUserId(),
                        applicant == null ? null : applicant.getPrincipalId(),
                        request.requestedPrincipalId(),
                        request.requestedUserId(),
                        request.requestedProjectScopes(),
                        request.requestedEventScopes(),
                        request.requestedPermissions(),
                        request.purpose(),
                        request.attributes()
                )
        );
        return ApiResponse.success(created);
    }

    @GetMapping
    public ApiResponse<List<ApiKeyApplicationRecord>> list() {
        return ApiResponse.success(credentialService.listApplications());
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<ApiKeyApplicationRecord>> get(@PathVariable String applicationId) {
        ApiKeyApplicationRecord application = credentialService.getApplication(applicationId);
        if (application == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "API key application not found: " + applicationId));
        }
        return ResponseEntity.ok(ApiResponse.success(application));
    }

    @PostMapping("/{applicationId}:approve")
    public ResponseEntity<ApiResponse<ApiKeyController.ApiKeyCreateResponse>> approve(
            @PathVariable String applicationId,
            @RequestBody(required = false) ApiKeyApplicationReviewRequest request,
            HttpServletRequest servletRequest) {
        PrincipalContext reviewer = authenticatedPrincipal(servletRequest);
        ApiKeyCredentialService.CreatedApiKey approved = credentialService.approveApplication(
                applicationId,
                reviewer == null ? null : reviewer.getPrincipalId(),
                request == null ? null : request.reason(),
                request == null ? null : request.expiresAt()
        );
        if (approved == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "API key application not found: " + applicationId));
        }
        return ResponseEntity.ok(ApiResponse.success(new ApiKeyController.ApiKeyCreateResponse(
                ApiKeyController.ApiKeyCredentialView.from(approved.record()),
                approved.rawSecret()
        )));
    }

    @PostMapping("/{applicationId}:reject")
    public ResponseEntity<ApiResponse<ApiKeyApplicationRecord>> reject(
            @PathVariable String applicationId,
            @RequestBody(required = false) ApiKeyApplicationReviewRequest request,
            HttpServletRequest servletRequest) {
        PrincipalContext reviewer = authenticatedPrincipal(servletRequest);
        ApiKeyApplicationRecord rejected = credentialService.rejectApplication(
                applicationId,
                reviewer == null ? null : reviewer.getPrincipalId(),
                request == null ? null : request.reason()
        );
        if (rejected == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "API key application not found: " + applicationId));
        }
        return ResponseEntity.ok(ApiResponse.success(rejected));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    private PrincipalContext authenticatedPrincipal(HttpServletRequest request) {
        Object principal = request.getAttribute(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR);
        return principal instanceof PrincipalContext context ? context : null;
    }

    public record ApiKeyApplicationCreateRequest(String requestedPrincipalId,
                                                 String requestedUserId,
                                                 List<String> requestedProjectScopes,
                                                 List<String> requestedEventScopes,
                                                 List<String> requestedPermissions,
                                                 String purpose,
                                                 Map<String, String> attributes) {
    }

    public record ApiKeyApplicationReviewRequest(String reason, Instant expiresAt) {
    }
}
