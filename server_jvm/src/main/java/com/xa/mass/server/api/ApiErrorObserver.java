package com.xa.mass.server.api;

import com.xa.mass.server.error.ServerErrorCode;

/** Passive, low-cardinality observer for API error responses; exceptions are ignored. */
@FunctionalInterface
public interface ApiErrorObserver {
    ApiErrorObserver NOOP = (errorCode, httpStatus) -> {};

    void onError(ServerErrorCode errorCode, int httpStatus);
}
