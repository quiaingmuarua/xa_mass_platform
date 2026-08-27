package com.xa.mass.kernel.pacer;

record ResultRoutingApplicationConfig(long intervalMillis) {

    public static final int PER_OUTCOME_BATCH_LIMIT = 100;
    public static final long DEFAULT_INTERVAL_MILLIS = 100;
    private static final ResultRoutingConfig ROUTING =
            new ResultRoutingConfig(PER_OUTCOME_BATCH_LIMIT);

    public ResultRoutingApplicationConfig {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive"
            );
        }
    }

    ResultRoutingConfig routing() {
        return ROUTING;
    }
}
