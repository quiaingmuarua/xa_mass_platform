package com.xa.mass.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class PingMessage extends BaseMessage {
    private Long timestamp;
} 