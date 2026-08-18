package com.xa.mass.workerdelivery.adapter.netty;

/** Finite encoded-data budget for one Adapter's Worker properties cache. */
public record NettyWorkerPropertiesCacheConfig(long maximumEncodedBytes) {

    public NettyWorkerPropertiesCacheConfig {
        if (maximumEncodedBytes <= 0) {
            throw new IllegalArgumentException(
                    "maximumEncodedBytes must be positive"
            );
        }
    }
}
