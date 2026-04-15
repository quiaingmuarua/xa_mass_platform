package com.xa.mass.base.example.old;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslEngineExample {

    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("Task", Task.class);

        // 演示相对时间
        String relativeTimeExampleDsl = """
                {
                  "MODEL": "Worker",
                  "COUNT": 10,
                  "FIELDS": {
                    "workerId": {"$JOIN": ["worker-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "createTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"},
                  "updateTime": {"$TIME_RANGE": ["now-2h", "now", "MINUTES", "yyyy-MM-dd HH:mm:ss"]}
                  }
                }
                """;
        List<Worker> relativeTimeExamples = JsonDslEngine.generateList(relativeTimeExampleDsl, Worker.class);
        System.out.println("\n=== Relative Time Examples ===");
        relativeTimeExamples.forEach(System.out::println);


    }
}
