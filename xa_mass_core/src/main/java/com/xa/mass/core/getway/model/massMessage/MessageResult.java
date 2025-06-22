package com.xa.mass.core.getway.model.massMessage;

public class MessageResult {
    private int code;        // "200", "500", 自定义业务码
    private String message;     // 结果描述
//    private Object data;        // 可选：附加数据，如执行结果、错误详情等


    public MessageResult() {
        this.code = 200;
        this.message = "success";
    }

    public MessageResult(int code, String message) {
        this.code = code;
        this.message = message;
    }

    // Getter 和 Setter 方法
    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }


    public void setMessage(String message) {
        this.message = message;
    }


}
