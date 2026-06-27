package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.runtime.TransportAdapterDescriptor;

/**
 * Factory for one embedded adapter runtime type.
 */
public interface EmbeddedTransportAdapterRuntimeFactory {

    String type();

    TransportAdapterDescriptor descriptor(EmbeddedAdapterRuntimeSpec spec);

    EmbeddedTransportAdapterRuntime create(EmbeddedAdapterRuntimeSpec spec,
                                           EmbeddedAdapterRuntimeEnvironment environment);
}
