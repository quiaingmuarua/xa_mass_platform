package com.xa.mass.transport.runtime.embedded;

/**
 * Reads protocol frames into minimal result facts without constructing result ingress entries.
 */
public interface AdapterResultFrameReader<T> {

    boolean isResultFrame(T frame);

    AdapterResultFrame read(T resultFrame);
}
