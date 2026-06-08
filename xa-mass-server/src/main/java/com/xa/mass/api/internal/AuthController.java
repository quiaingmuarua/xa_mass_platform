package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiCurrentUser;
import com.xa.mass.api.auth.OperatorAuthMode;
import com.xa.mass.api.auth.OperatorAuthProperties;
import com.xa.mass.api.auth.operator.OperatorCredentialVerifier;
import com.xa.mass.api.auth.operator.OperatorSessionRecord;
import com.xa.mass.api.auth.operator.OperatorSessionService;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final ApiAuthService apiAuthService;
    private final OperatorAuthProperties operatorAuthProperties;
    private final OperatorCredentialVerifier credentialVerifier;
    private final OperatorSessionService operatorSessionService;

    public AuthController(ApiAuthService apiAuthService) {
        this(apiAuthService, OperatorAuthProperties.devHeaderForTests(), null, null);
    }

    public AuthController(ApiAuthService apiAuthService,
                          OperatorAuthProperties operatorAuthProperties) {
        this(apiAuthService, operatorAuthProperties, null, null);
    }

    @Autowired
    public AuthController(ApiAuthService apiAuthService,
                          OperatorAuthProperties operatorAuthProperties,
                          OperatorCredentialVerifier credentialVerifier,
                          OperatorSessionService operatorSessionService) {
        this.apiAuthService = apiAuthService;
        this.operatorAuthProperties = operatorAuthProperties;
        this.credentialVerifier = credentialVerifier;
        this.operatorSessionService = operatorSessionService;
    }

    @GetMapping("/config")
    public ApiResponse<OperatorAuthConfigView> config() {
        OperatorAuthMode mode = operatorAuthProperties.mode();
        return ApiResponse.success(new OperatorAuthConfigView(
                mode.configValue(),
                mode == OperatorAuthMode.DEV_HEADER,
                mode == OperatorAuthMode.SESSION,
                mode == OperatorAuthMode.SESSION ? "X-Mass-Csrf-Token" : null
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<OperatorLoginView>> login(@RequestBody(required = false) OperatorLoginRequest request,
                                                                HttpServletResponse response) {
        if (operatorAuthProperties.mode() != OperatorAuthMode.SESSION
                || credentialVerifier == null
                || operatorSessionService == null
                || request == null
                || !credentialVerifier.verify(request.userId(), request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "Invalid operator credentials"));
        }
        PrincipalContext principal = apiAuthService.requireKnownOperatorPrincipal(request.userId());
        OperatorSessionRecord session = operatorSessionService.createSession(principal.getPrincipalId());
        operatorSessionService.writeSessionCookie(response, session);
        return ResponseEntity.ok(ApiResponse.success(new OperatorLoginView(
                apiAuthService.toApiCurrentUser(principal),
                session.csrfToken()
        )));
    }

    @GetMapping("/me")
    public ApiResponse<OperatorCurrentUserView> getCurrentUser(HttpServletRequest request) {
        PrincipalContext principal = apiAuthService.requireAuthenticated(request);
        return ApiResponse.success(OperatorCurrentUserView.from(
                apiAuthService.toApiCurrentUser(principal),
                currentCsrfToken(request)
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request,
                                                   HttpServletResponse response) {
        PrincipalContext principal = apiAuthService.requireAuthenticated(request);
        if (operatorAuthProperties.mode() == OperatorAuthMode.SESSION && operatorSessionService != null) {
            operatorSessionService.revokeCurrent(request);
            operatorSessionService.clearSessionCookie(response);
        }
        return ApiResponse.success(Map.of(
                "message", "Logout acknowledged",
                "userId", principal.getPrincipalId()
        ));
    }

    public record OperatorAuthConfigView(String authMode,
                                         boolean operatorHeaderSupported,
                                         boolean sessionCookieSupported,
                                         String csrfHeaderName) {
    }

    public record OperatorLoginRequest(String userId, String password) {
    }

    public record OperatorLoginView(ApiCurrentUser user, String csrfToken) {
    }

    public record OperatorCurrentUserView(String id,
                                          String name,
                                          String email,
                                          List<String> roles,
                                          List<String> permissions,
                                          String csrfToken) {
        static OperatorCurrentUserView from(ApiCurrentUser user, String csrfToken) {
            return new OperatorCurrentUserView(
                    user.id(),
                    user.name(),
                    user.email(),
                    user.roles(),
                    user.permissions(),
                    csrfToken
            );
        }
    }

    private String currentCsrfToken(HttpServletRequest request) {
        if (operatorAuthProperties.mode() != OperatorAuthMode.SESSION || operatorSessionService == null) {
            return null;
        }
        OperatorSessionRecord session = operatorSessionService.resolve(request);
        return session == null ? null : session.csrfToken();
    }
}
