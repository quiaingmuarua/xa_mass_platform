package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;

/**
 * SDK control-plane resource operations.
 *
 * <p>This groups the current SDK-first resource types that can be created or
 * discovered without reaching into engine or web internals.
 */
public interface ResourceOperations extends ProjectOperations, EventOperations, SubmitterOperations {

    /**
     * Preferred read surface for SDK project metadata and runtime-projected
     * event metadata.
     */
    default SdkMetadataCatalog metadataCatalog() {
        return projectEventCatalog();
    }

    /**
     * @deprecated Prefer {@link #metadataCatalog()} for new code.
     */
    @Deprecated(forRemoval = false)
    ProjectEventCatalog projectEventCatalog();
}
