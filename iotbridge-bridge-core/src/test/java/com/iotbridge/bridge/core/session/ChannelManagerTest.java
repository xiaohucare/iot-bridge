package com.iotbridge.bridge.core.session;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.spi.session.SessionKey;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelManager} 协议+设备会话注册、下线与隔离测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class ChannelManagerTest {

    private Channel newChannel(String channelId) {
        Channel channel = mock(Channel.class);
        ChannelId id = mock(ChannelId.class);
        when(channel.id()).thenReturn(id);
        when(id.asLongText()).thenReturn(channelId);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
        when(channel.isActive()).thenReturn(Boolean.TRUE);
        return channel;
    }

    @Test
    void registerThenOnlineAndSend() {
        ChannelManager manager = new ChannelManager();
        Channel channel = newChannel("chan-1");

        manager.register("sos-c02", "dev001", channel);

        assertTrue(manager.isOnline("sos-c02", "dev001"));
        assertEquals(1, manager.onlineCount());
        boolean delivered = manager.sendToDevice("sos-c02", "dev001", "hi".getBytes(StandardCharsets.UTF_8));
        assertTrue(delivered);
        verify(channel).writeAndFlush(any());
    }

    @Test
    void unbindByChannelShouldMarkOffline() {
        ChannelManager manager = new ChannelManager();
        Channel channel = newChannel("chan-2");
        manager.register("sos-c02", "dev002", channel);

        manager.unbindByChannel(channel);

        assertFalse(manager.isOnline("sos-c02", "dev002"));
        assertEquals(0, manager.onlineCount());
    }

    @Test
    void sendToDeviceOfflineShouldReturnFalse() {
        ChannelManager manager = new ChannelManager();

        assertFalse(manager.sendToDevice("sos-c02", "ghost", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sameDeviceIdAcrossProtocolsAreIsolated() {
        ChannelManager manager = new ChannelManager();
        Channel sosChannel = newChannel("chan-sos");
        Channel demoChannel = newChannel("chan-demo");

        // 不同协议、相同 deviceId 各自独立登记，互不覆盖
        manager.register("sos-c02", "devX", sosChannel);
        manager.register("demo-text", "devX", demoChannel);

        assertTrue(manager.isOnline("sos-c02", "devX"));
        assertTrue(manager.isOnline("demo-text", "devX"));
        assertEquals(2, manager.onlineCount());

        // 断开 SOS 会话仅影响该协议，demo-text 仍在线
        manager.unbindByChannel(sosChannel);
        assertFalse(manager.isOnline("sos-c02", "devX"));
        assertTrue(manager.isOnline("demo-text", "devX"));
        // 跨协议聚合：任一协议在线即算在线
        assertTrue(manager.isDeviceOnline("devX"));
    }

    @Test
    void snapshotOnlineDevicesShouldExcludeInactive() {
        ChannelManager manager = new ChannelManager();
        Channel active = newChannel("chan-a");
        Channel inactive = newChannel("chan-b");
        when(inactive.isActive()).thenReturn(Boolean.FALSE);

        manager.register("sos-c02", "devA", active);
        manager.register("demo-text", "devB", inactive);

        Map<SessionKey, String> snapshot = manager.snapshotOnlineDevices();
        assertTrue(snapshot.containsKey(SessionKey.of("sos-c02", "devA")));
        assertFalse(snapshot.containsKey(SessionKey.of("demo-text", "devB")));
        assertEquals(1, snapshot.size());
    }

    @Test
    void countActiveShouldExcludeInactiveButOnlineCountKeepsAll() {
        ChannelManager manager = new ChannelManager();
        Channel active = newChannel("chan-c");
        Channel inactive = newChannel("chan-d");
        when(inactive.isActive()).thenReturn(Boolean.FALSE);

        manager.register("sos-c02", "devC", active);
        manager.register("sos-c02", "devD", inactive);

        assertEquals(1, manager.countActive());
        // onlineCount 为登记会话数（含尚未清理的死连接），与 countActive 口径区分
        assertEquals(2, manager.onlineCount());
    }
}
