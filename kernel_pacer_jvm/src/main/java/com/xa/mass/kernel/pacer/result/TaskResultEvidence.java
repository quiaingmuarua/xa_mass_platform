package com.xa.mass.kernel.pacer;

record TaskResultEvidence(
        String taskId,
        String messageId,
        String opaqueResultPayload
) {
}
