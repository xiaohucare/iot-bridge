package com.iotbridge.common.exception;

/**
 * 业务错误码枚举。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public enum ErrorCodeEnum {

    /**
     * 成功。
     */
    SUCCESS("0", "成功"),

    /**
     * 请求参数非法。
     */
    BAD_REQUEST("A0400", "请求参数非法"),

    /**
     * 未通过鉴权（缺少或错误的 API Key）。
     */
    UNAUTHORIZED("A0301", "未通过鉴权"),

    /**
     * 系统内部错误。
     */
    SYSTEM_ERROR("B0001", "系统内部错误"),

    /**
     * 未找到对应协议编解码插件。
     */
    PROTOCOL_NOT_FOUND("B0100", "未找到对应协议插件"),

    /**
     * 协议解码失败。
     */
    DECODE_ERROR("B0101", "协议解码失败"),

    /**
     * 协议编码失败。
     */
    ENCODE_ERROR("B0102", "协议编码失败"),

    /**
     * 设备当前离线，指令无法下发。
     */
    DEVICE_OFFLINE("B0200", "设备离线"),

    /**
     * 下行指令发布失败（broker 未确认、不可路由或超时）。
     */
    DOWNLINK_PUBLISH_FAILED("B0300", "下行指令发布失败");

    private final String code;
    private final String message;

    ErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
