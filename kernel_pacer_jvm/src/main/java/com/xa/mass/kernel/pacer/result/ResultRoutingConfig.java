package com.xa.mass.kernel.pacer;

record ResultRoutingConfig(int perResultClassBatchLimit) {

    public ResultRoutingConfig {
        if (perResultClassBatchLimit < 1) {
            throw new IllegalArgumentException(
                    "perResultClassBatchLimit must be positive"
            );
        }
    }
}
