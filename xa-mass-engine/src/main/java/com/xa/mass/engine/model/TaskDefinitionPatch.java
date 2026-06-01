package com.xa.mass.engine.model;

import java.util.Map;

/**
 * Intent-shaped task definition mutation for cross-module command callers.
 */
public record TaskDefinitionPatch(
        String project,
        Map<String, Object> sharedConfig,
        String userId
) {
}
