package com.xa.mass.transport.runtime.lease;

class InMemoryTransportEndpointLeaseStoreContractTest extends TransportEndpointLeaseStoreContractTest {

    @Override
    protected LeaseStoreFixture createFixture(long leaseMillis) {
        InMemoryTransportEndpointLeaseStore store =
                new InMemoryTransportEndpointLeaseStore(leaseMillis);
        return new LeaseStoreFixture(store, store, null);
    }
}
