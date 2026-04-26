package com.xa.mass.transport;

/**
 * Factory seam for constructing transport server adapters.
 *
 * <p>The runtime should depend on this factory instead of instantiating a
 * concrete transport implementation directly. The context type is adapter
 * specific so different runtimes can supply only the information their
 * transport server needs.
 *
 * @param <C> adapter-specific context passed to the factory
 */
public interface TransportServerFactory<C> {

    TransportServer create(C context);
}
