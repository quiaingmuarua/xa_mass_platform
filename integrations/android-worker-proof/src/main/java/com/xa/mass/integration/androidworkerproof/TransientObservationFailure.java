package com.xa.mass.integration.androidworkerproof;

final class TransientObservationFailure extends RuntimeException {

    TransientObservationFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
