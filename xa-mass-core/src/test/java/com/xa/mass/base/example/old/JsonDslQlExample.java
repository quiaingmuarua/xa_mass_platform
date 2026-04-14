package com.xa.mass.base.example.old;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslQlExample {


    public static void main(String[] args) {
        // 娉ㄥ唽绫诲瀷
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);

        // 婕旂ず鐩稿鏃堕棿
        String dsl = """
                {
                  "MODEL": "Device",
                   "COUNT": 2,
                  "FIELDS": {
                    "status": {"$CHOICE": ["OFFLINE", "ONLINE"]},
                    "deviceId": {"$EXPR": "join('device-', '1')"},
                     "deviceGroupId": {"$JOIN": ["device-", "&.index"]},
                    "onlineStrategy": {
                      "$EXPR": {
                        "lang": "ql",
                        "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
                      }
                    }
                  }
                }
                """;
        List<Device> relativeTimeExamples = JsonDslEngine.generateList(dsl, Device.class);
        relativeTimeExamples.forEach(System.out::println);
    }
}