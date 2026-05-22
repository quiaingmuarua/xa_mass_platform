package com.xa.mass.runtime.api;

public record BarrierMarkResult(BarrierMarkStatus status, String reason) {

    public static BarrierMarkResult marked() {
        return new BarrierMarkResult(BarrierMarkStatus.MARKED, null);
    }

    public static BarrierMarkResult alreadyDone() {
        return new BarrierMarkResult(BarrierMarkStatus.ALREADY_DONE, null);
    }

    public static BarrierMarkResult tokenMismatch(String reason) {
        return new BarrierMarkResult(BarrierMarkStatus.TOKEN_MISMATCH, reason);
    }

    public static BarrierMarkResult rejected(String reason) {
        return new BarrierMarkResult(BarrierMarkStatus.REJECTED, reason);
    }

    public static BarrierMarkResult unavailable(String reason) {
        return new BarrierMarkResult(BarrierMarkStatus.UNAVAILABLE, reason);
    }

    public boolean completed() {
        return status == BarrierMarkStatus.MARKED || status == BarrierMarkStatus.ALREADY_DONE;
    }
}
