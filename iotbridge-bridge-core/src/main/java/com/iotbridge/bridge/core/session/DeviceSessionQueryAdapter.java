package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.spi.session.DeviceSessionQuery;
import com.iotbridge.spi.session.DeviceSessionView;
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
        return channelManager.isOnline(deviceId);
    }

    @Override
    public int onlineCount() {
        // 统一口径：在线 = 通道 isActive()，与 onlineDevices 快照一致，不构造 TreeMap 只为取数量
        return channelManager.countActive();
    }

    @Override
    public List<DeviceSessionView> onlineDevices() {
        Map<String, String> snapshot = channelManager.snapshotOnlineDevices();
        List<DeviceSessionView> views = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            views.add(new DeviceSessionView(entry.getKey(), entry.getValue()));
        }
        return views;
    }
}
