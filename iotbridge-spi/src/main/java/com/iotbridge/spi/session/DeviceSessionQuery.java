package com.iotbridge.spi.session;

import java.util.List;

/**
 * 在线设备会话查询接口。
 *
 * <p>契约置于 spi 层，由 bridge-core 适配 access-netty 的会话管理实现，供 admin 等上层消费，
 * 避免 admin 直接依赖传输层。用于运维排查“设备是否在线、当前有哪些设备在线”。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public interface DeviceSessionQuery {

    /**
     * 判断设备当前是否在线（任意协议下存在活动 TCP 通道）。跨协议聚合判定，便于运维快速排查。
     *
     * @param deviceId 设备标识
     * @return 在线返回 {@code true}
     */
    boolean isOnline(String deviceId);

    /**
     * 判断指定协议下的设备会话是否在线（精确判定，区分不同协议相同设备号）。
     *
     * @param protocolType 协议类型（为空回退默认协议）
     * @param deviceId     设备标识
     * @return 在线返回 {@code true}
     */
    boolean isOnline(String protocolType, String deviceId);

    /**
     * 当前在线设备数量。
     *
     * @return 在线设备数
     */
    int onlineCount();

    /**
     * 当前在线设备快照（按 protocolType、deviceId 升序）。
     *
     * @return 在线会话视图列表，无在线设备时返回空列表
     */
    List<DeviceSessionView> onlineDevices();

    /**
     * 当前在线设备快照，按协议类型过滤（多协议并行下聚焦单一协议域）。
     *
     * @param protocolType 协议类型；为 {@code null} 或空串时不过滤，等价于 {@link #onlineDevices()}
     * @return 命中的在线会话视图列表（按 deviceId 升序），无命中时返回空列表
     */
    List<DeviceSessionView> onlineDevices(String protocolType);
}
