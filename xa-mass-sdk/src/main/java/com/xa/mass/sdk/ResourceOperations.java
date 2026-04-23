package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ProjectEventCatalog;

/**
 * SDK control-plane resource operations.
 *
 * <p>This groups the current SDK-first resource types that can be created or
 * discovered without reaching into engine or web internals.
 */
public interface ResourceOperations extends ProjectOperations, EventOperations, SubmitterOperations {

    ProjectEventCatalog projectEventCatalog();
}
