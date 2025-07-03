package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslTimeExample {


    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);

        // 演示相对时间
        String relativeTimeExampleDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 2,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "createTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"},
                  "updateTime": {"$TIME_RANGE": ["now-2h", "now", "MINUTES", "yyyy-MM-dd HH:mm:ss"]}
                  }
                }
                """;
        List<Object> relativeTimeExamples = JsonDslEngine.generate(relativeTimeExampleDsl);
        System.out.println("\n=== Relative Time Examples ===");
        relativeTimeExamples.forEach(System.out::println);
    }

}
