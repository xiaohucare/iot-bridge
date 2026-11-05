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
     * 判断设备当前是否在线（存在活动 TCP 通道）。
     *
     * @param deviceId 设备标识
     * @return 在线返回 {@code true}
     */
    boolean isOnline(String deviceId);

    /**
     * 当前在线设备数量。
     *
     * @return 在线设备数
     */
    int onlineCount();

    /**
     * 当前在线设备快照（按 deviceId 升序）。
     *
     * @return 在线会话视图列表，无在线设备时返回空列表
     */
    List<DeviceSessionView> onlineDevices();
}
