package com.xa.mass.kernel.result;

record ResultRoutingConfig(int perOutcomeBatchLimit) {

    public ResultRoutingConfig {
        if (perOutcomeBatchLimit < 1) {
            throw new IllegalArgumentException(
                    "perOutcomeBatchLimit must be positive"
            );
        }
    }
}
