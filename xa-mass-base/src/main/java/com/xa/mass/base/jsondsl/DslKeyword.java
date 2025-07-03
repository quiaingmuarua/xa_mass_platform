package com.xa.mass.base.jsondsl;

/**
 * JSON-DSL 关键字枚举，定义了 mock 模板中使用的核心关键字。
 * 这些关键字用于构建 mock 数据的 DSL 语法。
 */
public enum DslKeyword {
    /**
     * 指定要生成的模型类名。
     * 可以是注册的类型别名或全类名。
     * 示例: "Device", "com.xa.mass.base.model.Task"
     */
    MODEL,

    /**
     * 字段配置映射，定义对象各字段的生成规则。
     * 支持内置函数、嵌套对象、集合等复杂配置。
     * 示例: {"deviceId": "device-{i}", "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}}
     */
    FIELDS,

    /**
     * 生成数量，指定要生成的对象个数。
     * 默认为 1，当大于 1 时会生成对象列表。
     * 示例: 3 表示生成 3 个对象
     */
    COUNT,

    /**
     * 集合类型，用于指定生成的集合类型。
     * 支持 "LIST" 和 "SET" 两种类型。
     * 仅在 FIELDS 中定义集合字段时使用。
     * 示例: "LIST" 生成 ArrayList, "SET" 生成 HashSet
     */
    TYPE;
} 