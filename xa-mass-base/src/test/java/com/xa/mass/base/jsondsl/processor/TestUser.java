package com.xa.mass.base.jsondsl.processor;

/**
 * 测试用的用户类
 */
public class TestUser {
    private String name;
    private String age;
    private String email;

    public TestUser() {
    }

    public TestUser(String name, String age) {
        this.name = name;
        this.age = age;
    }

    public TestUser(String name, String age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAge() {
        return age;
    }

    public void setAge(String age) {
        this.age = age;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Override
    public String toString() {
        return "TestUser{" +
                "name='" + name + '\'' +
                ", age=" + age +
                ", email='" + email + '\'' +
                '}';
    }
} 