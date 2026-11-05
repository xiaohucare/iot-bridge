package com.iotbridge.spi.session;

/**
 * 在线设备会话视图：网关侧对某设备当前 TCP 会话的只读快照。
 *
 * <p>字段使用不可变 {@code String}，不含任何 Netty 类型，便于跨层（admin/运维接口）安全传递。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class DeviceSessionView {

    /**
     * 会话所属协议类型（多协议并行下用于区分相同设备号）。
     */
    private final String protocolType;

    /**
     * 设备唯一标识。
     */
    private final String deviceId;

    /**
     * 设备 TCP 远端地址描述。
     */
    private final String remoteAddress;

    /**
     * 构造会话视图。
     *
     * @param protocolType  协议类型
     * @param deviceId      设备标识
     * @param remoteAddress 远端地址
     */
    public DeviceSessionView(String protocolType, String deviceId, String remoteAddress) {
        this.protocolType = protocolType;
        this.deviceId = deviceId;
        this.remoteAddress = remoteAddress;
    }

    public String getProtocolType() {
        return protocolType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public String toString() {
        return "DeviceSessionView{protocolType='" + protocolType + "', deviceId='" + deviceId
                + "', remoteAddress='" + remoteAddress + "'}";
    }
}
