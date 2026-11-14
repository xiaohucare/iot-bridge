package com.iotbridge.spi.security;

/**
 * 设备接入认证结果。
 *
 * <p>由 {@link DeviceAuthenticator} 返回，调用方（TCP 上行分发 / MQTT CONNECT）据此决定是否
 * 允许该设备建立/维持会话。区分「未配置」「未知设备」「已停用」「凭据不符」「来源受限」，
 * 是为了让告警与审计能定位到具体原因，而不是笼统的“认证失败”。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
public enum DeviceAuthResult {

    /**
     * 认证通过，允许接入。
     */
    ALLOWED("allowed"),

    /**
     * 认证器未启用（未配置设备注册表），未做实际校验。
     */
    NOT_CONFIGURED("notConfigured"),

    /**
     * 设备未在注册表中登记。
     */
    UNKNOWN_DEVICE("unknownDevice"),

    /**
     * 设备已登记但被停用（吊销）。
     */
    DISABLED("disabled"),

    /**
     * 凭据（密码/证书指纹）不匹配。
     */
    BAD_CREDENTIAL("badCredential"),

    /**
     * 来源地址不在该设备的允许范围内。
     */
    NETWORK_DENIED("networkDenied");

    private final String tag;

    DeviceAuthResult(String tag) {
        this.tag = tag;
    }

    /**
     * 指标标签值（小写驼峰，避免高基数且可读）。
     *
     * @return 标签值
     */
    public String tag() {
        return tag;
    }

    /**
     * 是否通过认证。
     *
     * @return 通过为 {@code true}
     */
    public boolean isAllowed() {
        return this == ALLOWED || this == NOT_CONFIGURED;
    }
}
