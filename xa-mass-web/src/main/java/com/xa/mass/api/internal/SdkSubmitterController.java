package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/sdk/submitters")
public class SdkSubmitterController {

    private final AuthProvider authProvider;

    public SdkSubmitterController(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> currentSubmitter(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        TaskSubmitterContext submitter = SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing SDK credential"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("principalId", submitter.getPrincipalId());
        data.put("userId", submitter.getUserId());
        data.put("projectScope", submitter.getProjectScope());
        data.put("attributes", submitter.getAttributes());
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
