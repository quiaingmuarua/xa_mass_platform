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
          "FIELDS": {
            "status": {"$CHOICE": ["OFFLINE", "ONLINE"]},
            "onlineStrategy": {
              "$EXPR": {
                "lang": "ql",
                "expr": "status == 'OFFLINE' ? 0 : random(10, 100)"
              }
            }
          }
        }
        """;
        Object obj = com.xa.mass.base.jsondsl.JsonDslEngine.generate(dsl).get(0);
        System.out.println(obj);
    }
}
