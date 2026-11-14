package com.iotbridge.spi.security;

/**
 * 一次设备接入认证的输入：设备标识 + 传输层可获得的凭据。
 *
 * <p>TCP 接入没有协议内认证字段（SOS-C02 帧不存在凭据位），因此只能携带来源地址；
 * 若网关开启 mTLS，可把客户端证书指纹填入 {@code certFingerprint}。
 * MQTT 接入可携带 CONNECT 的密码。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
public final class DeviceCredential {

    private final String protocolType;
    private final String deviceId;
    private final String password;
    private final String certFingerprint;
    private final String remoteAddress;

    private DeviceCredential(String protocolType, String deviceId, String password,
                             String certFingerprint, String remoteAddress) {
        this.protocolType = protocolType;
        this.deviceId = deviceId;
        this.password = password;
        this.certFingerprint = certFingerprint;
        this.remoteAddress = remoteAddress;
    }

    /**
     * 构造认证输入。
     *
     * @param protocolType    协议标识（与下行寻址同一口径）
     * @param deviceId        设备号（TCP 为帧内 IMEI，MQTT 为 clientId）
     * @param password        传输层密码，无则为 {@code null}
     * @param certFingerprint 客户端证书 SHA-256 指纹（十六进制，可含冒号），无则为 {@code null}
     * @param remoteAddress   远端地址（形如 {@code /10.0.0.1:52341} 或 {@code 10.0.0.1}）
     * @return 认证输入
     */
    public static DeviceCredential of(String protocolType, String deviceId, String password,
                                      String certFingerprint, String remoteAddress) {
        return new DeviceCredential(protocolType, deviceId, password, certFingerprint, remoteAddress);
    }

    /**
     * 仅带来源地址的认证输入（TCP 无凭据场景）。
     *
     * @param protocolType  协议标识
     * @param deviceId      设备号
     * @param remoteAddress 远端地址
     * @return 认证输入
     */
    public static DeviceCredential addressOnly(String protocolType, String deviceId, String remoteAddress) {
        return new DeviceCredential(protocolType, deviceId, null, null, remoteAddress);
    }

    public String getProtocolType() {
        return protocolType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getPassword() {
        return password;
    }

    public String getCertFingerprint() {
        return certFingerprint;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }
}
