package com.xa.mass.transport.runtime;

/**
 * Narrow host-provided capability surface for embedded Java adapter bootstrap.
 */
public interface AdapterBootstrapCapabilities {

    AdapterBootstrapAssignment assignment();

    AdapterMailboxCapabilities mailbox();

    AdapterSessionEvidenceCapabilities sessionEvidence();

    AdapterIngressCapabilities ingress();

    AdapterHostResources hostResources();
}
