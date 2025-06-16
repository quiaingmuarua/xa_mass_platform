package com.xa.mass.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class TaskMessage extends BaseMessage {
    private List<TaskStep> steps;
} 