package com.xa.mass.kernel.task;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface TaskResourceCatalog {

    Map<String, @Nullable TaskDescriptor> loadTaskAllocationDescriptors(
            List<String> taskIds
    );
}
