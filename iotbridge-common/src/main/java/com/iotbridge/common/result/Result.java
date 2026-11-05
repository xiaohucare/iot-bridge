package com.iotbridge.common.result;

import com.iotbridge.common.exception.ErrorCodeEnum;

/**
 * 统一响应包装体。
 *
 * <p>字段使用包装类型，符合 POJO 规范；对外提供静态工厂方法简化构造。</p>
 *
 * @param <T> 业务数据类型
 * @author iotbridge
 * @date 2026-11-05
 */
public class Result<T> {

    /**
     * 是否成功。
     */
    private Boolean success;

    /**
     * 结果码。
     */
    private String code;

    /**
     * 结果描述。
     */
    private String message;

    /**
     * 业务数据。
     */
    private T data;

    public Result() {
    }

    private Result(Boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造无数据的成功响应。
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok() {
        return new Result<>(Boolean.TRUE, ErrorCodeEnum.SUCCESS.getCode(),
                ErrorCodeEnum.SUCCESS.getMessage(), null);
    }

    /**
     * 构造带数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(Boolean.TRUE, ErrorCodeEnum.SUCCESS.getCode(),
                ErrorCodeEnum.SUCCESS.getMessage(), data);
    }

    /**
     * 构造失败响应。
     *
     * @param errorCode 错误码枚举
     * @param <T>       数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ErrorCodeEnum errorCode) {
        return new Result<>(Boolean.FALSE, errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 构造自定义描述的失败响应。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误描述
     * @param <T>       数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ErrorCodeEnum errorCode, String message) {
        return new Result<>(Boolean.FALSE, errorCode.getCode(), message, null);
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{success=" + success + ", code='" + code + "', message='" + message
                + "', data=" + data + '}';
    }
}
