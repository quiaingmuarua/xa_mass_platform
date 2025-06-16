package com.xa.mass.model.message;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class MessageResult {
    private Integer code;
    private String message;
} 