package com.xa.mass.sdk.authz;

import java.util.Set;

/**
 * Resolves event allow-lists by client id.
 */
public interface ClientPermissionProvider {

    Set<String> allowedEventCodes(String clientId);
}
