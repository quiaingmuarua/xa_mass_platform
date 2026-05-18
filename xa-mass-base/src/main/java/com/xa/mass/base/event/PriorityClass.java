package com.xa.mass.base.event;

/**
 * Descriptive event priority metadata.
 *
 * <p>This is not a queue-placement decision. Runtime scheduling must consume a
 * dedicated policy owner before this metadata can affect ordering.
 */
public enum PriorityClass {
    CONTROL,
    INTERACTIVE,
    STANDARD,
    BULK
}
