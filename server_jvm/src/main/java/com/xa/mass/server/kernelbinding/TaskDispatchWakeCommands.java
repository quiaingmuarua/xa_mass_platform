package com.xa.mass.server.kernelbinding;

import java.util.List;

public interface TaskDispatchWakeCommands {

    void wakeTaskDispatch(List<String> taskIds);
}
