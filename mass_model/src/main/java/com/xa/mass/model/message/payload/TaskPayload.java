package com.xa.mass.model.message.payload;

import com.xa.mass.model.message.TaskStep;
import lombok.Data;

import java.util.List;

@Data
public class TaskPayload {

    List<TaskStep> steps;
}
