package com.xa.mass.integration.androidworker;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class AndroidDeviceProperties {

    private final Context applicationContext;

    AndroidDeviceProperties(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        applicationContext = context.getApplicationContext();
    }

    Map<String, Object> workerProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("runtime", "android");
        properties.put("packageName", packageName());
        properties.put("versionName", versionName());
        properties.put("sdkInt", Build.VERSION.SDK_INT);
        properties.put("manufacturer", Build.MANUFACTURER);
        properties.put("model", Build.MODEL);
        return Collections.unmodifiableMap(properties);
    }

    String packageName() {
        return applicationContext.getPackageName();
    }

    String versionName() {
        try {
            PackageInfo info = applicationContext.getPackageManager()
                    .getPackageInfo(applicationContext.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }

    int sdkInt() {
        return Build.VERSION.SDK_INT;
    }

    String manufacturer() {
        return Build.MANUFACTURER;
    }

    String model() {
        return Build.MODEL;
    }
}
