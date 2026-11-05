package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.spi.session.DeviceSessionQuery;
import com.iotbridge.spi.session.DeviceSessionView;
import com.iotbridge.spi.session.SessionKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在线设备会话查询适配实现。
 *
 * <p>把接入层 {@link ChannelManager} 的在线快照转换为 spi 层的 {@link DeviceSessionView}，
 * 供 admin 消费；使传输层细节（Netty {@code Channel}）不外泄到上层。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DeviceSessionQueryAdapter implements DeviceSessionQuery {

    private final ChannelManager channelManager;

    public DeviceSessionQueryAdapter(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public boolean isOnline(String deviceId) {
        // 运维便捷查询：不区分协议，任一协议下在线即视为在线
        return channelManager.isDeviceOnline(deviceId);
    }

    @Override
    public boolean isOnline(String protocolType, String deviceId) {
        return channelManager.isOnline(protocolType, deviceId);
    }

    @Override
    public int onlineCount() {
        // 统一口径：在线 = 通道 isActive()，与 onlineDevices 快照一致，不构造 TreeMap 只为取数量
        return channelManager.countActive();
    }

    @Override
    public List<DeviceSessionView> onlineDevices() {
        return toViews(channelManager.snapshotOnlineDevices(), null);
    }

    @Override
    public List<DeviceSessionView> onlineDevices(String protocolType) {
        return toViews(channelManager.snapshotOnlineDevices(), protocolType);
    }

    /**
     * 将在线快照转为视图列表，可选按协议类型过滤。
     *
     * @param snapshot     {@code SessionKey -> 远端地址} 的在线快照（已按协议、设备号升序）
     * @param protocolType 协议类型；为 {@code null} 或空串时不过滤
     * @return 命中的会话视图列表
     */
    private List<DeviceSessionView> toViews(Map<SessionKey, String> snapshot, String protocolType) {
        boolean filterByProtocol = protocolType != null && !protocolType.isEmpty();
        List<DeviceSessionView> views = new ArrayList<>(snapshot.size());
        for (Map.Entry<SessionKey, String> entry : snapshot.entrySet()) {
            SessionKey key = entry.getKey();
            if (filterByProtocol && !protocolType.equals(key.getProtocolType())) {
                continue;
            }
            views.add(new DeviceSessionView(key.getProtocolType(), key.getDeviceId(), entry.getValue()));
        }
        return views;
    }
}
