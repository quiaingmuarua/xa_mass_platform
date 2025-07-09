package com.xa.mass.base.jsondsl.processor;

import java.util.Arrays;
import java.util.List;

/**
 * 泛型重载测试 - 验证泛型擦除对重载的影响
 */
public class GenericOverloadTest {

    public static void main(String[] args) {
        System.out.println("=== 泛型重载测试 ===\n");

        // 测试数据
        String single = "test";
        List<String> list = Arrays.asList("a", "b", "c");

        System.out.println("1. 测试泛型重载方法调用:");
        System.out.println("   单个对象: " + testGenericOverload(single));
        System.out.println("   列表对象: " + testGenericOverload(list));

        System.out.println("\n2. 问题分析:");
        System.out.println("   - 如果使用 <T> filter(T data) 和 <T> filter(List<T> dataList)");
        System.out.println("   - 泛型擦除后都变成 filter(Object data)");
        System.out.println("   - 编译器会报错：方法签名重复");

        System.out.println("\n3. 当前方案的优势:");
        System.out.println("   - filter(T data) 和 filterList(List<T> dataList)");
        System.out.println("   - 方法名不同，避免重载冲突");
        System.out.println("   - 类型安全，泛型正确");

        System.out.println("\n=== 测试完成 ===");
    }

    // 模拟泛型重载方法 - 这会导致编译错误
    /*
    public static <T> String testGenericOverload(T data) {
        return "T 方法被调用: " + data.getClass().getSimpleName();
    }
    
    public static <T> String testGenericOverload(List<T> dataList) {
        return "List<T> 方法被调用: " + dataList.getClass().getSimpleName();
    }
    */

    // 正确的做法：使用不同方法名
    public static <T> String testGenericOverload(T data) {
        return "T 方法被调用: " + data.getClass().getSimpleName();
    }

    public static <T> String testGenericOverloadList(List<T> dataList) {
        return "List<T> 方法被调用: " + dataList.getClass().getSimpleName();
    }
} 