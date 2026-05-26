package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.session.SubmitterViewerSessionService;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/submitters")
public class CurrentSubmitterController {

    private final AuthProvider authProvider;
    private final ApiKeyCredentialService apiKeyCredentialService;
    private final SubmitterViewerSessionService submitterViewerSessionService;

    public CurrentSubmitterController(AuthProvider authProvider) {
        this(authProvider, null, null);
    }

    @Autowired
    public CurrentSubmitterController(AuthProvider authProvider,
                                      ApiKeyCredentialService apiKeyCredentialService,
                                      SubmitterViewerSessionService submitterViewerSessionService) {
        this.authProvider = authProvider;
        this.apiKeyCredentialService = apiKeyCredentialService;
        this.submitterViewerSessionService = submitterViewerSessionService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> currentSubmitter(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        PrincipalContext submitter = authenticateSubmitter(apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing submitter credential"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("principalId", submitter.getPrincipalId());
        data.put("principalType", submitter.getPrincipalType().name());
        data.put("userId", submitter.getUserId());
        data.put("projectScope", submitter.getProjectScope());
        data.put("permissions", submitter.getPermissions());
        data.put("projectScopes", submitter.getProjectScopes());
        data.put("eventScopes", submitter.getEventScopes());
        data.put("attributes", submitter.getAttributes());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private PrincipalContext authenticateSubmitter(String apiKeyHeader, String authorizationHeader) {
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
        return submitterViewerSessionService == null ? null : submitterViewerSessionService.authenticate(credential);
    }
}
