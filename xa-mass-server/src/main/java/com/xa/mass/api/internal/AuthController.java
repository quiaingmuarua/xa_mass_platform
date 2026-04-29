package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiCurrentUser;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ApiAuthService apiAuthService;

    public AuthController(ApiAuthService apiAuthService) {
        this.apiAuthService = apiAuthService;
    }

    @GetMapping("/me")
    public ApiResponse<ApiCurrentUser> getCurrentUser(HttpServletRequest request) {
        PrincipalContext principal = apiAuthService.requireAuthenticated(request);
        return ApiResponse.success(apiAuthService.toApiCurrentUser(principal));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        PrincipalContext principal = apiAuthService.requireAuthenticated(request);
        return ApiResponse.success(Map.of(
                "message", "Logout acknowledged",
                "userId", principal.getPrincipalId()
        ));
    }
}
