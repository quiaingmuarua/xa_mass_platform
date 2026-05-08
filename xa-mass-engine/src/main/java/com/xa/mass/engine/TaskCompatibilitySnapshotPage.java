package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;

/**
 * Bounded metadata returned from one compatibility snapshot traversal.
 */
@CompatibilityProjectionOnly
public record TaskCompatibilitySnapshotPage(int limit, boolean truncated, int returned) {

    public TaskCompatibilitySnapshotPage {
        limit = Math.max(0, limit);
        returned = Math.max(0, returned);
    }
}
