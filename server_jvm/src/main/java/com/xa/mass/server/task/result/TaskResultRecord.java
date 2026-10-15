package com.xa.mass.server.task.result;

import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import org.jspecify.annotations.Nullable;

/** One observed Result with its original input association, from Redis or archive. */
public record TaskResultRecord(String messageId, TaskItemResult result, @Nullable TaskItem item) { }
