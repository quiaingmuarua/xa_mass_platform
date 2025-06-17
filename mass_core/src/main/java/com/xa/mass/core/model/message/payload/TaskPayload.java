package com.xa.mass.core.model.message.payload;

import com.xa.mass.core.model.message.TaskStep;
import lombok.Data;

import java.util.List;

@Data
public class TaskPayload {

    List<TaskStep> steps;
}
