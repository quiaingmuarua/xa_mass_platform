package com.xa.mass.kernel.pacer.dispatch;

import java.util.Map;

@FunctionalInterface
interface TaskInitializationCheck {

    void check(Map<String, Long> initialTaskScores);
}
