package com.xa.mass.kernel.result;

record TaskResultEvidence(
        String taskId,
        String messageId,
        String opaqueResultPayload
) {
}
