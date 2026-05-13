package com.xa.mass.runtime.api;

import java.time.Instant;

public record BarrierClaim(BarrierClaimStatus status,
                           String claimToken,
                           Instant claimedAt,
                           Instant expiresAt) {

    public static BarrierClaim claimed(String claimToken, Instant claimedAt, Instant expiresAt) {
        return new BarrierClaim(BarrierClaimStatus.CLAIMED, claimToken, claimedAt, expiresAt);
    }

    public static BarrierClaim alreadyDone() {
        return new BarrierClaim(BarrierClaimStatus.ALREADY_DONE, null, null, null);
    }

    public static BarrierClaim busy(String claimToken, Instant claimedAt, Instant expiresAt) {
        return new BarrierClaim(BarrierClaimStatus.BUSY, claimToken, claimedAt, expiresAt);
    }

    public static BarrierClaim rejected() {
        return new BarrierClaim(BarrierClaimStatus.REJECTED, null, null, null);
    }

    public static BarrierClaim unavailable() {
        return new BarrierClaim(BarrierClaimStatus.UNAVAILABLE, null, null, null);
    }

    public boolean claimedByCaller() {
        return status == BarrierClaimStatus.CLAIMED;
    }

    public boolean alreadyDoneByAnotherCaller() {
        return status == BarrierClaimStatus.ALREADY_DONE;
    }
}
