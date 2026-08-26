package com.xa.mass.kernel.pacer;

record ResultRoutingConfig(int perOutcomeBatchLimit) {

    public ResultRoutingConfig {
        if (perOutcomeBatchLimit < 1) {
            throw new IllegalArgumentException(
                    "perOutcomeBatchLimit must be positive"
            );
        }
    }
}
