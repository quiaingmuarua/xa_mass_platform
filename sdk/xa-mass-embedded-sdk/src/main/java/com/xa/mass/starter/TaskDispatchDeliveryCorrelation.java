package com.xa.mass.starter;

record TaskDispatchDeliveryCorrelation(String taskId,
                                       String messageId,
                                       String attemptId,
                                       int attemptNo) {

    TaskDispatchDeliveryCorrelation {
        taskId = requireText(taskId, "taskId");
        messageId = requireText(messageId, "messageId");
        attemptId = optionalText(attemptId);
        attemptNo = Math.max(0, attemptNo);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
