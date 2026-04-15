package com.xa.mass.base.example.old;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslQlExample {


    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("Task", Task.class);

        // 演示相对时间
        String dsl = """
                {
                  "MODEL": "Worker",
                   "COUNT": 2,
                  "FIELDS": {
                    "status": {"$CHOICE": ["OFFLINE", "ONLINE"]},
                    "workerId": {"$EXPR": "join('worker-', '1')"},
                     "workerGroupId": {"$JOIN": ["worker-", "&.index"]},
                    "onlineStrategy": {
                      "$EXPR": {
                        "lang": "ql",
                        "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
                      }
                    }
                  }
                }
                """;
        List<Worker> relativeTimeExamples = JsonDslEngine.generateList(dsl, Worker.class);
        relativeTimeExamples.forEach(System.out::println);
    }
}
