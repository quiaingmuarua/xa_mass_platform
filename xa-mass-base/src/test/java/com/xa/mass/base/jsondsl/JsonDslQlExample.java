package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslQlExample {


    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);

        // 演示相对时间
        String dsl = """
                {
                  "MODEL": "Device",
                   "COUNT": 2,
                  "FIELDS": {
                    "status": {"$CHOICE": ["OFFLINE", "ONLINE"]},
                    "deviceId": {"$EXPR": "join('device-', '1')"},
                     "groupId": {"$JOIN": ["device-", "&.index"]},
                    "onlineStrategy": {
                      "$EXPR": {
                        "lang": "ql",
                        "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
                      }
                    }
                  }
                }
                """;
        Object result = com.xa.mass.base.jsondsl.JsonDslEngine.generate(dsl);
        List<Object> relativeTimeExamples = result instanceof List ? (List<Object>) result : List.of(result);
        relativeTimeExamples.forEach(System.out::println);
    }
}
