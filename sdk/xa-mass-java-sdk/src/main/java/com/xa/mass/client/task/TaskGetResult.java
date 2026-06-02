package com.xa.mass.client.task;

import java.util.Map;

public record TaskGetResult(TaskView task, Map<String, Object> security) {
}
