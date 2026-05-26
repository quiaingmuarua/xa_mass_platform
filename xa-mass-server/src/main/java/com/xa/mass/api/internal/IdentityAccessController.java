package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.iam.RoleRecord;
import com.xa.mass.api.auth.iam.UserRecord;
import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserRoleBindingRecord;
import com.xa.mass.api.auth.iam.UserStatus;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class IdentityAccessController {

    private final UserRolePermissionStore store;
    private final ApiKeyCredentialService apiKeyCredentialService;

    public IdentityAccessController(UserRolePermissionStore store) {
        this(store, null);
    }

    @Autowired
    public IdentityAccessController(UserRolePermissionStore store,
                                    ApiKeyCredentialService apiKeyCredentialService) {
        this.store = store;
        this.apiKeyCredentialService = apiKeyCredentialService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserRecord>> listUsers() {
        return ApiResponse.success(store.listUsers());
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserRecord>> getUser(@PathVariable String userId) {
        UserRecord user = store.getUser(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "User not found: " + userId));
        }
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<UserRecord>> createUser(@RequestBody(required = false) CreateUserRequest request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "request body is required"));
            }
            Instant now = Instant.now();
            UserRecord created = store.createUser(new UserRecord(
                    requireNonBlank(request.userId(), "userId"),
                    defaultIfBlank(request.displayName(), request.userId()),
                    normalizeOptional(request.email()),
                    request.status() == null ? UserStatus.ACTIVE : request.status(),
                    request.attributes() == null ? Map.of() : request.attributes(),
                    now,
                    now
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PatchMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserRecord>> updateUser(@PathVariable String userId,
                                                              @RequestBody(required = false) UpdateUserRequest request,
                                                              HttpServletRequest httpRequest) {
        if (request == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "request body is required"));
        }
        UserRecord existing = store.getUser(userId);
        if (existing == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "User not found: " + userId));
        }
        UserStatus targetStatus = request.status() == null ? existing.status() : request.status();
        UserRecord updated = store.updateUser(new UserRecord(
                existing.userId(),
                request.displayName() == null ? existing.displayName() : request.displayName(),
                request.email() == null ? existing.email() : normalizeOptional(request.email()),
                targetStatus,
                request.attributes() == null ? existing.attributes() : request.attributes(),
                existing.createdAt(),
                Instant.now()
        ));
        if (updated == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "User not found: " + userId));
        }
        if (targetStatus != UserStatus.ACTIVE && existing.status() == UserStatus.ACTIVE && apiKeyCredentialService != null) {
            apiKeyCredentialService.disableCredentialsForUser(
                    updated.userId(),
                    currentPrincipalId(httpRequest),
                    "user status changed to " + targetStatus
            );
        }
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<ApiResponse<UserRoleBindingRecord>> bindRole(@PathVariable String userId,
                                                                       @PathVariable String roleId,
                                                                       HttpServletRequest request) {
        try {
            UserRoleBindingRecord binding = store.bindRole(new UserRoleBindingRecord(
                    requireNonBlank(userId, "userId"),
                    requireNonBlank(roleId, "roleId"),
                    currentPrincipalId(request),
                    Instant.now()
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(binding));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unbindRole(@PathVariable String userId,
                                                                       @PathVariable String roleId) {
        boolean removed = store.unbindRole(userId, roleId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "userId", userId,
                "roleId", roleId,
                "removed", removed
        )));
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleRecord>> listRoles() {
        return ApiResponse.success(store.listRoles());
    }

    @PostMapping("/roles")
    public ResponseEntity<ApiResponse<RoleRecord>> createRole(@RequestBody(required = false) CreateRoleRequest request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "request body is required"));
            }
            RoleRecord created = store.createRole(new RoleRecord(
                    requireNonBlank(request.roleId(), "roleId"),
                    requireNonBlank(request.name(), "name"),
                    normalizeOptional(request.description()),
                    normalizePermissions(request.permissions()),
                    false,
                    Instant.now()
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/roles/{roleId}")
    public ResponseEntity<ApiResponse<RoleRecord>> getRole(@PathVariable String roleId) {
        RoleRecord role = store.getRole(roleId);
        if (role == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Role not found: " + roleId));
        }
        return ResponseEntity.ok(ApiResponse.success(role));
    }

    @PatchMapping("/roles/{roleId}")
    public ResponseEntity<ApiResponse<RoleRecord>> updateRole(@PathVariable String roleId,
                                                              @RequestBody(required = false) UpdateRoleRequest request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "request body is required"));
            }
            RoleRecord existing = store.getRole(roleId);
            if (existing == null) {
                return ResponseEntity.status(404).body(ApiResponse.error(404, "Role not found: " + roleId));
            }
            if (existing.systemRole()) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "system roles cannot be updated"));
            }
            RoleRecord updated = store.updateRole(new RoleRecord(
                    existing.roleId(),
                    request.name() == null ? existing.name() : requireNonBlank(request.name(), "name"),
                    request.description() == null ? existing.description() : normalizeOptional(request.description()),
                    request.permissions() == null ? existing.permissions() : normalizePermissions(request.permissions()),
                    existing.systemRole(),
                    Instant.now()
            ));
            if (updated == null) {
                return ResponseEntity.status(404).body(ApiResponse.error(404, "Role not found: " + roleId));
            }
            return ResponseEntity.ok(ApiResponse.success(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/permissions")
    public ApiResponse<List<String>> listPermissions() {
        return ApiResponse.success(store.listPermissionNames());
    }

    private String currentPrincipalId(HttpServletRequest request) {
        Object principal = request == null ? null : request.getAttribute(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR);
        if (principal instanceof PrincipalContext context && context.getPrincipalId() != null) {
            return context.getPrincipalId();
        }
        return "unknown";
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Set<String> normalizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions must not be empty");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String permission : permissions) {
            normalized.add(requireNonBlank(permission, "permission"));
        }
        return normalized;
    }

    public record CreateUserRequest(String userId,
                                    String displayName,
                                    String email,
                                    UserStatus status,
                                    Map<String, String> attributes) {
    }

    public record UpdateUserRequest(String displayName,
                                    String email,
                                    UserStatus status,
                                    Map<String, String> attributes) {
    }

    public record CreateRoleRequest(String roleId,
                                    String name,
                                    String description,
                                    List<String> permissions) {
    }

    public record UpdateRoleRequest(String name,
                                    String description,
                                    List<String> permissions) {
    }
}
