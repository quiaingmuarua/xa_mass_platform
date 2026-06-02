package com.xa.mass.sdk;

public record SdkTaskResumeResult(
        boolean success,
        String status,
        String terminalReason,
        boolean completedToTerminal
) {
}
