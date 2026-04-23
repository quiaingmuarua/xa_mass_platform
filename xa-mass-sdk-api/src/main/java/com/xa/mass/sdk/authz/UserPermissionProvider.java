package com.xa.mass.sdk.authz;

import java.util.Set;

/**
 * Resolves event allow-lists by user id.
 */
public interface UserPermissionProvider {

    Set<String> allowedEventCodes(String userId);
}
