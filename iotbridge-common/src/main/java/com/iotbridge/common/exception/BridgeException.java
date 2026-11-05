package com.iotbridge.common.exception;

/**
 * 网关业务运行时异常。
 *
 * <p>携带 {@link ErrorCodeEnum} 以便上层统一转换为响应体。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class BridgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 业务错误码。
     */
    private final String code;

    /**
     * 以错误码枚举构造异常，使用枚举默认描述。
     *
     * @param errorCode 错误码枚举，不可为 {@code null}
     */
    public BridgeException(ErrorCodeEnum errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 以错误码枚举 + 自定义描述构造异常。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误描述
     */
    public BridgeException(ErrorCodeEnum errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 以错误码枚举 + 自定义描述 + 根因构造异常。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误描述
     * @param cause     根因异常
     */
    public BridgeException(ErrorCodeEnum errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public String getCode() {
        return code;
    }
}
