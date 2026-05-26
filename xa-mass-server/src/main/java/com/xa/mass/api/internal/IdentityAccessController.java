package com.xa.mass.api.internal;

import com.xa.mass.api.auth.iam.RoleRecord;
import com.xa.mass.api.auth.iam.UserRecord;
import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.model.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class IdentityAccessController {

    private final UserRolePermissionStore store;

    public IdentityAccessController(UserRolePermissionStore store) {
        this.store = store;
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

    @GetMapping("/roles")
    public ApiResponse<List<RoleRecord>> listRoles() {
        return ApiResponse.success(store.listRoles());
    }

    @GetMapping("/roles/{roleId}")
    public ResponseEntity<ApiResponse<RoleRecord>> getRole(@PathVariable String roleId) {
        RoleRecord role = store.getRole(roleId);
        if (role == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Role not found: " + roleId));
        }
        return ResponseEntity.ok(ApiResponse.success(role));
    }

    @GetMapping("/permissions")
    public ApiResponse<List<String>> listPermissions() {
        return ApiResponse.success(store.listPermissionNames());
    }
}
