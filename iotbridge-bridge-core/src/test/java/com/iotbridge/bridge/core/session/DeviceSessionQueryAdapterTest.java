package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.spi.session.DeviceSessionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DeviceSessionQueryAdapter} 在线会话查询适配测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class DeviceSessionQueryAdapterTest {

    @Test
    void mapsSnapshotToViewsAndCount() {
        ChannelManager channelManager = mock(ChannelManager.class);
        Map<String, String> snapshot = new TreeMap<>();
        snapshot.put("devA", "/127.0.0.1:1000");
        snapshot.put("devB", "/127.0.0.1:1001");
        when(channelManager.snapshotOnlineDevices()).thenReturn(snapshot);
        when(channelManager.countActive()).thenReturn(2);
        when(channelManager.isOnline("devA")).thenReturn(Boolean.TRUE);

        DeviceSessionQueryAdapter adapter = new DeviceSessionQueryAdapter(channelManager);

        assertEquals(2, adapter.onlineCount());
        assertTrue(adapter.isOnline("devA"));
        List<DeviceSessionView> devices = adapter.onlineDevices();
        assertEquals(2, devices.size());
        assertEquals("devA", devices.get(0).getDeviceId());
        assertEquals("/127.0.0.1:1000", devices.get(0).getRemoteAddress());
    }

    @Test
    void emptySnapshotYieldsEmptyList() {
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.snapshotOnlineDevices()).thenReturn(Map.of());

        DeviceSessionQueryAdapter adapter = new DeviceSessionQueryAdapter(channelManager);

        assertEquals(0, adapter.onlineCount());
        assertTrue(adapter.onlineDevices().isEmpty());
    }
}
