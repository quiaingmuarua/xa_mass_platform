package com.xa.mass.client.task;

import java.util.List;

public record TaskListResult(List<TaskView> items, int total) {
}
