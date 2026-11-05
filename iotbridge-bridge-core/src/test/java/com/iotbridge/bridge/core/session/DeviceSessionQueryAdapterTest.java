package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.spi.session.DeviceSessionView;
import com.iotbridge.spi.session.SessionKey;
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
        Map<SessionKey, String> snapshot = new TreeMap<>();
        snapshot.put(SessionKey.of("demo-text", "devA"), "/127.0.0.1:1000");
        snapshot.put(SessionKey.of("sos-c02", "devB"), "/127.0.0.1:1001");
        when(channelManager.snapshotOnlineDevices()).thenReturn(snapshot);
        when(channelManager.countActive()).thenReturn(2);
        when(channelManager.isDeviceOnline("devA")).thenReturn(Boolean.TRUE);
        when(channelManager.isOnline("sos-c02", "devB")).thenReturn(Boolean.TRUE);

        DeviceSessionQueryAdapter adapter = new DeviceSessionQueryAdapter(channelManager);

        assertEquals(2, adapter.onlineCount());
        // 跨协议聚合判定 + 按协议精确判定
        assertTrue(adapter.isOnline("devA"));
        assertTrue(adapter.isOnline("sos-c02", "devB"));
        List<DeviceSessionView> devices = adapter.onlineDevices();
        assertEquals(2, devices.size());
        // 快照按 protocolType、deviceId 升序：demo-text 在前
        assertEquals("demo-text", devices.get(0).getProtocolType());
        assertEquals("devA", devices.get(0).getDeviceId());
        assertEquals("/127.0.0.1:1000", devices.get(0).getRemoteAddress());
        assertEquals("sos-c02", devices.get(1).getProtocolType());
    }

    @Test
    void emptySnapshotYieldsEmptyList() {
        ChannelManager channelManager = mock(ChannelManager.class);
        when(channelManager.snapshotOnlineDevices()).thenReturn(Map.of());

        DeviceSessionQueryAdapter adapter = new DeviceSessionQueryAdapter(channelManager);

        assertEquals(0, adapter.onlineCount());
        assertTrue(adapter.onlineDevices().isEmpty());
        assertTrue(adapter.onlineDevices("sos-c02").isEmpty());
    }

    @Test
    void filtersOnlineDevicesByProtocol() {
        ChannelManager channelManager = mock(ChannelManager.class);
        Map<SessionKey, String> snapshot = new TreeMap<>();
        // 相同设备号 devX 分属两个协议，互不覆盖
        snapshot.put(SessionKey.of("demo-text", "devX"), "/127.0.0.1:1000");
        snapshot.put(SessionKey.of("sos-c02", "devX"), "/127.0.0.1:1001");
        snapshot.put(SessionKey.of("sos-c02", "devY"), "/127.0.0.1:1002");
        when(channelManager.snapshotOnlineDevices()).thenReturn(snapshot);

        DeviceSessionQueryAdapter adapter = new DeviceSessionQueryAdapter(channelManager);

        // 不过滤（null/空）等价全量
        assertEquals(3, adapter.onlineDevices(null).size());
        assertEquals(3, adapter.onlineDevices("").size());
        // 按协议聚焦
        List<DeviceSessionView> sosOnly = adapter.onlineDevices("sos-c02");
        assertEquals(2, sosOnly.size());
        assertTrue(sosOnly.stream().allMatch(v -> "sos-c02".equals(v.getProtocolType())));
        List<DeviceSessionView> textOnly = adapter.onlineDevices("demo-text");
        assertEquals(1, textOnly.size());
        assertEquals("devX", textOnly.get(0).getDeviceId());
        // 未知协议命中为空
        assertTrue(adapter.onlineDevices("ghost").isEmpty());
    }
}
