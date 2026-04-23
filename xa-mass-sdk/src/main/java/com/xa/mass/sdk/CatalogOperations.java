package com.xa.mass.sdk;

/**
 * SDK catalog and control-plane resource operations.
 *
 * <p>The catalog is the SDK-facing project/event directory used by clients,
 * examples, and platform shells to discover supported capabilities.
 *
 * @deprecated Prefer {@link ResourceOperations}. This name remains as a
 * compatibility alias for callers that already depend on the catalog wording.
 */
@Deprecated(forRemoval = false)
public interface CatalogOperations extends ResourceOperations {
}
