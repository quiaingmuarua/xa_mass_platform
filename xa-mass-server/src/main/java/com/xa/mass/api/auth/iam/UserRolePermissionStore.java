package com.xa.mass.api.auth.iam;

import java.util.List;

public interface UserRolePermissionStore {

    List<UserRecord> listUsers();

    UserRecord getUser(String userId);

    List<RoleRecord> listRoles();

    RoleRecord getRole(String roleId);

    List<UserRoleBindingRecord> listRoleBindings(String userId);

    List<String> listPermissionNames();
}
