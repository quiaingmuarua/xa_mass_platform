package com.xa.mass.base.jsondsl.processor;

import java.util.Arrays;
import java.util.List;

/**
 * 重载测试 - 验证泛型擦除对重载的影响
 */
public class OverloadTest {

    public static void main(String[] args) {
        System.out.println("=== Java 重载机制测试 ===\n");

        // 测试数据
        String single = "test";
        List<String> list = Arrays.asList("a", "b", "c");

        System.out.println("1. 测试重载方法调用:");
        System.out.println("   单个对象: " + testOverload(single));
        System.out.println("   列表对象: " + testOverload(list));

        System.out.println("\n2. 泛型擦除说明:");
        System.out.println("   - 泛型擦除后，List<String> 变成 List");
        System.out.println("   - 重载无法区分 List<T> 和 T");
        System.out.println("   - 编译器会报错或选择第一个匹配的方法");

        System.out.println("\n3. 解决方案:");
        System.out.println("   - 使用不同方法名: filter() vs filterList()");
        System.out.println("   - 使用具体类型: filter(User) vs filter(List<User>)");
        System.out.println("   - 使用不同参数类型: filter(T) vs filter(Collection<T>)");

        System.out.println("\n=== 测试完成 ===");
    }

    // 模拟重载方法
    public static String testOverload(Object data) {
        return "Object 方法被调用: " + data.getClass().getSimpleName();
    }

    // 这个重载在泛型擦除后不会生效
    public static String testOverload(List data) {
        return "List 方法被调用: " + data.getClass().getSimpleName();
    }
} 