package com.xa.mass.sdk.catalog;

/**
 * Compatibility alias for the SDK metadata catalog.
 *
 * <p>New SDK surfaces should prefer {@link SdkMetadataCatalog} to avoid
 * implying that project membership is the canonical source of runtime event
 * truth.
 */
public interface ProjectEventCatalog extends SdkMetadataCatalog {
}
