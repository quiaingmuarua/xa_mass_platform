package com.xa.mass.sdk;

import java.util.List;
import java.util.Map;

public interface DebugOperations {

    List<?> getWorkerMessageHistory(String workerId);

    Map<String, Object> sendWorkerMessage(String workerId,
                                          String project,
                                          String msgType,
                                          String subMsgType,
                                          Object payload);
}
