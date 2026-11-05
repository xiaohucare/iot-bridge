package com.iotbridge.spi.session;

import java.util.Objects;

/**
 * 设备会话键：由协议类型 + 设备标识组成的不可变值对象。
 *
 * <p>多协议并行接入下，{@code deviceId} 仅在单一协议域内保证唯一，跨协议可能重号，故会话寻址
 * （在线注册、下行投递、离线暂存与补发）统一以 {@code (protocolType, deviceId)} 为复合键，
 * 避免不同协议相同设备号互相覆盖。{@code protocolType} 为空归一化为空串，保证键语义一致。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class SessionKey implements Comparable<SessionKey> {

    /**
     * 归一化后的协议类型（空串表示默认协议）。
     */
    private final String protocolType;

    /**
     * 设备标识。
     */
    private final String deviceId;

    /**
     * 构造会话键。
     *
     * @param protocolType 协议类型，{@code null} 归一化为空串
     * @param deviceId     设备标识
     */
    public SessionKey(String protocolType, String deviceId) {
        this.protocolType = protocolType == null ? "" : protocolType;
        this.deviceId = deviceId;
    }

    /**
     * 工厂方法，语义同构造函数。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @return 会话键
     */
    public static SessionKey of(String protocolType, String deviceId) {
        return new SessionKey(protocolType, deviceId);
    }

    public String getProtocolType() {
        return protocolType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SessionKey)) {
            return false;
        }
        SessionKey other = (SessionKey) obj;
        return protocolType.equals(other.protocolType) && Objects.equals(deviceId, other.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocolType, deviceId);
    }

    /**
     * 自然序：先按 protocolType、再按 deviceId 升序，便于在线快照稳定输出。
     *
     * @param other 另一会话键
     * @return 比较结果
     */
    @Override
    public int compareTo(SessionKey other) {
        int cmp = protocolType.compareTo(other.protocolType);
        return cmp != 0 ? cmp : String.valueOf(deviceId).compareTo(String.valueOf(other.deviceId));
    }

    @Override
    public String toString() {
        return "SessionKey{protocolType='" + protocolType + "', deviceId='" + deviceId + "'}";
    }
}
