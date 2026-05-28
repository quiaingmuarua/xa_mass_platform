package com.xa.mass.client.task;

public record TaskAppendResult(String taskId, int added, String status, String intakeStatus, String message) {
}
