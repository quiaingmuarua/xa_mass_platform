package com.xa.mass.android.workerdemo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.xa.mass.worker.android.AndroidWorkerProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class AndroidDeviceProperties implements AndroidWorkerProperties {

    private final Context applicationContext;

    AndroidDeviceProperties(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        applicationContext = context.getApplicationContext();
    }

    @Override
    public Map<String, String> getProperties(Context applicationContext) {
        if (applicationContext == null) {
            throw new IllegalArgumentException(
                    "applicationContext must be present"
            );
        }
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("runtime", "android");
        properties.put("packageName", packageName());
        properties.put("versionName", versionName());
        properties.put("sdkInt", Integer.toString(Build.VERSION.SDK_INT));
        properties.put("manufacturer", Build.MANUFACTURER);
        properties.put("model", Build.MODEL);
        properties.put("messaging.enabled", "true");
        properties.put("country", "CN");
        properties.put("phone", demoPhone());
        return Collections.unmodifiableMap(properties);
    }

    String packageName() {
        return applicationContext.getPackageName();
    }

    String demoPhone() {
        String id = packageName();
        String suffix = id.endsWith(".lab1") ? "1" : id.endsWith(".lab2") ? "2" : id.endsWith(".lab3") ? "3" : "0";
        return "+861700000000" + suffix;
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
