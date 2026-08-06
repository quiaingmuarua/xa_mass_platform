package com.xa.mass.worker.android;

import android.content.Context;
import java.util.Map;

@FunctionalInterface
public interface AndroidWorkerProperties {

    Map<String, Object> getProperties(Context applicationContext);
}
