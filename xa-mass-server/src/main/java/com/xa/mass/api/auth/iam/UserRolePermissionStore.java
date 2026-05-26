package com.xa.mass.api.auth.iam;

import java.util.List;

public interface UserRolePermissionStore {

    List<UserRecord> listUsers();

    UserRecord getUser(String userId);

    UserRecord createUser(UserRecord user);

    UserRecord updateUser(UserRecord user);

    List<RoleRecord> listRoles();

    RoleRecord getRole(String roleId);

    List<UserRoleBindingRecord> listRoleBindings(String userId);

    UserRoleBindingRecord bindRole(UserRoleBindingRecord binding);

    boolean unbindRole(String userId, String roleId);

    List<String> listPermissionNames();
}
