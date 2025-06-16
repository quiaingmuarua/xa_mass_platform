package com.xa.mass.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class PongMessage extends BaseMessage {
    private Long timestamp;
    private Long serverTime;
} 