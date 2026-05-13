package com.xa.mass.runtime.api;

public record BarrierClaim(BarrierClaimStatus status) {

    public static BarrierClaim claimed() {
        return new BarrierClaim(BarrierClaimStatus.CLAIMED);
    }

    public static BarrierClaim alreadyDone() {
        return new BarrierClaim(BarrierClaimStatus.ALREADY_DONE);
    }

    public static BarrierClaim busy() {
        return new BarrierClaim(BarrierClaimStatus.BUSY);
    }

    public static BarrierClaim rejected() {
        return new BarrierClaim(BarrierClaimStatus.REJECTED);
    }

    public boolean claimedByCaller() {
        return status == BarrierClaimStatus.CLAIMED;
    }

    public boolean alreadyDoneByAnotherCaller() {
        return status == BarrierClaimStatus.ALREADY_DONE;
    }
}
