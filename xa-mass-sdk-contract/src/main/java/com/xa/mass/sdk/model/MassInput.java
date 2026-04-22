package com.xa.mass.sdk.model;

import java.util.Map;

/**
 * SDK-level logical input payload.
 */
public interface MassInput {

    Map<String, Object> toTaskMsgInput();
}
