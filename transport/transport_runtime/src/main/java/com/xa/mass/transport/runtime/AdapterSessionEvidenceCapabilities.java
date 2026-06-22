package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;

/**
 * Host-owned session evidence publisher construction for one adapter bootstrap.
 */
public interface AdapterSessionEvidenceCapabilities {

    AdapterSessionEvidencePublisher publisher();
}
