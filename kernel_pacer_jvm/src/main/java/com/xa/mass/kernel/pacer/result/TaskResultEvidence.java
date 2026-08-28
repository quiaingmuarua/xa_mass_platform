package com.xa.mass.kernel.pacer.result;

record TaskResultEvidence(
        String taskId,
        String messageId,
        String opaqueResultPayload
) {
}
